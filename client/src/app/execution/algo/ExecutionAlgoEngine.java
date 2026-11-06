package app.execution.algo;

import app.model.TradePlan;

import java.util.ArrayList;
import java.util.List;

public class ExecutionAlgoEngine {
    public SlicedExecutionPlan twap(TradePlan plan, double quantity, int slices, long durationMs) {
        int count = Math.max(1, slices);
        long step = count <= 1 ? 0L : Math.max(1L, durationMs / count);
        List<ExecutionSlice> result = new ArrayList<>();
        double baseQty = quantity / count;
        for (int i = 0; i < count; i++) {
            result.add(new ExecutionSlice(i + 1, System.currentTimeMillis() + step * i,
                    baseQty, passivePrice(plan, i, count), "TWAP"));
        }
        return new SlicedExecutionPlan("TWAP", result, quantity, plan.entry);
    }

    public SlicedExecutionPlan vwap(TradePlan plan, double quantity, double[] volumeCurve, long durationMs) {
        double[] curve = volumeCurve == null || volumeCurve.length == 0
                ? new double[]{0.08, 0.10, 0.14, 0.18, 0.18, 0.14, 0.10, 0.08}
                : volumeCurve;
        double sum = 0.0;
        for (double v : curve) sum += Math.max(0.0, v);
        if (sum <= 0.0) sum = 1.0;
        long step = Math.max(1L, durationMs / Math.max(1, curve.length));
        List<ExecutionSlice> result = new ArrayList<>();
        for (int i = 0; i < curve.length; i++) {
            double qty = quantity * Math.max(0.0, curve[i]) / sum;
            result.add(new ExecutionSlice(i + 1, System.currentTimeMillis() + step * i,
                    qty, passivePrice(plan, i, curve.length), "VWAP"));
        }
        return new SlicedExecutionPlan("VWAP", result, quantity, plan.entry);
    }

    public SlicedExecutionPlan iceberg(TradePlan plan, double quantity, double displayQuantity) {
        double display = Math.max(quantity / 50.0, displayQuantity);
        int count = Math.max(1, (int) Math.ceil(quantity / display));
        List<ExecutionSlice> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double remaining = quantity - display * i;
            double qty = Math.min(display, remaining);
            result.add(new ExecutionSlice(i + 1, System.currentTimeMillis() + i * 750L,
                    qty, passivePrice(plan, i, count), "ICEBERG"));
        }
        return new SlicedExecutionPlan("ICEBERG", result, quantity, plan.entry);
    }

    public SlicedExecutionPlan auto(TradePlan plan, double quantity, double estimatedNotional) {
        if (estimatedNotional >= 50_000.0) {
            return iceberg(plan, quantity, quantity / 50.0);
        }
        if (estimatedNotional >= 15_000.0) {
            return vwap(plan, quantity, null, 90_000L);
        }
        return twap(plan, quantity, 8, 45_000L);
    }

    private double passivePrice(TradePlan plan, int index, int total) {
        double risk = Math.abs(plan.entry - plan.stop);
        double lean = total <= 1 ? 0.0 : index / (double) (total - 1);
        double offset = risk * (0.015 + lean * 0.025);
        return plan.longSide ? plan.entry - offset : plan.entry + offset;
    }
}
