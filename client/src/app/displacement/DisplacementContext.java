package app.displacement;

import app.common.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DisplacementContext {
    private final List<DisplacementEvent> events;
    private final DisplacementEvent[] byIndex;

    public DisplacementContext(List<DisplacementEvent> events, DisplacementEvent[] byIndex) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.byIndex = byIndex;
    }

    public List<DisplacementEvent> events() {
        return events;
    }

    public DisplacementEvent at(int index) {
        if (index < 0 || index >= byIndex.length) return null;
        return byIndex[index];
    }

    public DisplacementEvent latest(Direction direction) {
        for (int i = events.size() - 1; i >= 0; i--) {
            DisplacementEvent event = events.get(i);
            if (event.direction == direction) return event;
        }
        return null;
    }

    public DisplacementEvent bestAfter(int fromIndex, Direction direction, int maxBars) {
        DisplacementEvent best = null;
        int maxIndex = fromIndex + maxBars;
        for (DisplacementEvent event : events) {
            if (event.index <= fromIndex || event.index > maxIndex) continue;
            if (event.direction != direction) continue;
            if (best == null || event.score > best.score) best = event;
        }
        return best;
    }
}
