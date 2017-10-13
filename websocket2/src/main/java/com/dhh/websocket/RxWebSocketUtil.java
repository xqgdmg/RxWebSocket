package com.dhh.websocket;

import android.os.SystemClock;
import android.support.v4.util.ArrayMap;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Action;
import io.reactivex.functions.Cancellable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;


/**
 * Created by dhh on 2017/9/21.
 * <p>
 * WebSocketUtil based on okhttp and RxJava
 * </p>
 * Core Feature : WebSocket will be auto reconnection onFailed.
 */
public class RxWebSocketUtil {
    private static RxWebSocketUtil instance;

    private OkHttpClient client;

    private Map<String, Observable<WebSocketInfo>> observableMap;
    private Map<String, WebSocket> webSocketMap;
    private boolean showLog;

    private RxWebSocketUtil() {
        try {
            Class.forName("okhttp3.OkHttpClient");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Must be dependency okhttp3 !");
        }
        try {
            Class.forName("io.reactivex.Observable");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Must be dependency rxjava 2.x");
        }
        try {
            Class.forName("io.reactivex.android.schedulers.AndroidSchedulers");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Must be dependency rxandroid 2.x");
        }
        observableMap = new ArrayMap<>();
        webSocketMap = new ArrayMap<>();
        client = new OkHttpClient();
    }

    public static RxWebSocketUtil getInstance() {
        if (instance == null) {
            synchronized (RxWebSocketUtil.class) {
                if (instance == null) {
                    instance = new RxWebSocketUtil();
                }
            }
        }
        return instance;
    }

    /**
     * set your client
     *
     * @param client
     */
    public void setClient(OkHttpClient client) {
        if (client == null) {
            throw new NullPointerException(" Are you kidding me ? client == null");
        }
        this.client = client;
    }

    public void setShowLog(boolean showLog) {
        this.showLog = showLog;
    }

    /**
     * @param url      ws://127.0.0.1:8080/websocket
     * @param timeout  The WebSocket will be reconnected after the specified time interval is not "onMessage",
     *                 <p>
     *                 在指定时间间隔后没有收到消息就会重连WebSocket,为了适配小米平板,因为小米平板断网后,不会发送错误通知
     * @param timeUnit unit
     * @return
     */
    public Observable<WebSocketInfo> getWebSocketInfo(final String url, final long timeout, final TimeUnit timeUnit) {
        Observable<WebSocketInfo> observable = observableMap.get(url);
        if (observable == null) {
            observable = Observable.create(new WebSocketOnSubscribe(url))
                    //自动重连
                    .timeout(timeout, timeUnit)
                    .retry()
                    //共享
                    .doOnDispose(new Action() {
                        @Override
                        public void run() throws Exception {
                            observableMap.remove(url);
                            webSocketMap.remove(url);
                            if (showLog) {
                                Log.d("RxWebSocketUtil", "注销");
                            }
                        }
                    })
                    .doOnNext(new Consumer<WebSocketInfo>() {
                        @Override
                        public void accept(WebSocketInfo webSocketInfo) throws Exception {
                            if (webSocketInfo.isOnOpen()) {
                                webSocketMap.put(url, webSocketInfo.getWebSocket());
                            }
                        }
                    })
                    .share()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread());
            observableMap.put(url, observable);
        } else {
            observable = Observable.merge(Observable.just(new WebSocketInfo(webSocketMap.get(url), true)), observable);
        }
        return observable;
    }

    /**
     * default timeout: 30 days
     * <p>
     * 若忽略小米平板,请调用这个方法
     * </p>
     */
    public Observable<WebSocketInfo> getWebSocketInfo(String url) {
        return getWebSocketInfo(url, 30, TimeUnit.DAYS);
    }

    public Observable<String> getWebSocketString(String url) {
        return getWebSocketInfo(url)
                .filter(new Predicate<WebSocketInfo>() {
                    @Override
                    public boolean test(@NonNull WebSocketInfo webSocketInfo) throws Exception {
                        return webSocketInfo.getString() != null;
                    }
                })
                .map(new Function<WebSocketInfo, String>() {
                    @Override
                    public String apply(@NonNull WebSocketInfo webSocketInfo) throws Exception {
                        return webSocketInfo.getString();
                    }
                });
    }

    public Observable<ByteString> getWebSocketByteString(String url) {
        return getWebSocketInfo(url)
                .filter(new Predicate<WebSocketInfo>() {
                    @Override
                    public boolean test(@NonNull WebSocketInfo webSocketInfo) throws Exception {
                        return webSocketInfo.getByteString() != null;
                    }
                })
                .map(new Function<WebSocketInfo, ByteString>() {
                    @Override
                    public ByteString apply(WebSocketInfo webSocketInfo) throws Exception {
                        return webSocketInfo.getByteString();
                    }
                });
    }

    public Observable<WebSocket> getWebSocket(String url) {
        return getWebSocketInfo(url)
                .map(new Function<WebSocketInfo, WebSocket>() {
                    @Override
                    public WebSocket apply(@NonNull WebSocketInfo webSocketInfo) throws Exception {
                        return webSocketInfo.getWebSocket();
                    }
                });
    }

    /**
     * 如果url的WebSocket已经打开,可以直接调用这个发送消息.
     *
     * @param url
     * @param msg
     */
    public void send(String url, String msg) {
        WebSocket webSocket = webSocketMap.get(url);
        if (webSocket != null) {
            webSocket.send(msg);
        } else {
            throw new IllegalStateException("The WebSokcet not open");
        }
    }

    /**
     * 如果url的WebSocket已经打开,可以直接调用这个发送消息.
     *
     * @param url
     * @param byteString
     */
    public void send(String url, ByteString byteString) {
        WebSocket webSocket = webSocketMap.get(url);
        if (webSocket != null) {
            webSocket.send(byteString);
        } else {
            throw new IllegalStateException("The WebSokcet not open");
        }
    }

    /**
     * 不用关心url 的WebSocket是否打开,可以直接发送
     *
     * @param url
     * @param msg
     */
    public void asyncSend(String url, final String msg) {
        getWebSocket(url)
                .take(1)
                .subscribe(new Consumer<WebSocket>() {
                    @Override
                    public void accept(WebSocket webSocket) throws Exception {
                        webSocket.send(msg);
                    }
                });

    }

    /**
     * 不用关心url 的WebSocket是否打开,可以直接发送
     *
     * @param url
     * @param byteString
     */
    public void asyncSend(String url, final ByteString byteString) {
        getWebSocket(url)
                .take(1)
                .subscribe(new Consumer<WebSocket>() {
                    @Override
                    public void accept(WebSocket webSocket) throws Exception {
                        webSocket.send(byteString);
                    }
                });
    }

    private Request getRequest(String url) {
        return new Request.Builder().get().url(url).build();
    }

    private final class WebSocketOnSubscribe implements ObservableOnSubscribe<WebSocketInfo> {
        private String url;

        private WebSocket webSocket;

        private WebSocketInfo startInfo, stringInfo, byteStringInfo;

        public WebSocketOnSubscribe(String url) {
            this.url = url;
            startInfo = new WebSocketInfo(true);
            stringInfo = new WebSocketInfo();
            byteStringInfo = new WebSocketInfo();
        }

        @Override
        public void subscribe(@NonNull ObservableEmitter<WebSocketInfo> e) throws Exception {
            if (webSocket != null) {
                //降低重连频率
                if (!"main".equals(Thread.currentThread().getName())) {
                    SystemClock.sleep(2000);
                }
            }
            initWebSocket(e);
        }

        private void initWebSocket(final ObservableEmitter<WebSocketInfo> emitter) {
            webSocket = client.newWebSocket(getRequest(url), new WebSocketListener() {
                @Override
                public void onOpen(final WebSocket webSocket, Response response) {
                    if (showLog) {
                        Log.d("RxWebSocketUtil", url + " --> onOpen");
                    }
                    webSocketMap.put(url, webSocket);
                    AndroidSchedulers.mainThread().createWorker().schedule(new Runnable() {
                        @Override
                        public void run() {
                            if (!emitter.isDisposed()) {
                                startInfo.setWebSocket(webSocket);
                                emitter.onNext(startInfo);
                            }
                        }
                    });
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    if (!emitter.isDisposed()) {
                        stringInfo.setWebSocket(webSocket);
                        stringInfo.setString(text);
                        emitter.onNext(stringInfo);
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    if (!emitter.isDisposed()) {
                        byteStringInfo.setWebSocket(webSocket);
                        byteStringInfo.setByteString(bytes);
                        emitter.onNext(byteStringInfo);
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    if (showLog) {
                        Log.e("RxWebSocketUtil", t.toString() + webSocket.request().url().uri().getPath());
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onError(t);
                    }
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(1000, null);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    if (showLog) {
                        Log.d("RxWebSocketUtil", url + " --> onClosed:code= " + code);
                    }
                }
            });
            emitter.setCancellable(new Cancellable() {
                @Override
                public void cancel() throws Exception {
                    webSocket.close(3000, "手动关闭");
                }
            });
        }
    }
}