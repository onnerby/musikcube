package io.casey.musikcube.remote.websocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketExtension;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.casey.musikcube.remote.util.NetworkUtil;
import io.casey.musikcube.remote.util.Preconditions;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.annotations.NonNull;

import static android.content.Context.CONNECTIVITY_SERVICE;

public class WebSocketService {
    private static final String TAG = "WebSocketService";

    private static final int AUTO_RECONNECT_INTERVAL_MILLIS = 2000;
    private static final int CALLBACK_TIMEOUT_MILLIS = 30000;
    private static final int CONNECTION_TIMEOUT_MILLIS = 5000;
    private static final int PING_INTERVAL_MILLIS = 3500;
    private static final int AUTO_CONNECT_FAILSAFE_DELAY_MILLIS = 2000;
    private static final int AUTO_DISCONNECT_DELAY_MILLIS = 10000;
    private static final int FLAG_AUTHENTICATION_FAILED = 0xbeef;
    private static final int WEBSOCKET_FLAG_POLICY_VIOLATION = 1008;

    private static final int MESSAGE_BASE = 0xcafedead;
    private static final int MESSAGE_CONNECT_THREAD_FINISHED = MESSAGE_BASE + 0;
    private static final int MESSAGE_RECEIVED = MESSAGE_BASE + 1;
    private static final int MESSAGE_REMOVE_OLD_CALLBACKS = MESSAGE_BASE + 2;
    private static final int MESSAGE_AUTO_RECONNECT = MESSAGE_BASE + 3;
    private static final int MESSAGE_SCHEDULE_PING = MESSAGE_BASE + 4;
    private static final int MESSAGE_PING_EXPIRED = MESSAGE_BASE + 5;

    public interface Client {
        void onStateChanged(State newState, State oldState);
        void onMessageReceived(SocketMessage message);
        void onInvalidPassword();
    }

    public interface MessageResultCallback {
        void onMessageResult(final SocketMessage response);
    }

    private interface MessageErrorCallback {
        void onMessageError();
    }

    private interface Predicate1<T> {
        boolean check(T value);
    }

    public interface Interceptor {
        boolean process(SocketMessage message, Responder responder);
    }

    public interface Responder {
        void respond(SocketMessage response);
    }

    public enum State {
        Connecting,
        Connected,
        Disconnected
    }

    private Handler handler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == MESSAGE_CONNECT_THREAD_FINISHED) {
                if (message.obj == null) {
                    boolean invalidPassword = (message.arg1 == FLAG_AUTHENTICATION_FAILED);
                    disconnect(!invalidPassword); /* auto reconnect as long as password was not invalid */

                    if (invalidPassword) {
                        for (Client client : clients) {
                            client.onInvalidPassword();
                        }
                    }
                }
                else {
                    setSocket((WebSocket) message.obj);
                    setState(State.Connected);
                    ping();
                }
                return true;
            }
            else if (message.what == MESSAGE_RECEIVED) {
                if (clients != null) {
                    final SocketMessage msg = (SocketMessage) message.obj;

                    boolean dispatched = false;

                    /* registered callback for THIS message */
                    final MessageResultDescriptor mdr = messageCallbacks.remove(msg.getId());
                    if (mdr != null && mdr.callback != null) {
                        mdr.callback.onMessageResult(msg);
                        dispatched = true;
                    }

                    if (!dispatched) {
                        /* service-level callback */
                        for (Client client : clients) {
                            client.onMessageReceived(msg);
                        }
                    }

                    if (mdr != null) {
                        mdr.error = null;
                    }
                }
                return true;
            }
            else if (message.what == MESSAGE_REMOVE_OLD_CALLBACKS) {
                removeExpiredCallbacks();
                handler.sendEmptyMessageDelayed(MESSAGE_REMOVE_OLD_CALLBACKS, CALLBACK_TIMEOUT_MILLIS);
            }
            else if (message.what == MESSAGE_AUTO_RECONNECT) {
                if (getState() == State.Disconnected && autoReconnect) {
                    reconnect();
                }
            }
            else if (message.what == MESSAGE_SCHEDULE_PING) {
                ping();
            }
            else if (message.what == MESSAGE_PING_EXPIRED) {
                // Toast.makeText(context, "recovering...", Toast.LENGTH_LONG).show();
                removeInternalCallbacks();
                boolean reconnect = (getState() == State.Connected) || autoReconnect;
                disconnect(reconnect);
            }

            return false;
        }
    });

    private static class MessageResultDescriptor {
        long id;
        long enqueueTime;
        boolean intercepted;
        Client client;
        MessageResultCallback callback;
        MessageErrorCallback error;
    }

    private static WebSocketService INSTANCE;
    private static AtomicLong NEXT_ID = new AtomicLong(0);

    private Context context;
    private SharedPreferences prefs;
    private WebSocket socket = null;
    private State state = State.Disconnected;
    private Set<Client> clients = new HashSet<>();
    private Map<String, MessageResultDescriptor> messageCallbacks = new HashMap<>();
    private boolean autoReconnect = false;
    private NetworkChangedReceiver networkChanged = new NetworkChangedReceiver();
    private ConnectThread thread;
    private Set<Interceptor> interceptors = new HashSet<>();

    public static synchronized WebSocketService getInstance(final Context context) {
        if (INSTANCE == null) {
            INSTANCE = new WebSocketService(context);
        }

        return INSTANCE;
    }

    private WebSocketService(final Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        handler.sendEmptyMessageDelayed(MESSAGE_REMOVE_OLD_CALLBACKS, CALLBACK_TIMEOUT_MILLIS);
    }

    public void addInterceptor(final Interceptor interceptor) {
        Preconditions.throwIfNotOnMainThread();
        interceptors.add(interceptor);
    }

    public void removeInterceptor(final Interceptor interceptor) {
        Preconditions.throwIfNotOnMainThread();
        interceptors.remove(interceptor);
    }

    public void addClient(Client client) {
        Preconditions.throwIfNotOnMainThread();

        if (!this.clients.contains(client)) {
            this.clients.add(client);

            if (this.clients.size() >= 0 && state == State.Disconnected) {
                registerReceiverAndScheduleFailsafe();
                reconnect();
            }

            handler.removeCallbacks(autoDisconnectRunnable);
            client.onStateChanged(getState(), getState());
        }
    }

    public void removeClient(Client client) {
        Preconditions.throwIfNotOnMainThread();

        if (this.clients.remove(client)) {
            removeCallbacksForClient(client);

            if (this.clients.size() == 0) {
                unregisterReceiverAndCancelFailsafe();
                handler.postDelayed(autoDisconnectRunnable, AUTO_DISCONNECT_DELAY_MILLIS);
            }
        }
    }

    public boolean hasClient(Client client) {
        Preconditions.throwIfNotOnMainThread();
        return this.clients.contains(client);
    }

    public void reconnect() {
        Preconditions.throwIfNotOnMainThread();
        autoReconnect = true;
        connectIfNotConnected();
    }

    public void disconnect() {
        disconnect(false); /* don't auto-reconnect */
    }

    public State getState() {
        return state;
    }

    public void cancelMessage(final long id) {
        Preconditions.throwIfNotOnMainThread();
        removeCallbacks((MessageResultDescriptor mrd) -> mrd.id == id);
    }

    private void ping() {
        if (state == State.Connected) {
            //Log.i("WebSocketService", "ping");
            removeInternalCallbacks();
            handler.removeMessages(MESSAGE_PING_EXPIRED);
            handler.sendEmptyMessageDelayed(MESSAGE_PING_EXPIRED, PING_INTERVAL_MILLIS);

            final SocketMessage ping = SocketMessage.Builder
                .request(Messages.Request.Ping).build();

            send(ping, INTERNAL_CLIENT, (SocketMessage response) -> {
                //Log.i("WebSocketService", "pong");
                handler.removeMessages(MESSAGE_PING_EXPIRED);
                handler.sendEmptyMessageDelayed(MESSAGE_SCHEDULE_PING, PING_INTERVAL_MILLIS);
            });
        }
    }

    public void cancelMessages(final Client client) {
        Preconditions.throwIfNotOnMainThread();
        removeCallbacks((MessageResultDescriptor mrd) -> mrd.client == client);
    }

    public long send(final SocketMessage message) {
        return send(message, null, null);
    }

    public long send(final SocketMessage message, Client client, MessageResultCallback callback) {
        Preconditions.throwIfNotOnMainThread();

        boolean intercepted = false;

        for (final Interceptor i : interceptors) {
            if (i.process(message, responder)) {
                intercepted = true;
            }
        }

        if (!intercepted) {
            /* it seems that sometimes the socket dies, but the onDisconnected() event is not
            raised. unclear if this is our bug or a bug in the library. disconnect and trigger
            a reconnect until we can find a better root cause. this is very difficult to repro */
            if (this.socket != null && !this.socket.isOpen()) {
                this.disconnect(true);
                return -1;
            }
            else if (this.socket == null) {
                return -1;
            }
        }

        final long id = NEXT_ID.incrementAndGet();

        if (callback != null) {
            if (!clients.contains(client) && client != INTERNAL_CLIENT) {
                throw new IllegalArgumentException("client is not registered");
            }

            final MessageResultDescriptor mrd = new MessageResultDescriptor();
            mrd.id = id;
            mrd.enqueueTime = System.currentTimeMillis();
            mrd.client = client;
            mrd.callback = callback;
            mrd.intercepted = intercepted;
            messageCallbacks.put(message.getId(), mrd);
        }

        if (!intercepted) {
            this.socket.sendText(message.toString());
        }
        else {
            Log.d(TAG, "send: message intercepted with id " + String.valueOf(id));
        }

        return id;
    }

    public Observable<SocketMessage> send(final SocketMessage message, Client client) {
        return Observable.create(new ObservableOnSubscribe<SocketMessage>() {
            @Override
            public void subscribe(@NonNull ObservableEmitter<SocketMessage> emitter) throws Exception {
                try {
                    Preconditions.throwIfNotOnMainThread();

                    boolean intercepted = false;

                    for (final Interceptor i : interceptors) {
                        if (i.process(message, responder)) {
                            intercepted = true;
                        }
                    }

                    if (!intercepted) {
                        /* it seems that sometimes the socket dies, but the onDisconnected() event is not
                        raised. unclear if this is our bug or a bug in the library. disconnect and trigger
                        a reconnect until we can find a better root cause. this is very difficult to repro */
                        if (socket != null && !socket.isOpen()) {
                            disconnect(true);
                            throw new Exception("socket disconnected");
                        }
                        else if (socket == null) {
                            throw new Exception("socket not connected");
                        }
                    }

                    if (!clients.contains(client) && client != INTERNAL_CLIENT) {
                        throw new IllegalArgumentException("client is not registered");
                    }

                    final MessageResultDescriptor mrd = new MessageResultDescriptor();
                    mrd.id = NEXT_ID.incrementAndGet();
                    mrd.enqueueTime = System.currentTimeMillis();
                    mrd.client = client;
                    mrd.intercepted = intercepted;

                    mrd.callback = (SocketMessage message) -> {
                        emitter.onNext(message);
                        emitter.onComplete();
                    };

                    mrd.error = () -> {
                        final Exception ex = new Exception();
                        ex.fillInStackTrace();
                        emitter.onError(ex);
                    };

                    messageCallbacks.put(message.getId(), mrd);

                    if (!intercepted) {
                        socket.sendText(message.toString());
                    }
                }
                catch (Exception ex) {
                    emitter.onError(ex);
                }
            }
        });
    }

    public boolean hasValidConnection() {
        final String addr = prefs.getString(Prefs.Key.ADDRESS, "");
        final int port = prefs.getInt(Prefs.Key.MAIN_PORT, -1);
        return (addr.length() > 0 && port >= 0);
    }

    private void disconnect(boolean autoReconnect) {
        Preconditions.throwIfNotOnMainThread();

        synchronized (this) {
            if (this.thread != null) {
                this.thread.interrupt();
                this.thread = null;
            }
        }

        this.autoReconnect = autoReconnect;

        if (this.socket != null) {
            this.socket.removeListener(webSocketAdapter);
            this.socket.disconnect();
            this.socket = null;
        }

        removeNonInterceptedCallbacks();
        setState(State.Disconnected);

        if (autoReconnect) {
            this.handler.sendEmptyMessageDelayed(
                MESSAGE_AUTO_RECONNECT,
                AUTO_RECONNECT_INTERVAL_MILLIS);
        }
        else {
            this.handler.removeMessages(MESSAGE_AUTO_RECONNECT);
        }
    }

    private void removeNonInterceptedCallbacks() {
        removeCallbacks((mrd) -> !mrd.intercepted);
    }

    private void removeInternalCallbacks() {
        removeCallbacks((MessageResultDescriptor mrd) -> mrd.client == INTERNAL_CLIENT);
    }

    private void removeExpiredCallbacks() {
        final long now = System.currentTimeMillis();

        removeCallbacks((MessageResultDescriptor value) ->
            now - value.enqueueTime > CALLBACK_TIMEOUT_MILLIS);
    }

    private void removeCallbacksForClient(final Client client) {
        removeCallbacks((MessageResultDescriptor value) -> value == client);
    }

    private void removeCallbacks(Predicate1<MessageResultDescriptor> predicate) {
        final Iterator<Map.Entry<String, MessageResultDescriptor>> it
                = messageCallbacks.entrySet().iterator();

        while (it.hasNext()) {
            final Map.Entry<String, MessageResultDescriptor> entry = it.next();
            final MessageResultDescriptor mdr = entry.getValue();
            if (predicate.check(mdr)) {
                if (mdr.error != null) {
                    mdr.error.onMessageError();
                }

                it.remove();
            }
        }
    }

    private void connectIfNotConnected() {
        if (state == State.Disconnected) {
            disconnect(autoReconnect);
            handler.removeMessages(MESSAGE_AUTO_RECONNECT);

            if (this.clients.size() > 0) {
                handler.removeCallbacks(autoDisconnectRunnable);
                setState(State.Connecting);
                thread = new ConnectThread();
                thread.start();
            }
        }
    }

    private void setSocket(WebSocket socket) {
        if (this.socket != socket) {
            if (this.socket != null) {
                this.socket.removeListener(webSocketAdapter);
            }

            this.socket = socket;
        }
    }

    private void setState(State state) {
        Preconditions.throwIfNotOnMainThread();

        Log.d(TAG, "state = " + state);

        if (this.state != state) {
            State old = this.state;
            this.state = state;

            for (Client client : this.clients) {
                client.onStateChanged(state, old);
            }
        }
    }

    private void registerReceiverAndScheduleFailsafe() {
        unregisterReceiverAndCancelFailsafe();

        /* generally raises a CONNECTIVITY_ACTION event immediately,
        even if already connected. */
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkChanged, filter);

        /* however, CONNECTIVITY_ACTION doesn't ALWAYS seem to be raised,
        so we schedule a failsafe just in case */
        this.handler.postDelayed(autoReconnectFailsafeRunnable, AUTO_CONNECT_FAILSAFE_DELAY_MILLIS);
    }

    private void unregisterReceiverAndCancelFailsafe() {
        handler.removeCallbacks(autoReconnectFailsafeRunnable);

        try {
            context.unregisterReceiver(networkChanged);
        }
        catch (Exception ex) {
            /* om nom nom */
        }
    }

    private Runnable autoReconnectFailsafeRunnable = () -> {
        if (autoReconnect && getState() == State.Disconnected) {
            reconnect();
        }
    };

    private Runnable autoDisconnectRunnable = () -> disconnect();

    private Responder responder = (response) -> {
        /* post to the back of the queue in case the interceptor responded immediately;
        we need to ensure all of the request book-keeping has been finished. */
        handler.post(() -> {
            handler.sendMessage(Message.obtain(handler, MESSAGE_RECEIVED, response));
        });
    };

    private WebSocketAdapter webSocketAdapter = new WebSocketAdapter() {
        @Override
        public void onTextMessage(WebSocket websocket, String text) throws Exception {
            final SocketMessage message = SocketMessage.create(text);
            if (message != null) {
                if (message.getName().equals(Messages.Request.Authenticate.toString())) {
                    handler.sendMessage(Message.obtain(
                        handler, MESSAGE_CONNECT_THREAD_FINISHED, websocket));
                }
                else {
                    handler.sendMessage(Message.obtain(handler, MESSAGE_RECEIVED, message));
                }
            }
        }

        @Override
        public void onDisconnected(WebSocket websocket,
                                   WebSocketFrame serverCloseFrame,
                                   WebSocketFrame clientCloseFrame,
                                   boolean closedByServer) throws Exception {
            int flags = 0;
            if (serverCloseFrame.getCloseCode() == WEBSOCKET_FLAG_POLICY_VIOLATION) {
                flags = FLAG_AUTHENTICATION_FAILED;
            }

            handler.sendMessage(Message.obtain(handler, MESSAGE_CONNECT_THREAD_FINISHED, flags, 0, null));
        }
    };

    private class ConnectThread extends Thread {
        @Override
        public void run() {
            WebSocket socket;

            try {
                final WebSocketFactory factory = new WebSocketFactory();

                if (prefs.getBoolean(Prefs.Key.CERT_VALIDATION_DISABLED, Prefs.Default.CERT_VALIDATION_DISABLED)) {
                    NetworkUtil.disableCertificateValidation(factory);
                }

                final String protocol = prefs.getBoolean(
                    Prefs.Key.SSL_ENABLED, Prefs.Default.SSL_ENABLED) ? "wss" : "ws";

                final String host = String.format(
                    Locale.ENGLISH,
                    "%s://%s:%d",
                    protocol,
                    prefs.getString(Prefs.Key.ADDRESS, Prefs.Default.ADDRESS),
                    prefs.getInt(Prefs.Key.MAIN_PORT, Prefs.Default.MAIN_PORT));

                socket = factory.createSocket(host, CONNECTION_TIMEOUT_MILLIS);
                socket.addListener(webSocketAdapter);

                if (prefs.getBoolean(Prefs.Key.MESSAGE_COMPRESSION_ENABLED, Prefs.Default.MESSAGE_COMPRESSION_ENABLED)) {
                    socket.addExtension(WebSocketExtension.PERMESSAGE_DEFLATE);
                }

                socket.connect();
                socket.setPingInterval(PING_INTERVAL_MILLIS);

                /* authenticate */
                final String auth = SocketMessage.Builder
                    .request(Messages.Request.Authenticate)
                    .addOption("password", prefs.getString(Prefs.Key.PASSWORD, Prefs.Default.PASSWORD))
                    .build()
                    .toString();

                socket.sendText(auth);
            }
            catch (Exception ex) {
                socket = null;
            }

            synchronized (WebSocketService.this) {
                if (thread == this && socket == null) {
                    handler.sendMessage(Message.obtain(
                        handler, MESSAGE_CONNECT_THREAD_FINISHED, null));
                }

                if (thread == this) {
                    thread = null;
                }
            }
        }
    }

    private class NetworkChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(CONNECTIVITY_SERVICE);

            final NetworkInfo info = cm.getActiveNetworkInfo();

            if (info != null && info.isConnected()) {
                if (autoReconnect) {
                    connectIfNotConnected();
                }
            }
        }
    }

    private static Client INTERNAL_CLIENT = new Client() {
        public void onStateChanged(State newState, State oldState) { }
        public void onMessageReceived(SocketMessage message) { }
        public void onInvalidPassword() { }
    };
}