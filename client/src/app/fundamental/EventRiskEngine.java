package app.fundamental;

import app.derivatives.DerivativesContext;
import app.model.Candle;
import app.volatility.MarketMetrics;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

public class EventRiskEngine {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    // Real, sourced dates instead of the old day-of-month guesswork (e.g. "any Wednesday
    // 12th-23rd is FOMC", "any weekday 10th-16th is CPI/PPI"), which fired several false
    // positives every month and just as often missed the actual date. FOMC decision days are
    // published years ahead by the Fed; CPI release days are pre-announced by the BLS.
    // Coverage is intentionally 2025-2026 (confirmed against federalreserve.gov / bls.gov as of
    // Aug 2026) rather than padded with guessed dates for other years — a date outside this
    // table simply isn't flagged as a scheduled macro event (falls back to the NFP weekday rule
    // and the shock/funding-crowding signals below), which is a strictly safer failure mode than
    // a fabricated date pretending to be authoritative. Extend these sets as new schedules are
    // published.
    private static final Set<LocalDate> FOMC_DECISION_DAYS = Set.of(
            // 2025
            LocalDate.of(2025, 1, 29), LocalDate.of(2025, 3, 19), LocalDate.of(2025, 5, 7),
            LocalDate.of(2025, 6, 18), LocalDate.of(2025, 7, 30), LocalDate.of(2025, 9, 17),
            LocalDate.of(2025, 10, 29), LocalDate.of(2025, 12, 10),
            // 2026
            LocalDate.of(2026, 1, 28), LocalDate.of(2026, 3, 18), LocalDate.of(2026, 4, 29),
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 7, 29), LocalDate.of(2026, 9, 16),
            LocalDate.of(2026, 10, 28), LocalDate.of(2026, 12, 9)
    );

    private static final Set<LocalDate> CPI_RELEASE_DAYS = Set.of(
            // 2025 — confirmed subset only; a few early/shutdown-shuffled 2025 dates aren't
            // confirmed and are deliberately left out rather than guessed.
            LocalDate.of(2025, 2, 12), LocalDate.of(2025, 6, 11), LocalDate.of(2025, 9, 11),
            LocalDate.of(2025, 10, 24), LocalDate.of(2025, 12, 18),
            // 2026 — full official BLS schedule
            LocalDate.of(2026, 1, 13), LocalDate.of(2026, 2, 13), LocalDate.of(2026, 3, 11),
            LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 12), LocalDate.of(2026, 6, 10),
            LocalDate.of(2026, 7, 14), LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 10)
    );

    public EventRiskContext analyze(List<Candle> candles,
                                    MarketMetrics metrics,
                                    DerivativesContext derivatives) {
        if (candles == null || candles.isEmpty()) return EventRiskContext.NONE;
        Candle last = candles.get(candles.size() - 1);
        ZonedDateTime ny = Instant.ofEpochMilli(last.time).atZone(NEW_YORK);
        EventWindow window = macroWindow(ny);

        boolean shock = marketShock(candles, metrics);
        boolean crowded = derivatives != null && derivatives.available
                && Math.abs(derivatives.fundingRate) >= 0.00070;

        int score = 10;
        if (window.active) score += 58;
        else if (window.minutesToEvent >= 0 && window.minutesToEvent <= 90) score += 35;
        if (shock) score += 28;
        if (crowded) score += 14;
        if (derivatives != null && derivatives.openInterestFallingFast()) score += 12;
        score = Math.max(0, Math.min(100, score));

        String label;
        if (window.active) label = "macro event window";
        else if (shock) label = "market shock";
        else if (crowded) label = "funding crowding";
        else label = "normal event risk";

        String timing = "";
        if (window.minutesToEvent >= 0) {
            timing = window.signedMinutes < 0
                    ? " · T+" + Math.abs(window.signedMinutes) + "m"
                    : " · T-" + window.minutesToEvent + "m";
        }
        String message = label
                + (window.name.isBlank() ? "" : " · " + window.name)
                + timing
                + (shock ? " · abnormal candle impulse" : "")
                + (crowded ? " · crowded funding" : "");
        return new EventRiskContext(true, score, window.minutesToEvent, window.active,
                shock, crowded, label, message);
    }

    private EventWindow macroWindow(ZonedDateTime ny) {
        LocalDate date = ny.toLocalDate();
        LocalTime time = ny.toLocalTime();
        EventWindow best = EventWindow.none();

        best = closer(best, scheduled(ny, "CPI 08:30", LocalTime.of(8, 30),
                CPI_RELEASE_DAYS.contains(date)));
        best = closer(best, scheduled(ny, "NFP 08:30", LocalTime.of(8, 30),
                isLikelyNfpMorning(date)));
        best = closer(best, scheduled(ny, "FOMC / Fed 14:00", LocalTime.of(14, 0),
                FOMC_DECISION_DAYS.contains(date)));
        best = closer(best, scheduled(ny, "Fed press / volatility 14:30", LocalTime.of(14, 30),
                FOMC_DECISION_DAYS.contains(date)));

        if (time.isAfter(LocalTime.of(9, 25)) && time.isBefore(LocalTime.of(9, 45))) {
            best = closer(best, new EventWindow("US cash open", 0, true));
        }
        return best;
    }

    private EventWindow scheduled(ZonedDateTime now, String name, LocalTime eventTime, boolean likelyDay) {
        if (!likelyDay) return EventWindow.none();
        ZonedDateTime event = now.with(eventTime);
        long minutes = java.time.Duration.between(now, event).toMinutes();
        long abs = Math.abs(minutes);
        boolean active = abs <= 35;
        if (minutes < -120 || minutes > 180) return EventWindow.none();
        return new EventWindow(name, (int) Math.max(0, minutes), (int) minutes, active);
    }

    private EventWindow closer(EventWindow a, EventWindow b) {
        if (b == null || b.minutesToEvent < 0) return a;
        if (a == null || a.minutesToEvent < 0) return b;
        if (b.active && !a.active) return b;
        if (a.active && !b.active) return a;
        return b.minutesToEvent < a.minutesToEvent ? b : a;
    }

    /**
     * NFP (BLS "Employment Situation") isn't in the hardcoded table above: unlike CPI/FOMC it's
     * reliably anchored to a weekday rule (first Friday of the month) rather than a scattered
     * day-of-month, so a rule tracks the real schedule well without needing a maintained list.
     * It occasionally slips to the second Friday when the reference week falls awkwardly; that
     * residual miss rate is small and, unlike the old CPI/FOMC rules, this one is at least
     * anchored to the right day-of-week logic instead of an arbitrary date range.
     */
    private boolean isLikelyNfpMorning(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.FRIDAY && date.getDayOfMonth() <= 7;
    }

    private boolean marketShock(List<Candle> candles, MarketMetrics metrics) {
        if (candles.size() < 12 || metrics == null) return false;
        Candle last = candles.get(candles.size() - 1);
        double atr = Math.max(metrics.latestAtr(), avgRange(candles, 30));
        double avgVol = avgVolume(candles, 30);
        return atr > 0.0 && last.range() >= atr * 2.8
                && (last.bodyPercent() >= 0.62 || (avgVol > 0.0 && last.volume >= avgVol * 2.4));
    }

    private double avgRange(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            sum += candles.get(i).range();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double avgVolume(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            sum += Math.max(0.0, candles.get(i).volume);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static class EventWindow {
        final String name;
        final int minutesToEvent;
        final int signedMinutes;
        final boolean active;

        EventWindow(String name, int minutesToEvent, boolean active) {
            this(name, minutesToEvent, minutesToEvent, active);
        }

        EventWindow(String name, int minutesToEvent, int signedMinutes, boolean active) {
            this.name = name == null ? "" : name;
            this.minutesToEvent = minutesToEvent;
            this.signedMinutes = signedMinutes;
            this.active = active;
        }

        static EventWindow none() {
            return new EventWindow("", -1, false);
        }
    }
}
