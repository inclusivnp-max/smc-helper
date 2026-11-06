package app.infrastructure;

import app.common.Json;
import app.logic.BinanceClient;

import java.util.List;
import java.util.Map;

public class LiveMarketDataService implements AutoCloseable {
    public interface Listener {
        default void onState(ResilientWebSocketClient.State state, String reason) {}
        default void onLastPrice(double price) {}
        default void onAggTrade(long time, double price, double quantity, boolean buyerMaker) {}
        default void onAggTrade(long aggregateId, long time, double price, double quantity, boolean buyerMaker) {
            onAggTrade(time, price, quantity, buyerMaker);
        }
        default void onKline(long openTime, double open, double high, double low, double close,
                             double volume, boolean closed) {}
        default void onDepth(long eventTime) {}
        default void onError(String message) {}
    }

    private final ResilientWebSocketClient client;

    public LiveMarketDataService(String symbol,
                                 BinanceClient.Market market,
                                 String interval,
                                 Listener listener) {
        Listener safeListener = listener == null ? new Listener() {} : listener;
        this.client = BinanceStreamFactory.combinedMarketStreams(
                "live-" + symbol,
                market,
                List.of(
                        BinanceStreamFactory.klineStream(symbol, interval),
                        BinanceStreamFactory.aggTradeStream(symbol),
                        BinanceStreamFactory.depthStream(symbol)),
                new ResilientWebSocketClient.Listener() {
                    @Override
                    public void onState(ResilientWebSocketClient.State state, String reason) {
                        safeListener.onState(state, reason);
                    }

                    @Override
                    public void onText(String message) {
                        handleMessage(message, safeListener);
                    }

                    @Override
                    public void onError(Throwable error) {
                        safeListener.onError(error == null ? "" : error.getMessage());
                    }
                });
    }

    public void start() {
        client.start();
    }

    public void stop() {
        client.stop();
    }

    public String status() {
        return client.status();
    }

    @Override
    public void close() {
        client.close();
    }

    private void handleMessage(String message, Listener listener) {
        try {
            Map<String, Object> envelope = Json.asObject(Json.parse(message));
            Map<String, Object> data = Json.asObject(envelope.get("data"));
            String event = Json.string(data.get("e"), "");
            if ("aggTrade".equals(event)) {
                long aggregateId = Json.integer(data.get("a"), 0L);
                long time = Json.integer(data.get("T"), Json.integer(data.get("E"), 0L));
                double price = Json.number(data.get("p"), Double.NaN);
                double quantity = Json.number(data.get("q"), 0.0);
                boolean buyerMaker = Json.bool(data.get("m"), false);
                if (Double.isFinite(price) && price > 0.0) {
                    listener.onLastPrice(price);
                    listener.onAggTrade(aggregateId, time, price, quantity, buyerMaker);
                }
            } else if ("kline".equals(event)) {
                Map<String, Object> k = Json.asObject(data.get("k"));
                double close = Json.number(k.get("c"), Double.NaN);
                if (Double.isFinite(close) && close > 0.0) listener.onLastPrice(close);
                listener.onKline(
                        Json.integer(k.get("t"), 0L),
                        Json.number(k.get("o"), 0.0),
                        Json.number(k.get("h"), 0.0),
                        Json.number(k.get("l"), 0.0),
                        close,
                        Json.number(k.get("v"), 0.0),
                        Json.bool(k.get("x"), false));
            } else if ("depthUpdate".equals(event)) {
                listener.onDepth(Json.integer(data.get("E"), 0L));
            }
        } catch (Exception ex) {
            listener.onError("live stream parse failed: " + ex.getMessage());
        }
    }
}
