package app.execution.fix;

public class OrderNormalization {
    public final ExecutionOrder order;
    public final InstrumentSpec spec;
    public final boolean changed;
    public final String message;

    public OrderNormalization(ExecutionOrder order, InstrumentSpec spec, boolean changed, String message) {
        this.order = order;
        this.spec = spec;
        this.changed = changed;
        this.message = message == null ? "" : message;
    }

    public static OrderNormalization unchanged(ExecutionOrder order) {
        return new OrderNormalization(order, null, false, "");
    }
}
