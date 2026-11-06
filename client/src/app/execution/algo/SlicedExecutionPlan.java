package app.execution.algo;

import java.util.Collections;
import java.util.List;

public class SlicedExecutionPlan {
    public final String algorithm;
    public final List<ExecutionSlice> slices;
    public final double totalQuantity;
    public final double totalNotional;

    public SlicedExecutionPlan(String algorithm, List<ExecutionSlice> slices,
                               double totalQuantity, double referencePrice) {
        this.algorithm = algorithm == null ? "" : algorithm;
        this.slices = Collections.unmodifiableList(slices);
        this.totalQuantity = totalQuantity;
        this.totalNotional = Math.abs(totalQuantity * referencePrice);
    }
}
