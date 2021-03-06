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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;

/**
 * abstract ProtocolSupport.
 *
 *  AbstractProtocol提供了一些 Protocol 实现需要的公共能力以及公共字段。
 */
public abstract class AbstractProtocol implements Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // 用于存储出去的服务集合，其中的 Key 通过 ProtocolUtils.serviceKey() 方法创建的服务标识，
    protected final Map<String, Exporter<?>> exporterMap = new ConcurrentHashMap<String, Exporter<?>>();

    //TODO SoftReference
    // 服务引用的集合
    protected final Set<Invoker<?>> invokers = new ConcurrentHashSet<Invoker<?>>();

    protected static String serviceKey(URL url) {
        int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
        return serviceKey(port, url.getPath(), url.getParameter(VERSION_KEY), url.getParameter(GROUP_KEY));
    }

    /**
     *  serviceKey = serviceGroup / serviceName : serviceVersion : port
     *
     *  例如:     ${group}/com.demo.DemoService:1.0:20880
     */
    protected static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        return ProtocolUtils.serviceKey(port, serviceName, serviceVersion, serviceGroup);
    }

    /**
     * 释放协议
     */
    @Override
    public void destroy() {
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                invokers.remove(invoker);
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy reference: " + invoker.getUrl());
                    }

                    // 关闭全部的服务引用
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        for (String key : new ArrayList<String>(exporterMap.keySet())) {
            Exporter<?> exporter = exporterMap.remove(key);
            if (exporter != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Unexport service: " + exporter.getInvoker().getUrl());
                    }

                    // 关闭暴露出去的服务
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    /**
     * 引用远程服务：
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {

        /**
         *  子类实现的方法 {@link org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#protocolBindingRefer(Class, URL)}
         *   【 本地服务 】 {@link org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol#protocolBindingRefer(Class, URL)}
         *  {@link org.apache.dubbo.rpc.support.MockProtocol#protocolBindingRefer(Class, URL)}
         */
        return new AsyncToSyncInvoker<>(protocolBindingRefer(type, url));
    }

    protected abstract <T> Invoker<T> protocolBindingRefer(Class<T> type, URL url) throws RpcException;
}
