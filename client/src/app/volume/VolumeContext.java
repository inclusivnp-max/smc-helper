package app.volume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VolumeContext {
    private final List<VolumeSignal> signals;
    private final VolumeSignal[] byIndex;

    public VolumeContext(List<VolumeSignal> signals, VolumeSignal[] byIndex) {
        this.signals = Collections.unmodifiableList(new ArrayList<>(signals));
        this.byIndex = byIndex;
    }

    public List<VolumeSignal> signals() {
        return signals;
    }

    public VolumeSignal at(int index) {
        if (index < 0 || index >= byIndex.length) return null;
        return byIndex[index];
    }

    public int scoreAt(int index) {
        VolumeSignal signal = at(index);
        return signal == null ? 0 : signal.score;
    }
}
