package app.execution.fix;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class FixTcpGateway implements ExecutionGateway, AutoCloseable {
    private final FixSessionConfig config;
    private final FixMessageBuilder builder;
    private final Duration reconnectBackoff = Duration.ofSeconds(2);
    private Socket socket;
    private BufferedWriter writer;
    private long lastConnectAttempt;

    public FixTcpGateway(FixSessionConfig config) {
        this.config = config;
        this.builder = new FixMessageBuilder(config);
    }

    public synchronized void connect() throws Exception {
        if (isConnected()) return;
        closeQuietly();
        lastConnectAttempt = System.currentTimeMillis();
        socket = config.tls
                ? SSLSocketFactory.getDefault().createSocket(config.host, config.port)
                : new Socket(config.host, config.port);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        write(builder.logon());
    }

    @Override
    public synchronized ExecutionReport send(ExecutionOrder order) throws Exception {
        if (!ensureConnected()) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "FIX TCP gateway disconnected");
        }
        try {
            write(builder.newOrderSingle(order));
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.ACCEPTED,
                    0.0, 0.0, "FIX NewOrderSingle sent");
        } catch (Exception firstError) {
            closeQuietly();
            if (!ensureConnected()) throw firstError;
            write(builder.newOrderSingle(order));
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.ACCEPTED,
                    0.0, 0.0, "FIX NewOrderSingle sent after reconnect");
        }
    }

    @Override
    public synchronized ExecutionReport cancel(String clientOrderId) {
        return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                0.0, 0.0, "Cancel requires symbol/side in venue adapter");
    }

    @Override
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized boolean heartbeat() {
        if (!ensureConnected()) return false;
        try {
            write(builder.heartbeat());
            return true;
        } catch (Exception ex) {
            closeQuietly();
            return false;
        }
    }

    @Override
    public String venue() {
        return "FIX " + config.host + ":" + config.port;
    }

    @Override
    public synchronized void close() throws Exception {
        if (writer != null) writer.close();
        if (socket != null) socket.close();
        writer = null;
        socket = null;
    }

    private void write(String message) throws Exception {
        writer.write(message);
        writer.flush();
    }

    private boolean ensureConnected() {
        if (isConnected()) return true;
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < reconnectBackoff.toMillis()) return false;
        try {
            connect();
            return true;
        } catch (Exception ignored) {
            closeQuietly();
            return false;
        }
    }

    private void closeQuietly() {
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        writer = null;
        socket = null;
    }
}
