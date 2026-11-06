package app.ml;

import app.derivatives.DerivativesContext;
import app.orderbook.OrderBookContext;
import app.regime.MarketRegime;

public class MlFeatureVector {
    public final String symbol;
    public final int smcScore;
    public final double openInterestChangePct;
    public final double fundingRate;
    public final double domImbalanceRatio;
    public final boolean spoofingDetected;
    public final int regimeScore;
    public final String regimeType;

    public MlFeatureVector(String symbol,
                           int smcScore,
                           double openInterestChangePct,
                           double fundingRate,
                           double domImbalanceRatio,
                           boolean spoofingDetected,
                           int regimeScore,
                           String regimeType) {
        this.symbol = symbol == null ? "" : symbol;
        this.smcScore = Math.max(0, Math.min(100, smcScore));
        this.openInterestChangePct = openInterestChangePct;
        this.fundingRate = fundingRate;
        this.domImbalanceRatio = domImbalanceRatio;
        this.spoofingDetected = spoofingDetected;
        this.regimeScore = Math.max(0, Math.min(100, regimeScore));
        this.regimeType = regimeType == null ? "" : regimeType;
    }

    public static MlFeatureVector from(String symbol,
                                       int smcScore,
                                       DerivativesContext derivatives,
                                       OrderBookContext orderBook,
                                       MarketRegime regime) {
        return new MlFeatureVector(
                symbol,
                smcScore,
                derivatives != null && derivatives.available ? derivatives.openInterestChangePct : 0.0,
                derivatives != null && derivatives.available ? derivatives.fundingRate : 0.0,
                orderBook != null && orderBook.available ? orderBook.imbalanceRatio : 1.0,
                orderBook != null && orderBook.available && orderBook.spoofingDetected,
                regime == null ? 50 : regime.score,
                regime == null ? "unknown" : regime.label());
    }
}
