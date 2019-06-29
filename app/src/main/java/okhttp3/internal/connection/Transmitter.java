/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.Socket;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.internal.Internal;
import okhttp3.internal.http.ExchangeCodec;
import okhttp3.internal.platform.Platform;
import okio.AsyncTimeout;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.sameConnection;

/***
 * 发射器，更准确的说是用来通知和调度网络请求的，作用在整个网络请求生命周期；
 * OkHttp的应用程序和网络层之间的桥梁。此类公开高级应用程序层：
 * 连接，请求，响应和流；
 * 也是用来计算RealConnection的连接引用，判定是否为空闲连接
 */
public final class Transmitter {
    private final OkHttpClient client;
    private final RealConnectionPool connectionPool;
    private final Call call;
    private final EventListener eventListener;
    private final AsyncTimeout timeout = new AsyncTimeout() {
        @Override
        protected void timedOut() {
            cancel();
        }
    };

    private @Nullable
    Object callStackTrace;

    private Request request;
    private ExchangeFinder exchangeFinder;

    // Guarded by connectionPool.
    public RealConnection connection;
    private @Nullable
    Exchange exchange;
    private boolean exchangeRequestDone;
    private boolean exchangeResponseDone;
    private boolean canceled;
    private boolean timeoutEarlyExit;
    private boolean noMoreExchanges;

    public Transmitter(OkHttpClient client, Call call) {
        // OkHttpClient对象
        this.client = client;
        //连接池对象
        this.connectionPool = Internal.instance.realConnectionPool(client.connectionPool());
        //l回调对象
        this.call = call;
        //回调对象
        this.eventListener = client.eventListenerFactory().create(call);
        //超时控制
        this.timeout.timeout(client.callTimeoutMillis(), MILLISECONDS);
    }

    /**
     * 返回AsyncTimeout对象
     */

    public Timeout timeout() {
        return timeout;
    }

    /**
     * 创建超时控制
     */
    public void timeoutEnter() {
        timeout.enter();
    }

    /**
     * 在调用完成完成之前停止应用超时控制。这用于WebSockets和双工调用，超时只适用于初始设置
     */
    public void timeoutEarlyExit() {
        if (timeoutEarlyExit) throw new IllegalStateException();
        timeoutEarlyExit = true;
        timeout.exit();
    }

    private @Nullable
    IOException timeoutExit(@Nullable IOException cause) {
        if (timeoutEarlyExit) return cause;
        if (!timeout.exit()) return cause;

        InterruptedIOException e = new InterruptedIOException("timeout");
        if (cause != null) e.initCause(cause);

        return e;
    }

    /**
     * 调用eventListener的callStart方法（绑定call）
     */
    public void callStart() {
        this.callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
        eventListener.callStart(call);
    }

    /**
     * 准备创建一个流来承载{@code request}。 如果存在连接，则优先使用现有连接。
     */
    public void prepareToConnect(Request request) {
        if (this.request != null) {
            if (sameConnection(this.request.url(), request.url()) && exchangeFinder.hasRouteToTry()) {
                return; // Already ready.
            }
            if (exchange != null) throw new IllegalStateException();

            if (exchangeFinder != null) {
                maybeReleaseConnection(null, true);
                exchangeFinder = null;
            }
        }

        this.request = request;
        this.exchangeFinder = new ExchangeFinder(this, connectionPool, createAddress(request.url()),
                call, eventListener);
    }

    /**
     * 创建连接地址
     * @param url
     * @return
     */
    private Address createAddress(HttpUrl url) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (url.isHttps()) {
            sslSocketFactory = client.sslSocketFactory();
            hostnameVerifier = client.hostnameVerifier();
            certificatePinner = client.certificatePinner();
        }

        return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
                sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
                client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
    }

    /**
     * 那一个新的request和response包装成一个Exchange
     */
    Exchange newExchange(Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
        synchronized (connectionPool) {
            if (noMoreExchanges) {
                throw new IllegalStateException("released");
            }
            if (exchange != null) {
                throw new IllegalStateException("cannot make a new request because the previous response "
                        + "is still open: please call response.close()");
            }
        }
        //核心代码在这里继续向下看
        ExchangeCodec codec = exchangeFinder.find(client, chain, doExtensiveHealthChecks);
        Exchange result = new Exchange(this, call, eventListener, exchangeFinder, codec);

        synchronized (connectionPool) {
            this.exchange = result;
            this.exchangeRequestDone = false;
            this.exchangeResponseDone = false;
            return result;
        }
    }

    void acquireConnectionNoEvents(RealConnection connection) {
        assert (Thread.holdsLock(connectionPool));

        if (this.connection != null) throw new IllegalStateException();
        this.connection = connection;
        connection.transmitters.add(new TransmitterReference(this, callStackTrace));
    }

    /**
     * 从连接的分配列表中删除发送器。返回调用者应该关闭的socket。
     */
    @Nullable
    Socket releaseConnectionNoEvents() {
        assert (Thread.holdsLock(connectionPool));

        int index = -1;
        for (int i = 0, size = this.connection.transmitters.size(); i < size; i++) {
            Reference<Transmitter> reference = this.connection.transmitters.get(i);
            if (reference.get() == this) {
                index = i;
                break;
            }
        }

        if (index == -1) throw new IllegalStateException();

        RealConnection released = this.connection;
        released.transmitters.remove(index);
        this.connection = null;

        if (released.transmitters.isEmpty()) {
            released.idleAtNanos = System.nanoTime();
            if (connectionPool.connectionBecameIdle(released)) {
                return released.socket();
            }
        }

        return null;
    }

    public void exchangeDoneDueToException() {
        synchronized (connectionPool) {
            if (noMoreExchanges) throw new IllegalStateException();
            exchange = null;
        }
    }

    /***
     * 释放由Exchange请求或响应所持有的资源 它被调用早请求完成或由于exception请求失败是
     * 这时候 e是非空的 当Exchange被取消时 同样提供一个e
     * @param exchange
     * @param requestDone
     * @param responseDone
     * @param e
     * @return
     */
    @Nullable
    IOException exchangeMessageDone(
            Exchange exchange, boolean requestDone, boolean responseDone, @Nullable IOException e) {
        boolean exchangeDone = false;
        synchronized (connectionPool) {
            if (exchange != this.exchange) {
                return e; // This exchange was detached violently!
            }
            boolean changed = false;
            if (requestDone) {
                if (!exchangeRequestDone) changed = true;
                this.exchangeRequestDone = true;
            }
            if (responseDone) {
                if (!exchangeResponseDone) changed = true;
                this.exchangeResponseDone = true;
            }
            if (exchangeRequestDone && exchangeResponseDone && changed) {
                exchangeDone = true;
                this.exchange.connection().successCount++;
                this.exchange = null;
            }
        }
        if (exchangeDone) {
            e = maybeReleaseConnection(e, false);
        }
        return e;
    }

    /**
     * 没有预期的请求了
     *
     * @param e
     * @return
     */
    public @Nullable
    IOException noMoreExchanges(@Nullable IOException e) {
        synchronized (connectionPool) {
            noMoreExchanges = true;
        }
        return maybeReleaseConnection(e, false);
    }

    /***
     * 如果不再需要连接，则释放它。
     * 这将在每次请求完成后调用，它被调用在没有预期的请求信号之后
     * @param e
     * @param force 如果为true  就会强制释放资源
     * @return
     */
    private @Nullable
    IOException maybeReleaseConnection(@Nullable IOException e, boolean force) {
        Socket socket;
        Connection releasedConnection;
        boolean callEnd;
        synchronized (connectionPool) {
            if (force && exchange != null) {
                throw new IllegalStateException("cannot release connection while it is in use");
            }
            releasedConnection = this.connection;
            socket = this.connection != null && exchange == null && (force || noMoreExchanges)
                    ? releaseConnectionNoEvents()
                    : null;
            if (this.connection != null) releasedConnection = null;
            callEnd = noMoreExchanges && exchange == null;
        }
        closeQuietly(socket);

        if (releasedConnection != null) {
            eventListener.connectionReleased(call, releasedConnection);
        }

        if (callEnd) {
            boolean callFailed = (e != null);
            e = timeoutExit(e);
            if (callFailed) {
                eventListener.callFailed(call, e);
            } else {
                eventListener.callEnd(call);
            }
        }
        return e;
    }

    /**
     * 是否可以重试
     *
     * @return
     */
    public boolean canRetry() {
        return exchangeFinder.hasStreamFailure() && exchangeFinder.hasRouteToTry();
    }

    /**
     * 是否含有正在执行的交换
     *
     * @return
     */
    public boolean hasExchange() {
        synchronized (connectionPool) {
            return exchange != null;
        }
    }

    /**
     * 如果当前持有套接字连接，则立即关闭它。
     * 使用此命令可以中断来自任何线程的正在运行的请求。
     * 调用者有责任关闭请求体和响应体流;否则，资源可能会泄漏。
     * 此方法可以安全地并发调用，但提供了有限的保证。
     * 如果已建立传输层连接(如HTTP/2流)，则终止该连接。
     * 否则，如果正在建立套接字连接，则终止该连接。
     */
    public void cancel() {
        Exchange exchangeToCancel;
        RealConnection connectionToCancel;
        synchronized (connectionPool) {
            canceled = true;
            exchangeToCancel = exchange;
            connectionToCancel = exchangeFinder != null && exchangeFinder.connectingConnection() != null
                    ? exchangeFinder.connectingConnection()
                    : connection;
        }
        if (exchangeToCancel != null) {
            exchangeToCancel.cancel();
        } else if (connectionToCancel != null) {
            connectionToCancel.cancel();
        }
    }

    /**
     * 是否可以中断请求
     *
     * @return
     */
    public boolean isCanceled() {
        synchronized (connectionPool) {
            return canceled;
        }
    }

    static final class TransmitterReference extends WeakReference<Transmitter> {
        /**
         * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
         * identifying the origin of connection leaks.
         */
        final Object callStackTrace;

        TransmitterReference(Transmitter referent, Object callStackTrace) {
            super(referent);
            this.callStackTrace = callStackTrace;
        }
    }
}
