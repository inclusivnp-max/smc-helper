package app.learning;

import app.derivatives.DerivativesContext;
import app.common.ProjectPaths;
import app.ml.MlBridgeClient;
import app.ml.MlFeatureVector;
import app.orderbook.OrderBookContext;
import app.regime.MarketRegime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public class FactorWeightOptimizer {
    private static final Path ROOT = ProjectPaths.weightsRoot();
    private static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;

    public AdaptiveFactorWeights load(String symbol) {
        String safe = safeSymbol(symbol);
        Path path = ROOT.resolve(safe + ".properties");
        if (!Files.exists(path)) return AdaptiveFactorWeights.defaults(safe);
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return new AdaptiveFactorWeights(
                    safe,
                    parse(properties, "displacement", 1.0),
                    parse(properties, "volume", 1.0),
                    parse(properties, "liquidity", 1.0),
                    parse(properties, "orderFlow", 1.0),
                    parse(properties, "regime", 1.0),
                    Long.parseLong(properties.getProperty("updatedAt", "0")));
        } catch (Exception ex) {
            return AdaptiveFactorWeights.defaults(safe);
        }
    }

    public AdaptiveFactorWeights loadOrRequest(String symbol,
                                               int smcScore,
                                               DerivativesContext derivatives,
                                               OrderBookContext orderBook,
                                               MarketRegime regime) {
        AdaptiveFactorWeights local = load(symbol);
        MlBridgeClient bridge = new MlBridgeClient();
        if (!bridge.enabled()) return local;
        MlFeatureVector vector = MlFeatureVector.from(symbol, smcScore, derivatives, orderBook, regime);
        AdaptiveFactorWeights remote = bridge.requestWeights(vector, local);
        if (remote != local) {
            try {
                save(remote);
            } catch (IOException ignored) {
            }
        }
        return remote;
    }

    public boolean needsWeeklyOptimization(AdaptiveFactorWeights weights) {
        return weights == null || weights.updatedAt <= 0L || System.currentTimeMillis() - weights.updatedAt >= WEEK_MS;
    }

    public void save(AdaptiveFactorWeights weights) throws IOException {
        Files.createDirectories(ROOT);
        Properties properties = new Properties();
        properties.setProperty("displacement", Double.toString(weights.displacementWeight));
        properties.setProperty("volume", Double.toString(weights.volumeWeight));
        properties.setProperty("liquidity", Double.toString(weights.liquidityWeight));
        properties.setProperty("orderFlow", Double.toString(weights.orderFlowWeight));
        properties.setProperty("regime", Double.toString(weights.regimeWeight));
        properties.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        try (BufferedWriter writer = Files.newBufferedWriter(ROOT.resolve(safeSymbol(weights.symbol) + ".properties"),
                StandardCharsets.UTF_8)) {
            properties.store(writer, "SMC adaptive factor weights");
        }
    }

    public AdaptiveFactorWeights heuristicFromBacktest(String symbol, double displacementEdge,
                                                       double volumeEdge, double liquidityEdge,
                                                       double orderFlowEdge, double regimeEdge) {
        return new AdaptiveFactorWeights(safeSymbol(symbol),
                edgeToWeight(displacementEdge),
                edgeToWeight(volumeEdge),
                edgeToWeight(liquidityEdge),
                edgeToWeight(orderFlowEdge),
                edgeToWeight(regimeEdge),
                System.currentTimeMillis());
    }

    private double edgeToWeight(double expectancyR) {
        return Math.max(0.20, Math.min(2.20, 1.0 + expectancyR * 0.55));
    }

    private double parse(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String safeSymbol(String symbol) {
        String text = symbol == null ? "UNKNOWN" : symbol.toUpperCase(Locale.ROOT);
        return text.replaceAll("[^A-Z0-9_\\-]", "");
    }
}
