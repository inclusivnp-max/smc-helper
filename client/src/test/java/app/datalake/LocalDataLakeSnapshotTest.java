package app.datalake;

import app.derivatives.DerivativesContext;
import app.derivatives.DerivativesEngine;
import app.orderbook.OrderBookContext;
import app.orderbook.OrderBookEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the historical depth_l2/derivatives replay path against the real fixture snapshots
 * already committed under data-lake/ (BTCUSDT, 2026-05-24) rather than synthetic data, so this
 * verifies the actual on-disk format is parsed correctly, not just an assumption about it.
 */
class LocalDataLakeSnapshotTest {
    private static final String SYMBOL = "BTCUSDT";
    private static final String MARKET = "FUTURES";
    // First depth_l2/derivatives snapshot times for BTCUSDT on 2026-05-24 (verified against the
    // committed fixture files directly, see the file's first data row).
    private static final long DEPTH_SNAPSHOT_TIME = 1779641309182L;
    private static final long DERIVATIVES_SNAPSHOT_TIME = 1779641309538L;
    private static final long THIRTY_MIN_MS = 30 * 60_000L;

    @Test
    void loadsNearestPastDepthSnapshot() {
        LocalDataLake.DepthSnapshotResult result = LocalDataLake.loadNearestDepthSnapshot(
                SYMBOL, MARKET, DEPTH_SNAPSHOT_TIME + 60_000L, THIRTY_MIN_MS);
        assertTrue(result.hit, "expected a depth snapshot hit within the staleness window");
        assertEquals(DEPTH_SNAPSHOT_TIME, result.snapshotTime);
        assertFalse(result.bids.isEmpty(), "expected parsed bid levels");
        assertFalse(result.asks.isEmpty(), "expected parsed ask levels");
        for (var level : result.bids) {
            assertTrue(level.price > 0.0, "bid price should be positive");
            assertTrue(level.quantity >= 0.0, "bid quantity should be non-negative");
        }
    }

    @Test
    void neverReturnsASnapshotFromTheFuture() {
        // One millisecond before the day's first snapshot: nothing qualifies as "strictly past."
        LocalDataLake.DepthSnapshotResult result = LocalDataLake.loadNearestDepthSnapshot(
                SYMBOL, MARKET, DEPTH_SNAPSHOT_TIME - 1L, THIRTY_MIN_MS);
        assertFalse(result.hit, "must not look ahead to a snapshot taken after targetTime");
    }

    @Test
    void respectsStalenessWindow() {
        // Same snapshot, but the staleness budget is far too small to reach it.
        LocalDataLake.DepthSnapshotResult result = LocalDataLake.loadNearestDepthSnapshot(
                SYMBOL, MARKET, DEPTH_SNAPSHOT_TIME + THIRTY_MIN_MS * 10, THIRTY_MIN_MS);
        assertFalse(result.hit, "a snapshot far outside the staleness window must not count");
    }

    @Test
    void unknownSymbolMisses() {
        LocalDataLake.DepthSnapshotResult result = LocalDataLake.loadNearestDepthSnapshot(
                "NOSUCHSYMBOL", MARKET, DEPTH_SNAPSHOT_TIME + 60_000L, THIRTY_MIN_MS);
        assertFalse(result.hit);
        LocalDataLake.DerivativesSnapshotResult derivatives = LocalDataLake.loadNearestDerivativesSnapshot(
                "NOSUCHSYMBOL", MARKET, DERIVATIVES_SNAPSHOT_TIME + 60_000L, THIRTY_MIN_MS);
        assertFalse(derivatives.hit);
    }

    @Test
    void loadsNearestPastDerivativesSnapshot() {
        // +5s, not +60s: the next real derivatives snapshot lands ~35s later, so a wider window
        // would legitimately (and correctly) pick that closer one instead of this fixture's first.
        LocalDataLake.DerivativesSnapshotResult result = LocalDataLake.loadNearestDerivativesSnapshot(
                SYMBOL, MARKET, DERIVATIVES_SNAPSHOT_TIME + 5_000L, THIRTY_MIN_MS);
        assertTrue(result.hit, "expected a derivatives snapshot hit within the staleness window");
        assertEquals(DERIVATIVES_SNAPSHOT_TIME, result.snapshotTime);
        assertTrue(result.openInterest > 0.0, "expected a positive open interest reading");
    }

    @Test
    void orderBookEngineHistoricalReplayProducesAvailableContext() {
        OrderBookContext context = new OrderBookEngine().analyzeHistorical(
                SYMBOL, MARKET, 76546.7, DEPTH_SNAPSHOT_TIME + 60_000L);
        assertTrue(context.available, "expected historical DOM context to resolve from the fixture");
        assertTrue(context.bidNotional > 0.0 || context.askNotional > 0.0);
    }

    @Test
    void orderBookEngineHistoricalReplayMissesGracefully() {
        OrderBookContext context = new OrderBookEngine().analyzeHistorical(
                SYMBOL, MARKET, 76546.7, DEPTH_SNAPSHOT_TIME - 1L);
        assertFalse(context.available, "no snapshot exists before the fixture's first one");
    }

    @Test
    void derivativesEngineHistoricalReplayProducesAvailableContext() {
        DerivativesContext context = new DerivativesEngine().analyzeHistorical(
                SYMBOL, MARKET, DERIVATIVES_SNAPSHOT_TIME + 60_000L);
        assertTrue(context.available, "expected historical derivatives context to resolve from the fixture");
        assertTrue(context.openInterest > 0.0);
    }
}
