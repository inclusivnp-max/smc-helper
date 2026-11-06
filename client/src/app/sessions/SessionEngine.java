package app.sessions;

import app.model.Candle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class SessionEngine {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public SessionContext analyze(List<Candle> candles) {
        if (candles.isEmpty()) {
            return new SessionContext("unknown", "idle", false, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, -1, -1);
        }

        Candle last = candles.get(candles.size() - 1);
        ZonedDateTime ny = Instant.ofEpochMilli(last.time).atZone(NEW_YORK);
        LocalDate tradeDate = ny.toLocalDate();
        LocalTime time = ny.toLocalTime();

        String session = activeSession(time);
        boolean killzone = inRange(time, LocalTime.of(2, 0), LocalTime.of(5, 0))
                || inRange(time, LocalTime.of(7, 0), LocalTime.of(10, 0));
        String phase = phase(time, killzone);

        double asiaHigh = -Double.MAX_VALUE;
        double asiaLow = Double.MAX_VALUE;
        int asiaHighIndex = -1;
        int asiaLowIndex = -1;
        double sessionHigh = -Double.MAX_VALUE;
        double sessionLow = Double.MAX_VALUE;

        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            ZonedDateTime t = Instant.ofEpochMilli(c.time).atZone(NEW_YORK);
            LocalDate d = t.toLocalDate();
            LocalTime lt = t.toLocalTime();
            boolean sameTradingWindow = d.equals(tradeDate) || d.equals(tradeDate.minusDays(1));

            if (sameTradingWindow && isAsiaRange(lt)) {
                if (c.high > asiaHigh) {
                    asiaHigh = c.high;
                    asiaHighIndex = i;
                }
                if (c.low < asiaLow) {
                    asiaLow = c.low;
                    asiaLowIndex = i;
                }
            }
            if (d.equals(tradeDate)) {
                sessionHigh = Math.max(sessionHigh, c.high);
                sessionLow = Math.min(sessionLow, c.low);
            }
        }

        if (asiaHigh == -Double.MAX_VALUE) asiaHigh = Double.NaN;
        if (asiaLow == Double.MAX_VALUE) asiaLow = Double.NaN;
        if (sessionHigh == -Double.MAX_VALUE) sessionHigh = Double.NaN;
        if (sessionLow == Double.MAX_VALUE) sessionLow = Double.NaN;

        return new SessionContext(session, phase, killzone, asiaHigh, asiaLow,
                sessionHigh, sessionLow, asiaHighIndex, asiaLowIndex);
    }

    private boolean isAsiaRange(LocalTime time) {
        // Asia session (NY time): 20:00 -> 04:00 следующего дня.
        // Ранее ошибочно стояло isBefore(0:01) — захватывало только до полуночи.
        return time.isAfter(LocalTime.of(19, 59)) || time.isBefore(LocalTime.of(4, 0));
    }

    private String activeSession(LocalTime time) {
        if (inRange(time, LocalTime.of(20, 0), LocalTime.of(0, 0))) return "Asia";
        if (inRange(time, LocalTime.of(2, 0), LocalTime.of(5, 0))) return "London killzone";
        if (inRange(time, LocalTime.of(7, 0), LocalTime.of(10, 0))) return "New York killzone";
        if (inRange(time, LocalTime.of(10, 0), LocalTime.of(12, 0))) return "NY continuation";
        if (inRange(time, LocalTime.of(12, 0), LocalTime.of(16, 0))) return "PM session";
        return "off-killzone";
    }

    private String phase(LocalTime time, boolean killzone) {
        if (isAsiaRange(time)) return "range building";
        if (killzone) return "manipulation/expansion window";
        if (inRange(time, LocalTime.of(10, 0), LocalTime.of(12, 0))) return "continuation";
        return "mean reversion risk";
    }

    private boolean inRange(LocalTime time, LocalTime start, LocalTime end) {
        if (start.equals(end)) return true;
        if (start.isBefore(end)) return !time.isBefore(start) && time.isBefore(end);
        return !time.isBefore(start) || time.isBefore(end);
    }
}
