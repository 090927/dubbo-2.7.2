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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    private final ExchangeClient[] clients;

    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    private final String version;

    private final ReentrantLock destroyLock = new ReentrantLock();

    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{INTERFACE_KEY, GROUP_KEY, TOKEN_KEY, TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(VERSION_KEY, "0.0.0");
        this.invokers = invokers;
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {

        // 设置附加属性
        RpcInvocation inv = (RpcInvocation) invocation;

        // 此次调用的方法名称
        final String methodName = RpcUtils.getMethodName(invocation);

        // 向Invocation中添加附加信息，这里将URL的path和version添加到附加信息中
        inv.setAttachment(PATH_KEY, getUrl().getPath());
        inv.setAttachment(VERSION_KEY, version);

        // 设置远程调用 Client
        ExchangeClient currentClient;
        if (clients.length == 1) {

            // 从 clients 数组中获取 ExchangeClient
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        try {

            // oneway 就是不需要响应结果的请求。(true: 表示 “单向” 通信 )
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);

            // // 根据调用的方法名称和配置计算此次调用的超时时间
            int timeout = getUrl().getMethodParameter(methodName, TIMEOUT_KEY, DEFAULT_TIMEOUT);
            if (isOneway) {

                // 不需要关注返回值的请求
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);

                /**
                 * 异步发送, 不关注返回值 {@link ReferenceCountExchangeClient#send(Object, boolean)}
                 */
                currentClient.send(inv, isSent);
                RpcContext.getContext().setFuture(null);
                return AsyncRpcResult.newDefaultAsyncResult(invocation);
            } else {
                /*
                 * 需要关注返回值的请求
                 */

                // 异步 RpcResult
                AsyncRpcResult asyncRpcResult = new AsyncRpcResult(inv);

                /**
                 * 异步调用 `关注返回值` {@link ReferenceCountExchangeClient#request(Object, int)}
                 *
                 *  【 core 】会相应地创建 DefaultFuture 对象以及检测超时的定时任务 {@link org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeChannel#request(Object, int)}
                 */
                CompletableFuture<Object> responseFuture = currentClient.request(inv, timeout);
                responseFuture.whenComplete((obj, t) -> {
                    if (t != null) {
                        asyncRpcResult.completeExceptionally(t);
                    } else {
                        asyncRpcResult.complete((AppResponse) obj);
                    }
                });

                /**
                 * 为兼容，返回适配器对象 {@link FutureAdapter#FutureAdapter(CompletableFuture)}
                 */
                RpcContext.getContext().setFuture(new FutureAdapter(asyncRpcResult));
                return asyncRpcResult;
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                super.destroy();
                if (invokers != null) {
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {

                        /**
                         *  关闭链接 {@link org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeClient#close(int)}
                         */
                        client.close(ConfigurationUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                destroyLock.unlock();
            }
        }
    }
}
