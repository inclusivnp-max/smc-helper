package app.execution.fix;

import java.util.List;

public interface ExecutionGateway {
    ExecutionReport send(ExecutionOrder order) throws Exception;
    ExecutionReport cancel(String clientOrderId) throws Exception;

    default List<OpenOrderSnapshot> openOrders() throws Exception {
        throw new UnsupportedOperationException("Gateway does not support openOrders reconciliation");
    }

    default AccountSnapshot accountSnapshot() throws Exception {
        return AccountSnapshot.ordersOnly(venue(), openOrders());
    }

    default OrderNormalization normalizeOrder(ExecutionOrder order) throws Exception {
        return OrderNormalization.unchanged(order);
    }

    default void setLeverage(String symbol, int leverage) throws Exception {
        // Some venues/adapters do not support leverage changes yet. Order sizing still uses the selected leverage.
    }

    boolean isConnected();
    String venue();
}
