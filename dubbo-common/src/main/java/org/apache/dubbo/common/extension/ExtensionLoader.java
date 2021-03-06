/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 *
 *  对 Java SPI 增强
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * Dubbo 中一个扩展接口对应一个 ExtensionLoader 实例，该集合缓存了全部 ExtensionLoader 实例，其中的 Key 为扩展接口，Value 为加载其扩展实现的 ExtensionLoader 实例。
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    /**
     *  该集合缓存了扩展实现类与其实例对象的映射关系。在前文示例中，Key 为 Class，Value 为 DubboProtocol 对象。
     *
     *   例如：扩展实现类集合, key 为 Protocol , value 为 DubboProtocol
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();

    // ==============================

    /**
     * 当前 ExtensionLoader 实例负责加载扩展接口, 比如 Protocol
     */
    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    /*
     * 缓存了该 ExtensionLoader 加载的扩展实现类与扩展名之间的映射关系。
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    /**
     * 则获取class上的 `SPI` 注解，根据该注解的定义的name作为key
     *    缓存了该 ExtensionLoader 加载的扩展名与扩展实现类之间的映射关系。cachedNames 集合的反向关系缓存。
     *
     *    1、例如: DubboProtocol （没有Adaptive注解，同时只有无参构造器）
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();

    /**
     * 缓存了该 ExtensionLoader 加载的扩展名与扩展实现对象之间的映射关系。
     *   例如: 缓存的扩展对象集合 key 为 dubbo, value 为 DubboProtocol
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     * 缓存适配器实例。
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();

    /**
     * 如果这个class含有 `Adaptive` 注解，则将 class 设置 为。
     *
     *  1、例如: AdaptiveCompiler (如果这个class含有Adaptive注解)
     */
    private volatile Class<?> cachedAdaptiveClass = null;

    /*
     * 记录了 type 这个扩展接口上 @SPI 注解的 value 值，也就是默认扩展名。
     */
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    /**
     *  扩展增强 Wrapper 实现类集合
     * 尝试获取带对应接口参数的 `构造器`，如果能够获取到，则说明这个class是一个装饰类即，需要存到
     *
     *  1、例如: ProtocolListenerWrapper 构造函数，入参是 `Protocol`。
     */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;

        /**
         *  根据 Class 获取 `ExtensionLoader`  {@link #getExtensionLoader(Class)}
         *  获取当前扩展接口对应的适配器对象。 {@link #getAdaptiveExtension()}
         *
         *   如果 type == ExtensionFactory, 那么 objectFactory 为 null。
         */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     *  根据 Class 获取 `ExtensionLoader`
     *
     *   【 Dubbo 每个扩展接口，对应着自己的ExtensionLoader对象 】
     *
     * @param type 例如: Protocol。
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 从缓存中获取一个 为null，则去 new ExtensionLoader
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {

            /**
             * {@link ExtensionLoader#ExtensionLoader(Class)}
             *
             *  `EXTENSION_LOADERS` 用于缓存所有的扩展加载实例。
             */
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {

            /**
             * 获取接口 所有扩展实现 {@link #getExtensionClasses()}
             */
            getExtensionClasses();

            // 遍历实现类
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();

                // @Activate注解
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                // 如果含有 注解 @Activate,  则获取注解 group、value 值
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }

                // 当前扩展实现组与我们传入 Group 匹配，且 value 值在 URL 中存在。
                if (isMatchGroup(group, activateGroup)) {
                    T ext = getExtension(name);
                    //  没有出现在values配置中的，即为默认激活的扩展实现
                    if (!names.contains(name)

                            // 通过"-"明确指定不激活该扩展实现
                            && !names.contains(REMOVE_VALUE_PREFIX + name)

                            /**
                             *  检测URL中是否出现了指定的Key {@link #isActive(String[], URL)}
                             */
                            && isActive(activateValue, url)) {

                        // 加载扩展实现的实例对象，这些都是激活的
                        exts.add(ext);
                    }
                }
            }

            // 按照 @Activate 注解中的 order 属性对默认激活的扩展集合进行排序。
            exts.sort(ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<>();

        // 按序添加自定义扩展实现类的对象。
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);

            // 通过"-"开头的配置明确指定不激活的扩展实现，直接就忽略了
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {

                        // 按照顺序，将自定义的扩展添加到默认扩展集合前面
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {

            // 按照顺序，将自定义的扩展添加到默认扩展集合后面
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {

            // 遍历当前 URL 中的所有属性值。
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();

                // url 中属性对的 key 等于扩展接口实现的 value 且值相等，则返回true。
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    /**
     * 获取或者创建一个 Holder。
     * @param name
     * @return
     */
    private Holder<Object> getOrCreateHolder(String name) {

        //检查缓存中是否存在
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {

            //缓存中不存在就去创建一个新的Holder
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {

        // 扩展实现名称是否合法。
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }

        // 如果为true，则加载默认扩展
        if ("true".equals(name)) {

            /**
             * {@link #getDefaultExtension()}
             */
            return getDefaultExtension();
        }

        /**
         * 根据 name 查找 cachedInstances 缓存的逻辑  {@link #getOrCreateHolder(String)}
         */
        Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();

        // / double-check 防止并发问题
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {

                    /**
                     * 加载扩展实现类,并实例化 {@link #createExtension(String)}
                     */
                    instance = createExtension(name);

                    // 扩展对象放入缓存
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }

        /**
         * 【 getExtension】{@link #getExtension(String)}
         */
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     *  {@link #getExtensionLoader(Class)} -> `getAdaptiveExtension`
     *   获取当前扩展接口对应的适配器对象。
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 从缓存中获取自适应拓展
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {

                            /**
                             *   创建适配器类 {@link #createAdaptiveExtension()}
                             */
                            instance = createAdaptiveExtension();

                            // 保存，适配器类。
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     *  创建 Extension
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        /**
         *
         *  获取 cachedClasses 缓存，根据扩展名从 cachedClasses 缓存中获取扩展实现类。如果 cachedClasses 未初始化，
         *  则会扫描前面介绍的三个 SPI 目录获取查找相应的 SPI 配置文件，然后加载其中的扩展实现类，
         *  最后将扩展名和扩展实现类的映射关系记录到 cachedClasses 缓存中。这部分逻辑在 loadExtensionClasses() 和 loadDirectory() 方法中。
         *
         *
         * 通过 name获取ExtensionClasses。 {@link #getExtensionClasses()}
         *
         *   例如：name: Protocol.  clazz: DubboProtocol
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {

            /*
             * 根据扩展实现类从 EXTENSION_INSTANCES 缓存中查找相应的实例。如果查找失败，会通过反射创建扩展实现对象。
             */
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {

                // 创建实例。
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }

            /**
             *  dubbo的 IOC反转控制，就是从spi和spring里面提取对象赋值。
             *  自动装配扩展实现对象中的属性（即调用其 setter） {@link #injectExtension(Object)}
             */
            injectExtension(instance);

            // Wrapper 对扩展实现进行功能增强。( 自动包装扩展实现对象 )
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {

                    /**
                     *  重新赋值成一个 wrapper （增强类）
                     *
                     *  注入依赖的扩展类对象 {@link #injectExtension(Object)}
                     */
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 注入适配器依赖的其他扩展点 【依赖注入】
     *
     *   重点：
     *      一个接口会存在多个实现类, 并不去注入一个具体的实现者,
     *          而是注入一个动态生成的实现者，这个动态生成的实现者的逻辑是确定的，能够根据不同的参数来使用不同的实现者实现相应的方法。
     *          这个动态生成的实现者的 class就是ExtensionLoader的Class<?> cachedAdaptiveClass
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {

                // 遍历扩展点实现类的所有方法。
                for (Method method : instance.getClass().getMethods()) {

                    /**
                     * set开头的方法 {@link #isSetter(Method)}
                     *  方法参数只有一个。
                     */
                    if (isSetter(method)) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         *
                         *  如果方法含有 `DisableInject` 注解，说明该属性不需要自动注入
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }

                        // 第一个参数是原始类型，不需要自动注入
                        Class<?> pt = method.getParameterTypes()[0];
                        if (ReflectUtils.isPrimitives(pt)) {
                            continue;
                        }
                        try {

                            /**
                             * 获取 setter 对应属性名，比如 setVersion 返回 version
                             */
                            String property = getSetterProperty(method);

                            /**
                             *  【 TODO 不清楚调用关系 】
                             *  从objectFactory 中获取所需要注入的实例 {@link #getExtension(String)}
                             */
                            Object object = objectFactory.getExtension(pt, property);

                            // 如果存在反射调用 setter() 方法进行属性注入。
                            if (object != null) {

                                /**
                                 * 使用反射方式。
                                 */
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     *
     *  set 开头，
     *  方法参数只有一个
     *  方法必须是public
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 获取该扩展接口的所有实现类的Class 对象
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {

        /**
         * cachedClasses 存储，`SPI 注解` Key: SPI 注解中的 `value`
         */
        Map<String, Class<?>> classes = cachedClasses.get();

        // 依然是双重校验+锁的方法去获取
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {

                    /**
                     *
                     *  缓存没有命中, 就会去加载
                     *      META-INF/dubbo ,
                     *      META-INF/dubbo/intenal,   ,
                     *      META-INF/service
                     *    目录下去加载
                     *
                     *   加载 {@link #loadExtensionClasses()}
                     */
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {

        /**
         * 获取默认扩展类名 {@link #cacheDefaultExtensionName()}
         */
        cacheDefaultExtensionName();

        // 在指定目录的 Jar 里面查找扩展点。
        Map<String, Class<?>> extensionClasses = new HashMap<>();

        /**
         *  从指定目录下加载 {@link #loadDirectory(Map, String, String)}
         *
         *  type: 扩展接口:
         *      第一个是 `ExtensionFactory` 在 {@link ExtensionLoader#ExtensionLoader(Class)}
         *          1、最终返回2个类。
         *                ‘用于从 Spring 的 IOC 容器中获取所需的拓展’  {@link org.apache.dubbo.config.spring.extension.SpringExtensionFactory}
         *                ‘创建自适应的拓展’  {@link org.apache.dubbo.common.extension.factory.SpiExtensionFactory}
         *
         *          2、 [ AdaptiveExtensionFactory ] {@link org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory
         *              因为类上有@Adaptive注解，直接被缓存在 {@link #cachedAdaptiveClass}
         *
         *      例如 Protocol。
         *
         */
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {

        // 获取扩展接口的 SPI 注解
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);

        // 检查是否存在注解
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }

                // 默认实现类的名称 放入 `cachedDefaultName`
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }
    }

    /**
     *
     *  加载指定目录，具体扩展实现类。
     *
     * @param extensionClasses
     * @param dir
     * @param type
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {

        /**
         * 文件名字:
         *     例如: META-INF/dubbo/Protocol
         */
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();

                    /**
                     * {@link #loadResource(Map, ClassLoader, java.net.URL)}
                     */
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {

                                /**
                                 * {@link #loadClass(Map, java.net.URL, Class, String)}
                                 */
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {

        // 类型不匹配抛出异常
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }

        // 如果是适配器类，则调用 `cacheAdaptiveClass` 来做缓存
        if (clazz.isAnnotationPresent(Adaptive.class)) {

            /**
             *  缓存到cachedAdaptiveClass字段 {@link #cacheAdaptiveClass(Class)}
             */
            cacheAdaptiveClass(clazz);

            /**
             * 如果是 Wrapper 类 {@link #isWrapperClass(Class)}
             *
             *  如果 clazz 的构造函数参数 type 类型，则说明是 Wrapper 类
             */
        } else if (isWrapperClass(clazz)) {

            /**
             *  添加到 `cacheWrapperClass` 中 {@link #cacheWrapperClass(Class)}
             */
            cacheWrapperClass(clazz);
        } else {

            // 剩余的则是扩展点的扩展实现类。
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {

                // 将包含@Activate注解的实现类缓存到cachedActivates集合中
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {

                    // 在cachedNames集合中缓存实现类->扩展名的映射
                    cacheName(clazz, n);

                    /**
                     *  在cachedClasses集合中缓存扩展名->实现类的映射 {@link #saveInExtensionClass(Map, Class, String)}
                     */
                    saveInExtensionClass(extensionClasses, clazz, name);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName());
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getClass().getName()
                    + ", " + clazz.getClass().getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     *
     *  如果 clazz 的构造函数参数 type 类型，则说明是 Wrapper 类。
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {

            // 判断 SPI 实现类 clazz 是否有参数类型为 type 的构造函数。
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建对应的适配器类。
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {

            /**
             *   注入适配器依赖的其他扩展点 {@link #injectExtension(Object)}
             *     由源文件动态编译成 class 对象。 {@link #getAdaptiveExtensionClass() }
             *   使用 `newInstance()` 生成扩展接口对应适配器类的实例。
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取一个适配器扩展点的类
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {

        /**
         *  获取该扩展接口的所有实现类的Class 对象 {@link #getExtensionClasses()}
         *
         *  存放
         *      1、cachedAdaptiveClass
         *      2、cacheWrapperClass
         */
        getExtensionClasses();

        // 如果 cachedAdaptiveClass 不为空，那么就返回 cachedAdaptiveClass。
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }

        /**
         * 创建适配器类 {@link #createAdaptiveExtensionClass()}
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 真正创建适配器类 Class对象。
     *
     *  【 该方法根据字符串代码生成适配器的Class对象并返回 】
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {

        /**
         *   生成字节码文件 {@link AdaptiveClassCodeGenerator#AdaptiveClassCodeGenerator(Class, String)}
         *  【 TODO 生成字符串代码 】 {@link AdaptiveClassCodeGenerator#generate()}
         *
         *   type: 扩展接口，例如: Protocol。
         */
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();

        /**
         * 获得类加载器 {@link #findClassLoader()}
         */
        ClassLoader classLoader = findClassLoader();

        /**
         *  获取 `Compiler` 实现类 “AdaptiveCompiler” {@link org.apache.dubbo.common.compiler.support.AdaptiveCompiler#compile(String, ClassLoader)}
         *
         *  因为 AdaptiveCompiler 有 `@Adaptive` 注解。
         *      原因是 `getAdaptiveExtensionClass` 中，优先执行 “cachedAdaptiveClass”。
         *
         * 【最终，动态编译】根据源文件生成Class 对象 {@link org.apache.dubbo.common.compiler.support.JavassistCompiler#compile(String, ClassLoader)}
         */
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
