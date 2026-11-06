package app.infrastructure;

import app.logic.BinanceClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BinanceStreamFactory {
    private static final String SPOT_WS = "wss://stream.binance.com:9443";
    private static final String FUTURES_WS = "wss://fstream.binance.com";

    public static ResilientWebSocketClient combinedMarketStreams(String name,
                                                                 BinanceClient.Market market,
                                                                 List<String> streams,
                                                                 ResilientWebSocketClient.Listener listener) {
        return new ResilientWebSocketClient(
                name,
                combinedUri(market, streams),
                ReconnectPolicy.binanceDefault(),
                listener);
    }

    public static URI combinedUri(BinanceClient.Market market, List<String> streams) {
        List<String> cleaned = sanitizeStreams(streams);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("At least one Binance stream is required");
        }
        String base = market == BinanceClient.Market.SPOT ? SPOT_WS : FUTURES_WS;
        return URI.create(base + "/stream?streams=" + String.join("/", cleaned));
    }

    public static String aggTradeStream(String symbol) {
        return normalizeSymbol(symbol) + "@aggTrade";
    }

    public static String depthStream(String symbol) {
        return normalizeSymbol(symbol) + "@depth@100ms";
    }

    public static String klineStream(String symbol, String interval) {
        String tf = interval == null || interval.isBlank() ? "1m" : interval.trim().toLowerCase(Locale.ROOT);
        return normalizeSymbol(symbol) + "@kline_" + tf;
    }

    private static List<String> sanitizeStreams(List<String> streams) {
        List<String> result = new ArrayList<>();
        if (streams == null) return result;
        for (String stream : streams) {
            if (stream == null || stream.isBlank()) continue;
            result.add(stream.trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required for Binance stream");
        }
        return symbol.trim().toLowerCase(Locale.ROOT);
    }
}
