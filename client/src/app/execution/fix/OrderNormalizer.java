package app.execution.fix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OrderNormalizer {
    private OrderNormalizer() {
    }

    public static OrderNormalization normalize(ExecutionOrder order, InstrumentSpec spec)
            throws OrderNormalizationException {
        if (order == null || spec == null) return OrderNormalization.unchanged(order);

        double quantity = normalizeQuantity(order.quantity, spec);
        boolean marketPriced = order.type == ExecutionOrder.Type.MARKET || order.type == ExecutionOrder.Type.STOP_MARKET;
        double price = marketPriced
                ? order.price
                : normalizePrice(order.price, order.side, order.type, false, spec);
        double stopPrice = order.type == ExecutionOrder.Type.MARKET
                ? order.stopPrice
                : normalizePrice(order.stopPrice, order.side, order.type, true, spec);

        if (quantity <= 0.0) {
            throw new OrderNormalizationException(order.symbol + ": quantity " + fmt(order.quantity)
                    + " ниже шага/минимума " + fmt(spec.stepSize));
        }
        if (spec.minQuantity > 0.0 && quantity + tolerance(quantity) < spec.minQuantity) {
            throw new OrderNormalizationException(order.symbol + ": quantity " + fmt(quantity)
                    + " меньше minQty " + fmt(spec.minQuantity) + " на " + spec.venue);
        }
        if (Double.isFinite(spec.maxQuantity) && quantity - tolerance(quantity) > spec.maxQuantity) {
            throw new OrderNormalizationException(order.symbol + ": quantity " + fmt(quantity)
                    + " больше maxQty " + fmt(spec.maxQuantity) + " на " + spec.venue);
        }
        double notionalPrice = price > 0.0 ? price : order.price;
        if (spec.minNotional > 0.0 && notionalPrice > 0.0
                && quantity * notionalPrice + tolerance(spec.minNotional) < spec.minNotional) {
            throw new OrderNormalizationException(order.symbol + ": notional " + fmt(quantity * notionalPrice)
                    + " меньше minNotional " + fmt(spec.minNotional) + " на " + spec.venue);
        }

        boolean changed = different(order.quantity, quantity)
                || different(order.price, price)
                || different(order.stopPrice, stopPrice);
        ExecutionOrder normalized = new ExecutionOrder(
                order.clientOrderId,
                order.symbol,
                order.side,
                order.type,
                order.timeInForce,
                quantity,
                price,
                stopPrice,
                order.strategyTag);
        return new OrderNormalization(normalized, spec, changed, message(order, normalized, spec));
    }

    private static double normalizeQuantity(double quantity, InstrumentSpec spec) {
        if (!Double.isFinite(quantity) || quantity <= 0.0) return 0.0;
        double value = spec.hasStep() ? floorToStep(quantity, spec.stepSize) : quantity;
        if (spec.quantityPrecision > 0) value = scale(value, spec.quantityPrecision, RoundingMode.DOWN);
        return Math.max(0.0, value);
    }

    private static double normalizePrice(double price,
                                         ExecutionOrder.Side side,
                                         ExecutionOrder.Type type,
                                         boolean trigger,
                                         InstrumentSpec spec) {
        if (!Double.isFinite(price) || price <= 0.0) return price;
        RoundingMode mode = priceRounding(side, type, trigger);
        double value = spec.hasTick() ? roundToStep(price, spec.tickSize, mode) : price;
        if (spec.pricePrecision > 0) value = scale(value, spec.pricePrecision, mode);
        return value;
    }

    private static RoundingMode priceRounding(ExecutionOrder.Side side,
                                              ExecutionOrder.Type type,
                                              boolean trigger) {
        if (type == ExecutionOrder.Type.STOP_LIMIT || type == ExecutionOrder.Type.STOP_MARKET || trigger) {
            return side == ExecutionOrder.Side.BUY ? RoundingMode.UP : RoundingMode.DOWN;
        }
        return side == ExecutionOrder.Side.BUY ? RoundingMode.DOWN : RoundingMode.UP;
    }

    private static double floorToStep(double value, double step) {
        return roundToStep(value, step, RoundingMode.DOWN);
    }

    private static double roundToStep(double value, double step, RoundingMode mode) {
        if (!Double.isFinite(step) || step <= 0.0) return value;
        BigDecimal raw = BigDecimal.valueOf(value);
        BigDecimal quantum = BigDecimal.valueOf(step);
        BigDecimal units = raw.divide(quantum, 0, mode);
        return units.multiply(quantum).stripTrailingZeros().doubleValue();
    }

    private static double scale(double value, int precision, RoundingMode mode) {
        return BigDecimal.valueOf(value).setScale(precision, mode).stripTrailingZeros().doubleValue();
    }

    private static boolean different(double a, double b) {
        if (!Double.isFinite(a) && !Double.isFinite(b)) return false;
        return Math.abs(a - b) > tolerance(Math.max(Math.abs(a), Math.abs(b)));
    }

    private static double tolerance(double value) {
        return Math.max(0.000000000001, Math.abs(value) * 0.000000001);
    }

    private static String message(ExecutionOrder original, ExecutionOrder normalized, InstrumentSpec spec) {
        List<String> parts = new ArrayList<>();
        if (different(original.quantity, normalized.quantity)) {
            parts.add("qty " + fmt(original.quantity) + " -> " + fmt(normalized.quantity));
        }
        if (different(original.price, normalized.price)) {
            parts.add("price " + fmt(original.price) + " -> " + fmt(normalized.price));
        }
        if (different(original.stopPrice, normalized.stopPrice)) {
            parts.add("stop " + fmt(original.stopPrice) + " -> " + fmt(normalized.stopPrice));
        }
        if (parts.isEmpty()) return "instrument ok: " + spec.symbol;
        return "normalized " + spec.symbol + " (" + String.join(", ", parts) + ")";
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.12f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
