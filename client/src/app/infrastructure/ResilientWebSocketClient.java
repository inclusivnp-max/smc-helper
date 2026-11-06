package app.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ResilientWebSocketClient implements AutoCloseable {
    public enum State {
        STOPPED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED
    }

    public interface Listener {
        default void onState(State state, String reason) {}
        default void onOpen() {}
        default void onText(String message) {}
        default void onClose(int statusCode, String reason) {}
        default void onError(Throwable error) {}
    }

    private final URI uri;
    private final String name;
    private final HttpClient httpClient;
    private final ReconnectPolicy policy;
    private final Listener listener;
    private final ScheduledExecutorService executor;
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    private volatile WebSocket webSocket;
    private volatile State state = State.STOPPED;
    private volatile boolean running;
    private volatile long connectedAt;
    private volatile long lastMessageAt;
    private volatile ScheduledFuture<?> watchdogTask;

    public ResilientWebSocketClient(String name,
                                    URI uri,
                                    ReconnectPolicy policy,
                                    Listener listener) {
        this.name = name == null || name.isBlank() ? "ws" : name;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.policy = policy == null ? ReconnectPolicy.binanceDefault() : policy;
        this.listener = listener == null ? new Listener() {} : listener;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SMC-WS-" + this.name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        reconnectAttempts.set(0);
        scheduleWatchdog();
        scheduleConnect(0L, "start");
    }

    public synchronized void stop() {
        running = false;
        reconnectScheduled.set(false);
        ScheduledFuture<?> task = watchdogTask;
        if (task != null) task.cancel(true);
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stop");
            } catch (Exception ignored) {
                ws.abort();
            }
        }
        setState(State.STOPPED, "stopped");
    }

    public State state() {
        return state;
    }

    public String status() {
        long now = System.currentTimeMillis();
        long age = connectedAt <= 0L ? 0L : now - connectedAt;
        long silence = lastMessageAt <= 0L ? 0L : now - lastMessageAt;
        return name + " " + state + " · attempts=" + reconnectAttempts.get()
                + " · ageMs=" + age + " · silenceMs=" + silence;
    }

    public void sendText(String text) {
        WebSocket ws = webSocket;
        if (ws != null && state == State.CONNECTED) {
            ws.sendText(text == null ? "" : text, true);
        }
    }

    public void reconnectNow(String reason) {
        reconnect(reason == null || reason.isBlank() ? "manual reconnect" : reason);
    }

    private void connect() {
        if (!running) return;
        reconnectScheduled.set(false);
        setState(State.CONNECTING, "connecting");
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(uri, new WsListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        notifyError(error);
                        scheduleReconnect("connect failed: " + error.getMessage());
                    }
                });
    }

    private void scheduleConnect(long delayMs, String reason) {
        if (!running) return;
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        executor.schedule(this::connect, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        setState(delayMs <= 0L ? State.CONNECTING : State.RECONNECTING, reason);
    }

    private void scheduleReconnect(String reason) {
        if (!running) return;
        int attempt = reconnectAttempts.getAndIncrement();
        scheduleConnect(policy.delayMillis(attempt), reason);
    }

    private void reconnect(String reason) {
        if (!running) return;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) ws.abort();
        scheduleReconnect(reason);
    }

    private void scheduleWatchdog() {
        ScheduledFuture<?> current = watchdogTask;
        if (current != null) current.cancel(true);
        long intervalMs = Math.max(1_000L, policy.heartbeatInterval.toMillis());
        watchdogTask = executor.scheduleWithFixedDelay(this::watchdog, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void watchdog() {
        if (!running || state != State.CONNECTED) return;
        long now = System.currentTimeMillis();
        if (connectedAt > 0L && now - connectedAt >= policy.forcedReconnectAfter.toMillis()) {
            reconnect("scheduled stream refresh before 24h disconnect");
            return;
        }
        if (lastMessageAt > 0L && now - lastMessageAt >= policy.staleTimeout.toMillis()) {
            reconnect("stale websocket stream");
            return;
        }
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendPing(ByteBuffer.wrap(new byte[]{1}));
            } catch (Exception ex) {
                notifyError(ex);
                reconnect("ping failed: " + ex.getMessage());
            }
        }
    }

    private void setState(State next, String reason) {
        state = next;
        try {
            listener.onState(next, reason == null ? "" : reason);
        } catch (Exception ex) {
            notifyError(ex);
        }
    }

    private void notifyError(Throwable error) {
        try {
            listener.onError(error);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }

    private class WsListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            if (!running) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stopped");
                } catch (Exception ignored) {
                    ws.abort();
                }
                return;
            }
            webSocket = ws;
            connectedAt = System.currentTimeMillis();
            lastMessageAt = connectedAt;
            reconnectAttempts.set(0);
            reconnectScheduled.set(false);
            setState(State.CONNECTED, "connected");
            try {
                listener.onOpen();
            } catch (Exception ex) {
                notifyError(ex);
            }
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (!running || ws != webSocket) return null;
            lastMessageAt = System.currentTimeMillis();
            textBuffer.append(data);
            if (last) {
                try {
                    listener.onText(textBuffer.toString());
                } catch (Exception ex) {
                    notifyError(ex);
                } finally {
                    textBuffer.setLength(0);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            if (!running || ws != webSocket) return null;
            lastMessageAt = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            if (!running || ws != webSocket) return null;
            lastMessageAt = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            if (!running || ws != webSocket) return null;
            lastMessageAt = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            try {
                listener.onClose(statusCode, reason);
            } catch (Exception ex) {
                notifyError(ex);
            }
            if (webSocket == ws) webSocket = null;
            if (running) {
                scheduleReconnect("closed " + statusCode + ": " + reason);
            } else {
                setState(State.STOPPED, "closed");
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            notifyError(error);
            if (webSocket == ws) webSocket = null;
            if (running) {
                scheduleReconnect("websocket error: " + message(error));
            } else {
                setState(State.FAILED, message(error));
            }
        }
    }

    private static String message(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
