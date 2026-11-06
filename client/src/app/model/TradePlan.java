package app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TradePlan {
    public final boolean longSide;
    public final Zone zone;
    public final double entry, stop, tp1, tp2, tp3;
    public final int score;
    public final String scenario;
    public final int confidence;
    public final boolean entryConfirmed;
    public final int sweepIndex;
    public final int chochIndex;
    public final String setupType;
    public final String targetModel;
    public final String liquidityTargetLabel;
    public final double liquidityTarget;
    public final List<String> factors;
    public final List<String> warnings;
    private static final double IDEAL_TP1_R = 1.0;
    private static final double IDEAL_TP2_R = 2.0;
    private static final double IDEAL_TP3_R = 3.0;
    private static final double MAX_USEFUL_TARGET_R = 3.25;

    private TradePlan(boolean l, Zone z, double e, double s, double t1, double t2, double t3) {
        this(l, z, e, s, t1, t2, t3, z.score, "Legacy zone reaction", false, -1, -1,
                "legacy", "fixed-r", Double.NaN, "", z.reasons, Collections.emptyList());
    }

    private TradePlan(boolean l, Zone z, double e, double s, double t1, double t2, double t3,
                      int confidence, String scenario, boolean entryConfirmed,
                      int sweepIndex, int chochIndex, String setupType, String targetModel,
                      double liquidityTarget, String liquidityTargetLabel,
                      List<String> factors, List<String> warnings) {
        longSide = l; zone = z; entry = e; stop = s;
        tp1 = t1; tp2 = t2; tp3 = t3;
        this.confidence = Math.max(0, Math.min(100, confidence));
        score = this.confidence;
        this.scenario = scenario;
        this.entryConfirmed = entryConfirmed;
        this.sweepIndex = sweepIndex;
        this.chochIndex = chochIndex;
        this.setupType = setupType == null ? "" : setupType;
        this.targetModel = targetModel == null ? "fixed-r" : targetModel;
        this.liquidityTarget = liquidityTarget;
        this.liquidityTargetLabel = liquidityTargetLabel == null ? "" : liquidityTargetLabel;
        this.factors = Collections.unmodifiableList(new ArrayList<>(
                factors == null ? Collections.emptyList() : factors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(
                warnings == null ? Collections.emptyList() : warnings));
    }

    public static TradePlan forLong(double price, Zone z) {
        // Увеличиваем буферы для входа и стопа!
        // 0.001 - это 0.1% от цены биткоина (примерно 60-70 баксов)
        double stopBuffer = price * 0.002;  // Стоп отодвигаем на 0.2% ниже зоны
        double entryBuffer = price * 0.001; // Входим на 0.1% раньше зоны

        double entry = z.top + entryBuffer;
        double stop = z.bottom - stopBuffer;
        double risk = entry - stop;

        return new TradePlan(true, z, entry, stop,
                entry + risk, entry + risk * 2, entry + risk * 3);
    }

    public static TradePlan forShort(double price, Zone z) {
        double stopBuffer = price * 0.002;
        double entryBuffer = price * 0.001;

        double entry = z.bottom - entryBuffer;
        double stop = z.top + stopBuffer;
        double risk = stop - entry;

        return new TradePlan(false, z, entry, stop,
                entry - risk, entry - risk * 2, entry - risk * 3);
    }

    public static TradePlan smart(boolean longSide, double currentPrice, Zone zone, double atr,
                                  int confidence, String scenario, boolean entryConfirmed,
                                  int sweepIndex, int chochIndex,
                                  List<String> factors, List<String> warnings) {
        return smart(longSide, currentPrice, zone, atr, confidence, scenario, entryConfirmed,
                sweepIndex, chochIndex, "unclassified", Double.NaN, "", factors, warnings);
    }

    public static TradePlan smart(boolean longSide, double currentPrice, Zone zone, double atr,
                                  int confidence, String scenario, boolean entryConfirmed,
                                  int sweepIndex, int chochIndex,
                                  String setupType, double liquidityTarget, String liquidityTargetLabel,
                                  List<String> factors, List<String> warnings) {
        double minBuffer = Math.max(atr * 0.18, currentPrice * 0.00035);
        double entryBuffer = Math.max(atr * 0.06, currentPrice * 0.00015);

        double entry;
        double stop;
        if (longSide) {
            entry = Math.min(currentPrice, zone.top + entryBuffer);
            stop = zone.bottom - minBuffer;
            double risk = Math.max(entry - stop, atr * 0.35);
            double[] targets = targets(true, entry, risk, liquidityTarget);
            return new TradePlan(true, zone, entry, stop,
                    targets[0], targets[1], targets[2],
                    confidence, scenario, entryConfirmed, sweepIndex, chochIndex,
                    setupType, targetModel(targets[3]),
                    liquidityTarget, liquidityTargetLabel, factors, warnings);
        }

        entry = Math.max(currentPrice, zone.bottom - entryBuffer);
        stop = zone.top + minBuffer;
        double risk = Math.max(stop - entry, atr * 0.35);
        double[] targets = targets(false, entry, risk, liquidityTarget);
        return new TradePlan(false, zone, entry, stop,
                targets[0], targets[1], targets[2],
                confidence, scenario, entryConfirmed, sweepIndex, chochIndex,
                setupType, targetModel(targets[3]),
                liquidityTarget, liquidityTargetLabel, factors, warnings);
    }

    public static TradePlan restored(boolean longSide, double entry, double stop,
                                     double tp1, double tp2, double tp3,
                                     int confidence, boolean entryConfirmed,
                                     String setupType) {
        return new TradePlan(longSide, null, entry, stop, tp1, tp2, tp3,
                confidence, "Restored from execution journal", entryConfirmed,
                -1, -1, setupType, "restored", Double.NaN, "",
                List.of("execution journal restore"), Collections.emptyList());
    }

    private static double[] targets(boolean longSide, double entry, double risk, double liquidityTarget) {
        double dir = longSide ? 1.0 : -1.0;
        double tp1 = entry + dir * risk * IDEAL_TP1_R;
        double tp2 = entry + dir * risk * IDEAL_TP2_R;
        double tp3 = entry + dir * risk * IDEAL_TP3_R;
        boolean validTarget = !Double.isNaN(liquidityTarget)
                && (longSide ? liquidityTarget > entry + risk * 0.85 : liquidityTarget < entry - risk * 0.85);
        if (!validTarget) return new double[]{tp1, tp2, tp3, 0.0};

        double targetR = Math.abs(liquidityTarget - entry) / Math.max(risk, 0.00000001);
        if (targetR < 1.05) return new double[]{tp1, tp2, tp3, 0.0};
        if (targetR > MAX_USEFUL_TARGET_R) {
            return new double[]{tp1, tp2, tp3, 2.0};
        }
        tp3 = liquidityTarget;
        double tp1R = Math.min(IDEAL_TP1_R, targetR);
        double tp2R = Math.min(IDEAL_TP2_R, Math.max(1.15, targetR * 0.68));
        tp1 = entry + dir * risk * tp1R;
        tp2 = entry + dir * risk * tp2R;
        if (longSide) {
            tp1 = Math.min(tp1, tp3);
            tp2 = Math.min(tp2, tp3);
        } else {
            tp1 = Math.max(tp1, tp3);
            tp2 = Math.max(tp2, tp3);
        }
        return new double[]{tp1, tp2, tp3, 1.0};
    }

    private static String targetModel(double marker) {
        if (marker >= 2.0) return "rr-capped-3r";
        if (marker >= 1.0) return "liquidity-target";
        return "fixed-r";
    }

    /**
     * Рейтинг качества сделки на основе confidence, entryConfirmed, R:R и зоны.
     * A+ = институциональный полный сценарий с высоким R:R
     * A  = подтверждённый свип + displacement + CHoCH
     * B  = continuation или частичное подтверждение
     * C  = слабый сигнал, watchlist
     */
    public String qualityRating() {
        if (confidence >= 78 && entryConfirmed && rr3() >= 2.5
                && zone != null && zone.displacementScore >= 65) return "A+";
        if (confidence >= 65 && entryConfirmed) return "A";
        if (confidence >= 52) return "B";
        return "C";
    }

    /**
     * Ожидаемая математическая ценность при правиле 40/40/20 по тейкам, использующая
     * реальный откалиброванный win rate (например, app.backtest.RadarBacktestStats.
     * calibratedWinRate01(...), построенный на фактических исходах сделок из бэктестов).
     * Предпочтительнее no-arg версии ниже везде, где есть доступ к историческим данным.
     */
    public double expectedValueR(double calibratedWinRate01) {
        double winRate = Math.min(0.95, Math.max(0.05, calibratedWinRate01));
        double reward = weightedRewardR(0.40, 0.40, 0.20);
        return winRate * reward - (1.0 - winRate) * 1.0; // 1R риск при проигрыше
    }

    /**
     * Фолбэк-версия на случай, если исторической статистики нет вообще: грубо считает win rate
     * линейно от confidence. confidence — это накопленная сумма очков за конфлюенцию, а не
     * откалиброванная вероятность, так что эта формула — эвристика на крайний случай, а не
     * честная оценка. Используйте overload выше, когда доступен AnalysisResult/TradePlan для
     * RadarBacktestStats.calibratedWinRate01(...).
     */
    public double expectedValueR() {
        double fallbackWinRate = Math.min(0.75, Math.max(0.25, confidence / 100.0 * 0.80));
        return expectedValueR(fallbackWinRate);
    }

    public double risk() {
        return Math.abs(entry - stop);
    }

    public double rrTo(double target) {
        double risk = risk();
        if (risk <= 0.0 || !Double.isFinite(target)) return 0.0;
        return Math.abs(target - entry) / risk;
    }

    public double rr1() {
        return rrTo(tp1);
    }

    public double rr2() {
        return rrTo(tp2);
    }

    public double rr3() {
        return rrTo(tp3);
    }

    public double weightedRewardR(double tp1Size, double tp2Size, double tp3Size) {
        return Math.max(0.0, tp1Size) * rr1()
                + Math.max(0.0, tp2Size) * rr2()
                + Math.max(0.0, tp3Size) * rr3();
    }
}
