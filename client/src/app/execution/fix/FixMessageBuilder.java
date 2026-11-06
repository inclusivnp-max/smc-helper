package app.execution.fix;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class FixMessageBuilder {
    private static final char SOH = '\u0001';
    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);

    private final FixSessionConfig config;
    private int outgoingSeqNum = 1;

    public FixMessageBuilder(FixSessionConfig config) {
        this.config = config;
    }

    public synchronized String logon() {
        StringBuilder body = standardHeader("A");
        tag(body, 98, "0");
        tag(body, 108, Integer.toString(config.heartbeatSeconds));
        if (!config.username.isBlank()) tag(body, 553, config.username);
        if (!config.password.isBlank()) tag(body, 554, config.password);
        return wrap(body);
    }

    public synchronized String heartbeat() {
        return wrap(standardHeader("0"));
    }

    public synchronized String newOrderSingle(ExecutionOrder order) {
        StringBuilder body = standardHeader("D");
        tag(body, 11, order.clientOrderId);
        tag(body, 55, order.symbol);
        tag(body, 54, order.side == ExecutionOrder.Side.BUY ? "1" : "2");
        tag(body, 38, format(order.quantity));
        tag(body, 40, ordType(order.type));
        tag(body, 59, tif(order.timeInForce));
        if (order.type != ExecutionOrder.Type.MARKET && order.type != ExecutionOrder.Type.STOP_MARKET) tag(body, 44, format(order.price));
        if (order.type == ExecutionOrder.Type.STOP_LIMIT || order.type == ExecutionOrder.Type.STOP_MARKET) tag(body, 99, format(order.stopPrice));
        tag(body, 60, TS.format(Instant.now()));
        if (!order.strategyTag.isBlank()) tag(body, 58, order.strategyTag);
        return wrap(body);
    }

    public synchronized String cancelRequest(String originalClientOrderId, String cancelClientOrderId, String symbol,
                                             ExecutionOrder.Side side) {
        StringBuilder body = standardHeader("F");
        tag(body, 41, originalClientOrderId);
        tag(body, 11, cancelClientOrderId);
        tag(body, 55, symbol);
        tag(body, 54, side == ExecutionOrder.Side.BUY ? "1" : "2");
        tag(body, 60, TS.format(Instant.now()));
        return wrap(body);
    }

    private StringBuilder standardHeader(String msgType) {
        StringBuilder body = new StringBuilder();
        tag(body, 35, msgType);
        tag(body, 49, config.senderCompId);
        tag(body, 56, config.targetCompId);
        tag(body, 34, Integer.toString(outgoingSeqNum++));
        tag(body, 52, TS.format(Instant.now()));
        return body;
    }

    private String wrap(StringBuilder body) {
        String bodyText = body.toString();
        String headWithoutLength = "8=" + config.beginString + SOH;
        String length = "9=" + bodyText.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length + SOH;
        String withoutChecksum = headWithoutLength + length + bodyText;
        int checksum = 0;
        for (int i = 0; i < withoutChecksum.length(); i++) checksum += withoutChecksum.charAt(i);
        checksum %= 256;
        return withoutChecksum + "10=" + String.format("%03d", checksum) + SOH;
    }

    private void tag(StringBuilder sb, int tag, String value) {
        if (value == null) value = "";
        sb.append(tag).append('=').append(value).append(SOH);
    }

    private String ordType(ExecutionOrder.Type type) {
        return switch (type) {
            case MARKET -> "1";
            case LIMIT -> "2";
            case STOP_MARKET -> "3";
            case STOP_LIMIT -> "4";
        };
    }

    private String tif(ExecutionOrder.TimeInForce tif) {
        return switch (tif) {
            case GTC -> "1";
            case IOC -> "3";
            case FOK -> "4";
            case GTD -> "6";
        };
    }

    private String format(double value) {
        return String.format(Locale.US, "%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
