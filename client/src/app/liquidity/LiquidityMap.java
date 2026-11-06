package app.liquidity;

import app.common.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LiquidityMap {
    public final List<LiquidityLevel> buySide;
    public final List<LiquidityLevel> sellSide;
    public final List<LiquiditySweep> sweeps;
    public final List<InducementEvent> inducements;

    public LiquidityMap(List<LiquidityLevel> buySide, List<LiquidityLevel> sellSide,
                        List<LiquiditySweep> sweeps, List<InducementEvent> inducements) {
        this.buySide = sorted(buySide);
        this.sellSide = sorted(sellSide);
        this.sweeps = Collections.unmodifiableList(new ArrayList<>(sweeps));
        this.inducements = Collections.unmodifiableList(new ArrayList<>(inducements));
    }

    public List<LiquidityLevel> allLevels() {
        List<LiquidityLevel> all = new ArrayList<>();
        all.addAll(buySide);
        all.addAll(sellSide);
        return all;
    }

    public LiquiditySweep latestSweep(Direction direction) {
        for (int i = sweeps.size() - 1; i >= 0; i--) {
            LiquiditySweep sweep = sweeps.get(i);
            if (sweep.reversalDirection == direction) return sweep;
        }
        return null;
    }

    public LiquidityLevel nearestUntouched(double price, boolean buySideWanted) {
        List<LiquidityLevel> source = buySideWanted ? buySide : sellSide;
        LiquidityLevel best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LiquidityLevel level : source) {
            if (level.swept) continue;
            double distance = Math.abs(level.price - price);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = level;
            }
        }
        return best;
    }

    private List<LiquidityLevel> sorted(List<LiquidityLevel> levels) {
        List<LiquidityLevel> copy = new ArrayList<>(levels);
        copy.sort(Comparator.comparingInt((LiquidityLevel l) -> l.strength).reversed()
                .thenComparingDouble(l -> l.price));
        return Collections.unmodifiableList(copy);
    }
}
