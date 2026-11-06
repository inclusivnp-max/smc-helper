package app.model;

import java.util.ArrayList;
import java.util.List;

public class Zone {
    public final String type;
    public final boolean bullish;
    public double top, bottom;
    public final int creationIndex;
    public int mitigatedIndex = -1;
    public boolean mitigated = false;
    public boolean sweepConfirmed = false;
    public boolean entryConfirmed = false;
    public boolean institutional = false;
    public String source = "legacy";
    public String quality = "weak";
    public String relatedLiquidity = "";
    public int displacementScore = 0;
    public int liquidityScore = 0;
    public int structureScore = 0;
    public int volumeScore = 0;
    public double mitigationDepth = 0.0;
    public int score = 0;
    public String mechanic = "";
    public String setupModel = "";
    public String targetLiquidity = "";
    public int mechanicsScore = 0;
    public boolean inverted = false;
    public boolean breakerBlock = false;
    public boolean mitigationBlock = false;
    public boolean propulsionBlock = false;
    public boolean premiumDiscountAligned = false;
    public boolean liquidityToLiquidity = false;
    public boolean highQualityModel = false;
    public final List<String> reasons = new ArrayList<>();

    public Zone(String type, boolean bullish, double top, double bottom, int idx) {
        this.type = type;
        this.bullish = bullish;
        this.top = Math.max(top, bottom);
        this.bottom = Math.min(top, bottom);
        this.creationIndex = idx;
    }

    public double mid() { return (top + bottom) / 2.0; }
    public double height() { return top - bottom; }

    public String mechanicShort() {
        if (inverted) return "IFVG";
        if (breakerBlock) return "BRK";
        if (propulsionBlock) return "PROP";
        if (mitigationBlock) return "MIT";
        if (highQualityModel) return "A+";
        if (liquidityToLiquidity) return "L2L";
        return "";
    }

    public String mechanicLabel() {
        if (mechanic != null && !mechanic.isBlank()) return mechanic;
        if (inverted) return "Inverted FVG";
        if (breakerBlock) return "Breaker Block";
        if (propulsionBlock) return "Propulsion Block";
        if (mitigationBlock) return "Mitigation Block";
        if (highQualityModel) return "High-quality SMC model";
        if (liquidityToLiquidity) return "Liquidity-to-liquidity";
        return "Classic zone";
    }
}
