package app.execution.fix;

import app.model.TradePlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

public class ExecutionSafetySelfTest {
    public static void main(String[] args) throws Exception {
        testPartialFillCreatesProtectivePlan();
        testPaperSnapshotTracksMarginAndRealizedPnl();
        testClosedTradeJournalWritesCsvAndXlsx();
        testRiskGateDailyLoss();
        testDuplicateEntryOrderIsBlocked();
        testMaxTradesPerDayTripsKillSwitch();
        testMarketNormalizationKeepsReferencePrice();
        testGatewayExceptionBecomesRejectedReport();
    }

    private static void testPartialFillCreatesProtectivePlan() throws Exception {
        PaperFixGateway gateway = new PaperFixGateway("PAPER-TEST");
        RiskGate riskGate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                1_000_000.0, 1_000_000.0, 100.0, 100.0);
        ExecutionJournal journal = new ExecutionJournal(Files.createTempDirectory("smc-execution-journal-test"));
        HftExecutionService service = new HftExecutionService(gateway, riskGate, journal);
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                90, true, "self-test");

        ExecutionReport accepted = service.submitEntry("ETHUSDT", plan, 10.0);
        assertState(accepted.status == ExecutionReport.Status.ACCEPTED, "entry should be accepted");

        ExecutionReport partial = gateway.simulateFill(accepted.clientOrderId, 3.0, 100.0);
        service.onExecutionReport(partial);

        ProtectiveOrderPlan protective = service.protectivePlan("ETHUSDT");
        assertState(protective != null && Math.abs(protective.filledQuantity - 3.0) < 0.00000001,
                "partial fill should create protective plan for filled quantity");
        assertState(service.localOpenOrders().stream().anyMatch(o -> o.protectiveOrder()),
                "protective orders should be registered locally");
    }

    private static void testPaperSnapshotTracksMarginAndRealizedPnl() throws Exception {
        PaperFixGateway gateway = new PaperFixGateway("PAPER-TEST");
        RiskGate riskGate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                1_000_000.0, 1_000_000.0, 100.0, 100.0);
        ExecutionJournal journal = new ExecutionJournal(Files.createTempDirectory("smc-paper-account-test"));
        HftExecutionService service = new HftExecutionService(gateway, riskGate, journal);
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                90, true, "self-test lev=10");

        ExecutionReport accepted = service.submitEntry("ETHUSDT", plan, 2.0);
        service.onExecutionReport(gateway.simulateFill(accepted.clientOrderId, 2.0, 100.0));

        AccountSnapshot open = journal.replayedAccountSnapshot("PAPER-TEST", Map.of("ETHUSDT", 110.0), 1_000.0);
        assertNear(open.unrealizedPnl, 20.0, "open paper uPnL should use mark price");
        assertNear(open.equity, 1_020.0, "open paper equity should include uPnL");
        assertNear(open.availableBalance, 980.0, "open paper available should reserve leveraged margin");

        journal.recordManualPositionClose("ETHUSDT", true, 100.0, 2.0, 110.0, "self-test close");
        AccountSnapshot closed = journal.replayedAccountSnapshot("PAPER-TEST", Map.of("ETHUSDT", 110.0), 1_000.0);
        assertState(closed.openPositions.isEmpty(), "closed paper position should not remain open");
        assertNear(closed.walletBalance, 1_020.0, "closed paper wallet should include realized PnL");
        assertNear(closed.availableBalance, 1_020.0, "closed paper available should release margin");
    }

    private static void testClosedTradeJournalWritesCsvAndXlsx() throws Exception {
        Path dir = Files.createTempDirectory("smc-closed-trades-test");
        ClosedTradeJournal journal = new ClosedTradeJournal(dir);
        journal.record(new ClosedTradeJournal.Record(
                "26.май", "12:00:00", "ETHUSDT", "Long",
                100.0, 2.0, 105.0, 110.0, 115.0, 95.0,
                110.0, 20.0, 10.0, "Зашла", 83,
                "self-test", "manual", "5m", "Paper local",
                "PAPER-TEST", "self-test", "London", true,
                "2026-05-26 12:00:00", 1));
        assertState(Files.exists(journal.csvPath()), "closed trade CSV should be written");
        assertState(Files.exists(journal.xlsxPath()), "closed trade XLSX should be written");
        try (ZipFile zip = new ZipFile(journal.xlsxPath().toFile())) {
            assertState(zip.getEntry("xl/worksheets/sheet1.xml") != null,
                    "closed trade XLSX should contain sheet1.xml");
        }
    }

    private static void testRiskGateDailyLoss() {
        RiskGate gate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                100.0, 1_000_000.0, 100.0, 100.0);
        gate.recordRealizedPnl(-120.0);
        assertState(gate.status().killSwitchTripped, "daily loss should trip kill switch");
    }

    private static void testDuplicateEntryOrderIsBlocked() throws Exception {
        PaperFixGateway gateway = new PaperFixGateway("PAPER-DUP-TEST");
        RiskGate riskGate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                1_000_000.0, 1_000_000.0, 100.0, 100.0,
                0.0, 0, 0, 0, 60);
        ExecutionJournal journal = new ExecutionJournal(Files.createTempDirectory("smc-duplicate-order-test"));
        HftExecutionService service = new HftExecutionService(gateway, riskGate, journal);
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                90, true, "self-test");
        ExecutionReport first = service.submitEntry("ETHUSDT", plan, 1.0);
        ExecutionReport second = service.submitEntry("ETHUSDT", plan, 1.0);
        assertState(first.status == ExecutionReport.Status.ACCEPTED, "first entry should be accepted");
        assertState(second.status == ExecutionReport.Status.REJECTED
                        && second.message.contains("Order state lock"),
                "duplicate entry should be blocked by order state lock");
    }

    private static void testMaxTradesPerDayTripsKillSwitch() throws Exception {
        PaperFixGateway gateway = new PaperFixGateway("PAPER-RISK-TEST");
        RiskGate riskGate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                1_000_000.0, 1_000_000.0, 100.0, 100.0,
                0.0, 1, 0, 0, 0);
        ExecutionJournal journal = new ExecutionJournal(Files.createTempDirectory("smc-max-trades-test"));
        HftExecutionService service = new HftExecutionService(gateway, riskGate, journal);
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                90, true, "self-test");
        ExecutionReport first = service.submitEntry("ETHUSDT", plan, 1.0);
        ExecutionReport second = service.submitEntry("BNBUSDT", plan, 1.0);
        assertState(first.status == ExecutionReport.Status.ACCEPTED, "first daily trade should be accepted");
        assertState(second.status == ExecutionReport.Status.REJECTED
                        && second.message.contains("KILL SWITCH"),
                "second daily trade should be blocked by max trades kill switch");
    }

    private static void testMarketNormalizationKeepsReferencePrice() throws Exception {
        InstrumentSpec spec = new InstrumentSpec("TEST", "BNBUSDT",
                0.01, 0.01, 0.01, 10_000.0, 5.0, 3, 2, System.currentTimeMillis());
        ExecutionOrder order = new ExecutionOrder("T-MARKET", "BNBUSDT",
                ExecutionOrder.Side.BUY, ExecutionOrder.Type.MARKET, ExecutionOrder.TimeInForce.IOC,
                0.151607, 659.637, 0.0, "self-test");
        OrderNormalization normalization = OrderNormalizer.normalize(order, spec);
        assertNear(normalization.order.quantity, 0.15, "market quantity should follow symbol step");
        assertNear(normalization.order.price, 659.637, "market reference price should not be tick-rounded");
    }

    private static void testGatewayExceptionBecomesRejectedReport() throws Exception {
        ExecutionGateway failing = new ExecutionGateway() {
            @Override
            public ExecutionReport send(ExecutionOrder order) throws Exception {
                throw new java.io.IOException("simulated gateway failure");
            }

            @Override
            public ExecutionReport cancel(String clientOrderId) {
                return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.CANCELLED,
                        0.0, 0.0, "cancelled");
            }

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public String venue() {
                return "FAILING-TEST";
            }
        };
        RiskGate riskGate = new RiskGate(1_000_000.0, 1_000_000.0, 1, false,
                1_000_000.0, 1_000_000.0, 100.0, 100.0);
        ExecutionJournal journal = new ExecutionJournal(Files.createTempDirectory("smc-gateway-failure-test"));
        HftExecutionService service = new HftExecutionService(failing, riskGate, journal);
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                90, true, "self-test");
        ExecutionReport report = service.submitEntry("ETHUSDT", plan, 1.0,
                ExecutionOrder.TimeInForce.IOC, ExecutionOrder.Type.MARKET);
        assertState(report.status == ExecutionReport.Status.REJECTED,
                "gateway exception should be returned as a rejected execution report");
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertNear(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 0.000001) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }
}
