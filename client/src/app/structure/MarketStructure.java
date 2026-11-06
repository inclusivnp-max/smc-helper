package app.structure;

import app.common.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MarketStructure {
    public final Direction bias;
    public final int strength;
    public final SwingPoint protectedHigh;
    public final SwingPoint protectedLow;
    public final List<SwingPoint> internalSwings;
    public final List<SwingPoint> externalSwings;
    public final List<StructureEvent> events;

    public MarketStructure(Direction bias, int strength, SwingPoint protectedHigh, SwingPoint protectedLow,
                           List<SwingPoint> internalSwings, List<SwingPoint> externalSwings,
                           List<StructureEvent> events) {
        this.bias = bias;
        this.strength = strength;
        this.protectedHigh = protectedHigh;
        this.protectedLow = protectedLow;
        this.internalSwings = Collections.unmodifiableList(new ArrayList<>(internalSwings));
        this.externalSwings = Collections.unmodifiableList(new ArrayList<>(externalSwings));
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public StructureEvent latestEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    public StructureEvent latestEvent(Direction direction) {
        for (int i = events.size() - 1; i >= 0; i--) {
            StructureEvent event = events.get(i);
            if (event.direction == direction) return event;
        }
        return null;
    }
}
