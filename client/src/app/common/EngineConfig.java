package app.common;

public class EngineConfig {
    public final int atrPeriod;
    public final int swingInternalLeft;
    public final int swingInternalRight;
    public final int swingExternalLeft;
    public final int swingExternalRight;
    public final int equalLiquidityMinTouches;
    public final double equalLiquidityAtrTolerance;
    public final double sweepAtrTolerance;
    public final int sweepDisplacementLookahead;
    public final int maxActiveZones;
    public final int maxLiquidityLevels;
    public final int entryLookbackBars;
    public final double minZoneAtrWidth;
    public final double minFvgAtrSize;
    public final double chopAtrCompression;
    public final double spreadBps;
    public final double slippageBps;

    private EngineConfig(Builder b) {
        this.atrPeriod               = b.atrPeriod;
        this.swingInternalLeft       = b.swingInternalLeft;
        this.swingInternalRight      = b.swingInternalRight;
        this.swingExternalLeft       = b.swingExternalLeft;
        this.swingExternalRight      = b.swingExternalRight;
        this.equalLiquidityMinTouches   = b.equalLiquidityMinTouches;
        this.equalLiquidityAtrTolerance = b.equalLiquidityAtrTolerance;
        this.sweepAtrTolerance          = b.sweepAtrTolerance;
        this.sweepDisplacementLookahead = b.sweepDisplacementLookahead;
        this.maxActiveZones          = b.maxActiveZones;
        this.maxLiquidityLevels      = b.maxLiquidityLevels;
        this.entryLookbackBars       = b.entryLookbackBars;
        this.minZoneAtrWidth         = b.minZoneAtrWidth;
        this.minFvgAtrSize           = b.minFvgAtrSize;
        this.chopAtrCompression      = b.chopAtrCompression;
        this.spreadBps               = b.spreadBps;
        this.slippageBps             = b.slippageBps;
    }

    public static EngineConfig defaults() {
        return new Builder().build();
    }

    /** Returns a Builder pre-populated with this config's values for easy overrides. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static class Builder {
        private int    atrPeriod               = 14;
        private int    swingInternalLeft        = 2;
        private int    swingInternalRight       = 2;
        private int    swingExternalLeft        = 5;
        private int    swingExternalRight       = 5;
        private int    equalLiquidityMinTouches = 2;
        private double equalLiquidityAtrTolerance = 0.22;
        private double sweepAtrTolerance          = 0.05;
        private int    sweepDisplacementLookahead  = 4;
        private int    maxActiveZones           = 36;
        private int    maxLiquidityLevels       = 12;
        private int    entryLookbackBars        = 80;
        private double minZoneAtrWidth          = 0.12;
        private double minFvgAtrSize            = 0.08;
        private double chopAtrCompression       = 0.70;
        private double spreadBps                = 2.5;
        private double slippageBps              = 1.5;

        public Builder() {}

        private Builder(EngineConfig c) {
            this.atrPeriod               = c.atrPeriod;
            this.swingInternalLeft       = c.swingInternalLeft;
            this.swingInternalRight      = c.swingInternalRight;
            this.swingExternalLeft       = c.swingExternalLeft;
            this.swingExternalRight      = c.swingExternalRight;
            this.equalLiquidityMinTouches   = c.equalLiquidityMinTouches;
            this.equalLiquidityAtrTolerance = c.equalLiquidityAtrTolerance;
            this.sweepAtrTolerance          = c.sweepAtrTolerance;
            this.sweepDisplacementLookahead = c.sweepDisplacementLookahead;
            this.maxActiveZones          = c.maxActiveZones;
            this.maxLiquidityLevels      = c.maxLiquidityLevels;
            this.entryLookbackBars       = c.entryLookbackBars;
            this.minZoneAtrWidth         = c.minZoneAtrWidth;
            this.minFvgAtrSize           = c.minFvgAtrSize;
            this.chopAtrCompression      = c.chopAtrCompression;
            this.spreadBps               = c.spreadBps;
            this.slippageBps             = c.slippageBps;
        }

        public Builder atrPeriod(int v)                    { this.atrPeriod = v; return this; }
        public Builder swingInternalLeft(int v)            { this.swingInternalLeft = v; return this; }
        public Builder swingInternalRight(int v)           { this.swingInternalRight = v; return this; }
        public Builder swingExternalLeft(int v)            { this.swingExternalLeft = v; return this; }
        public Builder swingExternalRight(int v)           { this.swingExternalRight = v; return this; }
        public Builder equalLiquidityMinTouches(int v)     { this.equalLiquidityMinTouches = v; return this; }
        public Builder equalLiquidityAtrTolerance(double v){ this.equalLiquidityAtrTolerance = v; return this; }
        public Builder sweepAtrTolerance(double v)         { this.sweepAtrTolerance = v; return this; }
        public Builder sweepDisplacementLookahead(int v)   { this.sweepDisplacementLookahead = v; return this; }
        public Builder maxActiveZones(int v)               { this.maxActiveZones = v; return this; }
        public Builder maxLiquidityLevels(int v)           { this.maxLiquidityLevels = v; return this; }
        public Builder entryLookbackBars(int v)            { this.entryLookbackBars = v; return this; }
        public Builder minZoneAtrWidth(double v)           { this.minZoneAtrWidth = v; return this; }
        public Builder minFvgAtrSize(double v)             { this.minFvgAtrSize = v; return this; }
        public Builder chopAtrCompression(double v)        { this.chopAtrCompression = v; return this; }
        public Builder spreadBps(double v)                 { this.spreadBps = v; return this; }
        public Builder slippageBps(double v)               { this.slippageBps = v; return this; }

        public EngineConfig build() { return new EngineConfig(this); }
    }
}
