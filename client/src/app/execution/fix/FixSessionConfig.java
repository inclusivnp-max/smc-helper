package app.execution.fix;

public class FixSessionConfig {
    public final String beginString;
    public final String senderCompId;
    public final String targetCompId;
    public final String host;
    public final int port;
    public final int heartbeatSeconds;
    public final boolean tls;
    public final String username;
    public final String password;

    public FixSessionConfig(String beginString, String senderCompId, String targetCompId,
                            String host, int port, int heartbeatSeconds, boolean tls) {
        this(beginString, senderCompId, targetCompId, host, port, heartbeatSeconds, tls, "", "");
    }

    public FixSessionConfig(String beginString, String senderCompId, String targetCompId,
                            String host, int port, int heartbeatSeconds, boolean tls,
                            String username, String password) {
        this.beginString = beginString == null || beginString.isBlank() ? "FIX.4.4" : beginString;
        this.senderCompId = senderCompId;
        this.targetCompId = targetCompId;
        this.host = host;
        this.port = port;
        this.heartbeatSeconds = Math.max(5, heartbeatSeconds);
        this.tls = tls;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }
}
