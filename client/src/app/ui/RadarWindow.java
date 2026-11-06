package app.ui;

import app.common.UiSettings;
import app.common.I18n;
import app.backtest.RadarBacktestStats;
import app.logic.BinanceClient;
import app.logic.SMCAnalyzer;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.TradePlan;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static app.Colors.*;

public class RadarWindow extends JDialog {

    private static final int HTF_LIMIT = 200;
    private static final int LTF_LIMIT = 500;
    // Scales down on modest hardware instead of always claiming 4 threads: on a 2-core machine 4
    // busy analysis workers can genuinely starve the EDT of CPU (lower thread priority alone only
    // resolves scheduling PREFERENCE under contention, not the case where there simply aren't
    // enough cores to go around). Always keeps at least one core's worth of headroom for the UI.
    private static final int RADAR_WORKERS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    private static final UiSettings SETTINGS = UiSettings.get();
    private static final String FILTER_PREF = "radar.filters.";
    private static final String[][] DEFAULT_PAIRS = {
            {"1h", "5m"},
            {"1h", "15m"},
            {"4h", "5m"},
            {"4h", "15m"},
            {"4h", "1h"},
            {"1d", "15m"},
            {"1d", "1h"}
    };
    private static final List<AlertItem> SAVED_ALERTS = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> SAVED_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile RadarFilters SAVED_FILTERS = loadSavedFilters();
    private static final double DIRECTION_CONFLICT_GAP = 12.0;

    private final DefaultListModel<AlertItem> listModel = new DefaultListModel<>();
    private final JList<AlertItem> alertList = new JList<>(listModel);
    private final JLabel statusLbl = new JLabel("Ожидание запуска...");
    private final JLabel filterBadge = new JLabel();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private final String[] symbols;
    private final String method;
    private final BinanceClient.Market market;
    private final TimeframePair[] pairs;
    private final Consumer<AlertItem> onSignalSelect;

    private Thread scannerThread;
    private volatile ExecutorService scannerExecutor;
    private volatile boolean isRunning = false;
    private volatile String lastScanHint = "";
    private final Map<String, Double> conflictQualityBySymbol = new ConcurrentHashMap<>();

    public static class AlertItem {
        public String time;
        public String symbol;
        public String htf;
        public String ltf;
        public String method;
        public BinanceClient.Market market;
        public String direction;
        public String zoneType;
        public String modelBadge = "";
        public String modelName = "";
        public String modelSummary = "";
        public int modelRank;
        public boolean highQualityModel;
        public boolean liquidityToLiquidity;
        public boolean premiumDiscountAligned;
        public String status;
        public double currentPrice;
        public double entry;
        public double distancePct;
        public int score;
        public int passedFactors;
        public int requiredFactors;
        public String filterSummary = "";
        public String statsSummary = "";
        public int statsTrades;
        public double statsExpectancyR;
        public double statsWinrate;
        public boolean longSide;
        public boolean confirmed;
        public boolean stale;
        public TradePlan planSnapshot;

        String key() {
            long entryBucket = Math.round(entry * 1_000_000.0);
            String marketKey = market == null ? "" : market.label;
            return marketKey + "|" + symbol + "|" + htf + "|" + ltf + "|" + method + "|" + direction + "|" + entryBucket;
        }

        String stableKey() {
            String marketKey = market == null ? "" : market.label;
            return marketKey + "|" + symbol + "|" + htf + "|" + ltf + "|" + method + "|" + direction;
        }

        String symbolKey() {
            String marketKey = market == null ? "" : market.label;
            return marketKey + "|" + (symbol == null ? "" : symbol.toUpperCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            String model = modelBadge == null || modelBadge.isBlank() ? "" : " · " + modelBadge;
            return String.format("%s · %s/%s · %s%s · SMC %d · вход %.4f",
                    symbol, htf, ltf, direction, model, score, entry);
        }
    }

    public RadarWindow(JFrame parent,
                       String[] symbols,
                       String preferredHtf,
                       String preferredLtf,
                       String method,
                       BinanceClient.Market market,
                       Consumer<AlertItem> onSignalSelect) {
        super(parent, I18n.t("Радар входов"), false);
        this.symbols = symbols.clone();
        this.method = method;
        this.market = market;
        this.pairs = buildPairs(preferredHtf, preferredLtf);
        this.onSignalSelect = onSignalSelect;

        setSize(640, 620);
        setMinimumSize(new Dimension(560, 500));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(true);
        root.setBackground(PANEL_BG);
        setContentPane(root);

        root.add(buildTop(), BorderLayout.NORTH);
        root.add(buildList(), BorderLayout.CENTER);
        root.add(buildBottom(), BorderLayout.SOUTH);
        restoreSavedAlerts();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopScanning();
            }
        });
    }

    public void refreshLanguage() {
        setTitle(I18n.t("Радар входов"));
        I18n.refreshTree(this);
        updateFilterBadge();
        repaint();
    }

    private JPanel buildTop() {
        JPanel top = new JPanel(new BorderLayout(14, 0));
        top.setBackground(PANEL_BG);
        top.setBorder(new EmptyBorder(14, 14, 12, 14));

        JLabel title = new JLabel(I18n.t("Радар входов"));
        title.putClientProperty("i18n.source", "Радар входов");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(19f));

        JLabel subtitle = new JLabel(I18n.english()
                ? "Market: " + market.label + " · scanning HTF/LTF pairs"
                : "Рынок: " + market.label + " · сканирует HTF/LTF связки");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));

        filterBadge.setForeground(YELLOW);
        filterBadge.setFont(UIUtils.labelFont(11.8f));
        updateFilterBadge();

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subtitle);
        titleBox.add(Box.createVerticalStrut(5));
        titleBox.add(filterBadge);

        JButton filtersBtn = new JButton(I18n.t("Фильтры"));
        filtersBtn.putClientProperty("i18n.source", "Фильтры");
        UIUtils.stylePrimaryButton(filtersBtn, ACCENT);
        filtersBtn.addActionListener(e -> openFiltersDialog());

        JToggleButton powerBtn = new JToggleButton(I18n.t("Запустить"));
        powerBtn.putClientProperty("i18n.source", "Запустить");
        UIUtils.stylePrimaryButton(powerBtn, BULL);
        powerBtn.addActionListener(e -> {
            if (powerBtn.isSelected()) {
                powerBtn.setText(I18n.t("Остановить"));
                powerBtn.setBackground(BEAR);
                startScanning();
            } else {
                powerBtn.setText(I18n.t("Запустить"));
                powerBtn.setBackground(BULL);
                stopScanning();
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(filtersBtn);
        actions.add(powerBtn);

        top.add(titleBox, BorderLayout.CENTER);
        top.add(actions, BorderLayout.EAST);
        return top;
    }

    private JScrollPane buildList() {
        alertList.setOpaque(true);
        alertList.setBackground(BG);
        alertList.setForeground(FG);
        alertList.setFont(UIUtils.bodyFont(13f));
        alertList.setSelectionBackground(new Color(33, 76, 130));
        alertList.setSelectionForeground(Color.WHITE);
        alertList.setCellRenderer(new AlertRenderer());
        alertList.setVisibleRowCount(10);

        alertList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() >= 1) openSelectedSignal();
            }
        });
        alertList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) openSelectedSignal();
            }
        });

        JScrollPane scroll = new JScrollPane(alertList);
        UIUtils.styleScrollPane(scroll, GRID);
        scroll.setOpaque(true);
        scroll.setBackground(BG);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(BG);
        scroll.setViewportBorder(null);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 14, 0, 14, BG));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout(10, 8));
        bottom.setBackground(PANEL_BG);
        bottom.setBorder(new EmptyBorder(10, 14, 14, 14));

        statusLbl.setForeground(MUTED);
        statusLbl.setFont(UIUtils.bodyFont(12.4f));

        progressBar.setValue(0);
        progressBar.setString("0%");
        progressBar.setStringPainted(true);
        progressBar.setForeground(BULL);
        progressBar.setBackground(BG);
        progressBar.setBorder(BorderFactory.createLineBorder(GRID));
        progressBar.setFont(UIUtils.labelFont(11f));
        progressBar.setUI(new ModernProgressBarUI());
        progressBar.setPreferredSize(new Dimension(0, 18));
        progressBar.setVisible(false);

        JButton openBtn = new JButton(I18n.t("Открыть сетап"));
        openBtn.putClientProperty("i18n.source", "Открыть сетап");
        UIUtils.stylePrimaryButton(openBtn, ACCENT);
        openBtn.addActionListener(e -> openSelectedSignal());

        JButton refreshBtn = new JButton(I18n.t("Обновить текущий список"));
        refreshBtn.putClientProperty("i18n.source", "Обновить текущий список");
        UIUtils.stylePrimaryButton(refreshBtn, BULL);
        refreshBtn.addActionListener(e -> refreshOnce());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(refreshBtn);
        buttons.add(openBtn);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.add(statusLbl, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);

        JLabel legendLbl = new JLabel(
                "<html><span style='color:#26a641;'>&#9646; A+</span> " +
                "<span style='color:#3fb950;'>&#9646; A</span> — зелёный &nbsp;·&nbsp; " +
                "<span style='color:#d29922;'>&#9646; B</span> — жёлтый &nbsp;·&nbsp; " +
                "<span style='color:#8b949e;'>&#9646; C</span> — серый" +
                "&nbsp;&nbsp;&nbsp;<i style='color:#6e7681;'>Полоска слева = рейтинг сигнала " +
                "(A+: confidence≥78 + R:R≥2.5 + displacement≥65)</i></html>");
        legendLbl.setFont(UIUtils.captionFont(11f));
        legendLbl.setBorder(new EmptyBorder(6, 0, 2, 0));

        bottom.add(row, BorderLayout.NORTH);
        bottom.add(legendLbl, BorderLayout.CENTER);
        bottom.add(progressBar, BorderLayout.SOUTH);
        return bottom;
    }

    private void startScanning() {
        if (isRunning) return;
        isRunning = true;
        clearAlertsForFreshScan();
        updateRadarProgress(1, "Запускаю полный скан · " + activeSavedFilters().summary());

        scannerThread = new Thread(this::scanLoop, "SMC-Radar-Scanner");
        scannerThread.setDaemon(true);
        // Below-normal, same reasoning as newRadarExecutor()'s worker threads -- this thread
        // mostly just dispatches/waits on the worker pool, but keeping it a notch below the EDT
        // too avoids it becoming the tie-break winner over UI work on a busy machine.
        scannerThread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        scannerThread.start();
    }

    private void clearAlertsForFreshScan() {
        synchronized (SAVED_ALERTS) {
            SAVED_ALERTS.clear();
            SAVED_KEYS.clear();
        }
        seenKeys.clear();
        conflictQualityBySymbol.clear();
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            alertList.clearSelection();
            setStatus("Список очищен. Запускаю новый полный скан...");
        });
    }

    private void refreshOnce() {
        if (isRunning) {
            setStatus("Сканирование уже идет...");
            return;
        }
        List<AlertItem> snapshot = savedSnapshot();
        if (snapshot.isEmpty()) {
            setStatus("Список пуст. Нажми «Запустить», чтобы найти новые сетапы.");
            return;
        }
        isRunning = true;
        updateRadarProgress(1, "Обновляю текущий список · " + activeSavedFilters().summary());
        scannerThread = new Thread(() -> {
            try {
                RefreshStats stats = refreshExistingAlerts(snapshot);
                updateRadarProgress(100, "Обновлено: " + stats.updated + " · устарело: " + stats.stale + " · ошибок: " + stats.errors);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                updateRadarProgress(0, "Остановлено.");
            } finally {
                isRunning = false;
            }
        }, "SMC-Radar-Refresh");
        scannerThread.setDaemon(true);
        scannerThread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        scannerThread.start();
    }

    private void scanLoop() {
        while (isRunning) {
            int foundThisCycle;
            try {
                foundThisCycle = scanOneCycle();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                updateRadarProgress(0, "Остановлено.");
                return;
            }

            if (isRunning) {
                String hint = lastScanHint == null || lastScanHint.isBlank() ? "" : ". " + lastScanHint;
                updateRadarProgress(100, "Цикл завершен. Новых сетапов: " + foundThisCycle + hint + ". Пауза 90 сек.");
                try {
                    sleepInterruptibly(90_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    updateRadarProgress(0, "Остановлено.");
                    return;
                }
            }
        }
        updateRadarProgress(0, "Остановлено.");
    }

    private int scanOneCycle() throws InterruptedException {
        RadarFilters cycleFilters = activeSavedFilters().copy();
        List<ScanJob> jobs = new ArrayList<>();
        int index = 0;
        for (String symbol : symbols) {
            for (TimeframePair pair : pairs) {
                jobs.add(new ScanJob(symbol, pair, ++index));
            }
        }
        int total = jobs.size();
        int foundThisCycle = 0;
        int bestRejectedScore = -1;
        String bestRejectedHint = "";
        int errors = 0;

        ExecutorService executor = newRadarExecutor("SMC-Radar-Scan-Worker");
        scannerExecutor = executor;
        CompletionService<ScanOutcome> completion = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        try {
            for (ScanJob job : jobs) {
                if (!isRunning) break;
                completion.submit(() -> scanJob(job, cycleFilters));
                submitted++;
            }
            updateRadarProgress(1, "Скан: " + submitted + " задач · " + RADAR_WORKERS
                    + " worker · limiter " + BinanceClient.rateLimiterStatus());

            int completed = 0;
            while (completed < submitted && isRunning) {
                Future<ScanOutcome> future = completion.poll(350, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                completed++;
                int percent = Math.max(1, Math.min(99, (int) Math.round(completed * 100.0 / Math.max(1, submitted))));

                ScanOutcome outcome;
                try {
                    outcome = future.get();
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    errors++;
                    updateRadarProgress(percent, "Ошибка задачи радара · " + completed + "/" + submitted);
                    continue;
                }

                if (outcome.interrupted) throw new InterruptedException();
                if (outcome.error) {
                    errors++;
                    updateRadarProgress(percent, "Ошибка API: " + outcome.symbol + " · "
                            + outcome.pair.htf + "/" + outcome.pair.ltf + " · " + completed + "/" + submitted);
                    continue;
                }

                AlertItem alert = outcome.review.alert;
                AddOutcome addOutcome = alert == null ? AddOutcome.skipped("") : offerAlert(alert);
                if (addOutcome.accepted) {
                    foundThisCycle++;
                    String model = alert.modelBadge == null || alert.modelBadge.isBlank() ? "" : " · " + alert.modelBadge;
                    updateRadarProgress(percent, "Найден сетап: " + alert.symbol + " · "
                            + alert.htf + "/" + alert.ltf + model + " · SMC " + alert.score
                            + " · " + completed + "/" + submitted);
                } else if (addOutcome.conflict) {
                    updateRadarProgress(percent, addOutcome.message + " · " + completed + "/" + submitted);
                } else if (outcome.review.hasPlan && outcome.review.score > bestRejectedScore) {
                    bestRejectedScore = outcome.review.score;
                    String addReason = addOutcome.message == null || addOutcome.message.isBlank()
                            ? outcome.review.reason
                            : addOutcome.message;
                    bestRejectedHint = outcome.symbol + " " + outcome.pair.htf + "/" + outcome.pair.ltf
                            + " SMC " + outcome.review.score + " (" + addReason + ")";
                    updateRadarProgress(percent, "Проверено " + outcome.symbol + " · "
                            + outcome.pair.htf + "/" + outcome.pair.ltf + " · " + completed + "/" + submitted
                            + " · limiter " + BinanceClient.rateLimiterStatus());
                } else {
                    updateRadarProgress(percent, "Проверено " + outcome.symbol + " · "
                            + outcome.pair.htf + "/" + outcome.pair.ltf + " · " + completed + "/" + submitted
                            + " · limiter " + BinanceClient.rateLimiterStatus());
                }
            }
        } finally {
            executor.shutdownNow();
            if (scannerExecutor == executor) scannerExecutor = null;
        }
        String errorHint = errors > 0 ? "Ошибок API: " + errors + ". " + BinanceClient.rateLimiterStatus() : "";
        if (foundThisCycle == 0 && bestRejectedScore >= 0) {
            lastScanHint = "Лучший кандидат не прошел фильтр: " + bestRejectedHint
                    + (errorHint.isBlank() ? "" : ". " + errorHint);
        } else {
            lastScanHint = errorHint;
        }
        return foundThisCycle;
    }

    private ScanOutcome scanJob(ScanJob job, RadarFilters filters) {
        try {
            if (!isRunning || Thread.currentThread().isInterrupted()) return ScanOutcome.interrupted(job);
            List<Candle> htfCandles = BinanceClient.fetchCandles(job.symbol, job.pair.htf, HTF_LIMIT, market);
            List<Candle> ltfCandles = BinanceClient.fetchCandles(job.symbol, job.pair.ltf, LTF_LIMIT, market);
            AnalysisResult fast = SMCAnalyzer.analyze(job.symbol, job.pair.ltf, htfCandles, ltfCandles, method,
                    market, false, false);
            updateLivePrice(fast, job.symbol, market);
            CandidateReview fastReview = reviewCandidate(fast, job.pair, method, market, filters);
            if (!needsFullContext(fastReview, filters)) {
                return ScanOutcome.success(job, fastReview);
            }

            try {
                AnalysisResult full = SMCAnalyzer.analyze(job.symbol, job.pair.ltf, htfCandles, ltfCandles, method,
                        market, true, false);
                updateLivePrice(full, job.symbol, market);
                return ScanOutcome.success(job, reviewCandidate(full, job.pair, method, market, filters));
            } catch (Exception fullContextError) {
                if (fastReview.alert != null) {
                    fastReview.reason = "external context unavailable: " + safeMessage(fullContextError);
                    fastReview.alert.status = fastReview.alert.status + " · внешний контекст недоступен";
                    return ScanOutcome.success(job, fastReview);
                }
                throw fullContextError;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ScanOutcome.interrupted(job);
        } catch (Exception ex) {
            return ScanOutcome.error(job, ex.getMessage());
        }
    }

    private boolean needsFullContext(CandidateReview review, RadarFilters filters) {
        if (review == null || !review.hasPlan) return false;
        if (review.alert != null) return true;
        int nearScore = Math.max(50, filters.minScore - 8);
        return review.score >= nearScore
                && (filters.useImbalanceFilter || filters.useEventRiskFilter || filters.useVolatilityShockFilter);
    }

    private AlertItem buildAlert(AnalysisResult result, TimeframePair pair) {
        return buildAlert(result, pair, method, market);
    }

    private AlertItem buildAlert(AnalysisResult result, TimeframePair pair,
                                 String signalMethod, BinanceClient.Market signalMarket) {
        return buildAlert(result, pair, signalMethod, signalMarket, activeSavedFilters().copy());
    }

    private AlertItem buildAlert(AnalysisResult result, TimeframePair pair,
                                 String signalMethod, BinanceClient.Market signalMarket,
                                 RadarFilters filters) {
        return reviewCandidate(result, pair, signalMethod, signalMarket, filters).alert;
    }

    private CandidateReview reviewCandidate(AnalysisResult result, TimeframePair pair,
                                            String signalMethod, BinanceClient.Market signalMarket,
                                            RadarFilters activeFilters) {
        CandidateReview review = new CandidateReview();
        if (result == null || result.plan == null) {
            review.reason = "нет trade plan";
            return review;
        }
        TradePlan plan = result.plan;
        int score = radarScore(result, plan);
        review.hasPlan = true;
        review.score = score;
        if (!plan.entryConfirmed) {
            review.reason = "вход не подтвержден";
            return review;
        }
        if (stopAlreadyInvalidated(plan, result.currentPrice)) {
            review.reason = "stop invalidated";
            return review;
        }
        RadarBacktestStats.Snapshot stats = RadarBacktestStats.evaluate(result, plan);
        FilterMatch match = evaluateFilters(result, plan, score, activeFilters, stats);
        if (!match.passed) {
            review.reason = match.summary();
            return review;
        }

        AlertItem alert = new AlertItem();
        alert.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        alert.symbol = result.symbol;
        alert.htf = pair.htf;
        alert.ltf = pair.ltf;
        alert.method = signalMethod;
        alert.market = signalMarket;
        alert.longSide = plan.longSide;
        alert.direction = plan.longSide ? "ЛОНГ" : "ШОРТ";
        alert.zoneType = plan.zone == null ? "зона" : plan.zone.type;
        applyModelSnapshot(alert, plan);
        alert.currentPrice = result.currentPrice;
        alert.entry = plan.entry;
        alert.distancePct = remainingToEntryPct(plan.longSide, result.currentPrice, plan.entry);
        alert.score = score;
        alert.passedFactors = match.passedFactors;
        alert.requiredFactors = match.requiredFactors;
        alert.filterSummary = match.summary();
        alert.statsSummary = stats.summary();
        alert.statsTrades = stats.trades();
        alert.statsExpectancyR = stats.expectancyR();
        alert.statsWinrate = stats.winrate();
        alert.confirmed = plan.entryConfirmed;
        alert.stale = false;
        alert.planSnapshot = plan;
        alert.status = "вход подтвержден";
        review.alert = alert;
        review.reason = "passed";
        return review;
    }

    private RefreshStats refreshExistingAlerts(List<AlertItem> snapshot) throws InterruptedException {
        RadarFilters refreshFilters = activeSavedFilters().copy();
        List<AlertItem> refreshed = new ArrayList<>();
        RefreshStats stats = new RefreshStats();
        int total = snapshot.size();

        ExecutorService executor = newRadarExecutor("SMC-Radar-Refresh-Worker");
        scannerExecutor = executor;
        CompletionService<RefreshOutcome> completion = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        try {
            for (AlertItem item : snapshot) {
                if (!isRunning) break;
                completion.submit(() -> refreshJob(item, refreshFilters));
                submitted++;
            }

            int completed = 0;
            while (completed < submitted && isRunning) {
                Future<RefreshOutcome> future = completion.poll(350, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                completed++;
                int percent = Math.max(1, Math.min(99, (int) Math.round(completed * 100.0 / Math.max(1, total))));

                RefreshOutcome outcome;
                try {
                    outcome = future.get();
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    stats.errors++;
                    updateRadarProgress(percent, "Ошибка обновления · " + completed + "/" + total);
                    continue;
                }

                if (outcome.interrupted) throw new InterruptedException();
                if (outcome.error) {
                    stats.errors++;
                    updateRadarProgress(percent, "Ошибка обновления: " + outcome.source.symbol + " · "
                            + outcome.source.htf + "/" + outcome.source.ltf + " · " + completed + "/" + total);
                    continue;
                }
                if (outcome.updated.stale) {
                    stats.stale++;
                } else {
                    refreshed.add(outcome.updated);
                    stats.updated++;
                }
                updateRadarProgress(percent, "Обновляю список · " + completed + "/" + total
                        + " · ok " + stats.updated + " · stale " + stats.stale
                        + " · limiter " + BinanceClient.rateLimiterStatus());
            }
        } finally {
            executor.shutdownNow();
            if (scannerExecutor == executor) scannerExecutor = null;
        }

        if (isRunning) applyRefreshedAlerts(refreshed);
        return stats;
    }

    private RefreshOutcome refreshJob(AlertItem item, RadarFilters filters) {
        try {
            if (!isRunning || Thread.currentThread().isInterrupted()) return RefreshOutcome.interrupted(item);
            return RefreshOutcome.success(item, refreshAlert(item, filters));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return RefreshOutcome.interrupted(item);
        } catch (Exception ex) {
            return RefreshOutcome.error(item, ex.getMessage());
        }
    }

    private AlertItem refreshAlert(AlertItem old, RadarFilters filters) throws Exception {
        BinanceClient.Market signalMarket = old.market == null ? market : old.market;
        String signalMethod = old.method == null || old.method.isBlank() ? method : old.method;
        AnalysisResult result;
        List<Candle> htfCandles = BinanceClient.fetchCandles(old.symbol, old.htf, HTF_LIMIT, signalMarket);
        List<Candle> ltfCandles = BinanceClient.fetchCandles(old.symbol, old.ltf, LTF_LIMIT, signalMarket);
        result = SMCAnalyzer.analyze(old.symbol, old.ltf, htfCandles, ltfCandles, signalMethod,
                signalMarket, true, false);
        updateLivePrice(result, old.symbol, signalMarket);

        AlertItem fresh = buildAlert(result, new TimeframePair(old.htf, old.ltf), signalMethod, signalMarket, filters);
        if (fresh != null) return fresh;

        AlertItem stale = copyAlert(old);
        stale.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        stale.currentPrice = result.currentPrice;
        stale.confirmed = false;
        stale.stale = true;
        if (result.plan != null) {
            stale.entry = result.plan.entry;
            stale.longSide = result.plan.longSide;
            stale.direction = result.plan.longSide ? "ЛОНГ" : "ШОРТ";
            stale.zoneType = result.plan.zone == null ? stale.zoneType : result.plan.zone.type;
            applyModelSnapshot(stale, result.plan);
            stale.distancePct = remainingToEntryPct(result.plan.longSide, result.currentPrice, result.plan.entry);
            stale.score = radarScore(result, result.plan);
            RadarBacktestStats.Snapshot stats = RadarBacktestStats.evaluate(result, result.plan);
            FilterMatch match = evaluateFilters(result, result.plan, stale.score, filters, stats);
            stale.passedFactors = match.passedFactors;
            stale.requiredFactors = match.requiredFactors;
            stale.filterSummary = match.summary();
            stale.statsSummary = stats.summary();
            stale.statsTrades = stats.trades();
            stale.statsExpectancyR = stats.expectancyR();
            stale.statsWinrate = stats.winrate();
            stale.status = match.passed ? "ждет подтверждения" : "не проходит фильтр";
        } else {
            stale.distancePct = remainingToEntryPct(stale.longSide, result.currentPrice, stale.entry);
            stale.score = Math.min(Math.max(result.smcScore, 0), 55);
            stale.status = "сетап устарел";
        }
        return stale;
    }

    private AlertItem copyAlert(AlertItem source) {
        AlertItem copy = new AlertItem();
        copy.time = source.time;
        copy.symbol = source.symbol;
        copy.htf = source.htf;
        copy.ltf = source.ltf;
        copy.method = source.method;
        copy.market = source.market;
        copy.direction = source.direction;
        copy.zoneType = source.zoneType;
        copy.modelBadge = source.modelBadge;
        copy.modelName = source.modelName;
        copy.modelSummary = source.modelSummary;
        copy.modelRank = source.modelRank;
        copy.highQualityModel = source.highQualityModel;
        copy.liquidityToLiquidity = source.liquidityToLiquidity;
        copy.premiumDiscountAligned = source.premiumDiscountAligned;
        copy.status = source.status;
        copy.currentPrice = source.currentPrice;
        copy.entry = source.entry;
        copy.distancePct = source.distancePct;
        copy.score = source.score;
        copy.passedFactors = source.passedFactors;
        copy.requiredFactors = source.requiredFactors;
        copy.filterSummary = source.filterSummary;
        copy.statsSummary = source.statsSummary;
        copy.statsTrades = source.statsTrades;
        copy.statsExpectancyR = source.statsExpectancyR;
        copy.statsWinrate = source.statsWinrate;
        copy.longSide = source.longSide;
        copy.confirmed = source.confirmed;
        copy.stale = source.stale;
        copy.planSnapshot = source.planSnapshot;
        return copy;
    }

    private int radarScore(AnalysisResult result, TradePlan plan) {
        int score = Math.max(plan.confidence, result.smcScore);
        return Math.max(0, Math.min(100, score));
    }

    private static void applyModelSnapshot(AlertItem alert, TradePlan plan) {
        if (alert == null || plan == null || plan.zone == null) return;
        var zone = plan.zone;
        alert.modelBadge = modelBadge(plan);
        alert.modelName = zone.mechanicLabel();
        alert.modelSummary = modelSummary(plan);
        alert.modelRank = modelRank(plan);
        alert.highQualityModel = zone.highQualityModel;
        alert.liquidityToLiquidity = zone.liquidityToLiquidity;
        alert.premiumDiscountAligned = zone.premiumDiscountAligned;
    }

    private static String modelBadge(TradePlan plan) {
        if (plan == null || plan.zone == null) return "";
        var zone = plan.zone;
        String code = zone.mechanicShort();
        if (zone.highQualityModel && code != null && !code.isBlank() && !"A+".equals(code)) return "A+/" + code;
        if (zone.highQualityModel) return "A+";
        if (code != null && !code.isBlank()) return code;
        if (zone.liquidityToLiquidity) return "L2L";
        return "";
    }

    private static String modelSummary(TradePlan plan) {
        if (plan == null || plan.zone == null) return "нет smart-money модели";
        var zone = plan.zone;
        List<String> parts = new ArrayList<>();
        String badge = modelBadge(plan);
        if (!badge.isBlank()) parts.add(badge);
        if (zone.highQualityModel) parts.add("full model");
        if (zone.liquidityToLiquidity) parts.add("L2L");
        if (zone.premiumDiscountAligned) parts.add("PD ok");
        if (zone.targetLiquidity != null && !zone.targetLiquidity.isBlank()) parts.add(zone.targetLiquidity);
        return parts.isEmpty() ? zone.mechanicLabel() : String.join(" · ", parts);
    }

    private static int modelRank(TradePlan plan) {
        if (plan == null || plan.zone == null) return 0;
        var zone = plan.zone;
        int rank = Math.max(0, zone.mechanicsScore);
        if (zone.highQualityModel) rank += 1000;
        if (zone.breakerBlock) rank += 180;
        if (zone.inverted) rank += 165;
        if (zone.propulsionBlock) rank += 145;
        if (zone.mitigationBlock) rank += 110;
        if (zone.liquidityToLiquidity) rank += 80;
        if (zone.premiumDiscountAligned) rank += 35;
        return rank;
    }

    private static boolean hasSmartMoneyModel(TradePlan plan) {
        return modelRank(plan) > 0 || (plan != null && plan.zone != null
                && plan.zone.mechanic != null && !plan.zone.mechanic.isBlank());
    }

    private FilterMatch evaluateFilters(AnalysisResult result, TradePlan plan, int score,
                                        RadarFilters filters,
                                        RadarBacktestStats.Snapshot stats) {
        FilterMatch match = new FilterMatch(filters.requiredLabel(), filters.minScore, score);
        if (score < filters.minScore) {
            match.scoreRejected = true;
            return match;
        }
        if (filters.requireSmartMoneyModel && !hasSmartMoneyModel(plan)) {
            match.reject("нет smart-money модели");
            return match;
        }
        if (filters.requireAPlusModel && (plan.zone == null || !plan.zone.highQualityModel)) {
            match.reject("нет A+ модели");
            return match;
        }
        if (filters.requireLiquidityTargetModel && (plan.zone == null || !plan.zone.liquidityToLiquidity)) {
            match.reject("нет L2L цели");
            return match;
        }
        if (filters.requirePremiumDiscountAlignment && (plan.zone == null || !plan.zone.premiumDiscountAligned)) {
            match.reject("premium/discount не совпал");
            return match;
        }

        addFactor(match, filters.useHtfAlignment, plan.longSide == result.htfBullish, "HTF");
        addFactor(match, filters.useLiquiditySweep,
                plan.sweepIndex >= 0 || (plan.zone != null && plan.zone.sweepConfirmed),
                "sweep");
        addFactor(match, filters.useDisplacement,
                plan.zone != null && plan.zone.displacementScore >= 60,
                "disp");
        addFactor(match, filters.useStructureShift,
                plan.chochIndex >= 0 || (plan.zone != null && plan.zone.structureScore >= 55),
                "BOS/CHoCH");
        addFactor(match, filters.useZoneQuality,
                plan.zone != null && plan.zone.score >= 70 && !plan.zone.mitigated,
                "zone");
        addFactor(match, filters.useVolume,
                plan.zone != null && plan.zone.volumeScore >= 45,
                "volume");
        addFactor(match, filters.useRegimeFilter,
                result.marketRegime == null || !result.marketRegime.choppy(),
                "no chop");
        addFactor(match, filters.useEntryDistance,
                entryDistanceAcceptable(plan, result.currentPrice),
                "near");
        addFactor(match, filters.useKillzone,
                result.sessionContext != null && result.sessionContext.inKillzone,
                "killzone");
        // Deviation/imbalance: skip entirely when context is not available — don't penalise
        boolean devAvail = result.deviationContext != null && result.deviationContext.available;
        addFactor(match, filters.useDeviationFilter && devAvail,
                result.deviationContext.supports(plan.longSide) && result.deviationContext.score >= 50,
                "deviation");
        boolean imbAvail = result.imbalanceContext != null && result.imbalanceContext.available;
        addFactor(match, filters.useImbalanceFilter && imbAvail,
                result.imbalanceContext.supports(plan.longSide) && result.imbalanceContext.score >= 50,
                "imbalance");
        addFactor(match, filters.useEventRiskFilter,
                result.eventRiskContext == null || !result.eventRiskContext.blocksNewRisk(),
                "event ok");
        addFactor(match, filters.useVolatilityShockFilter,
                !volatilityShock(result),
                "no news shock");
        addFactor(match, filters.useBacktestStats,
                stats != null
                        && stats.hasEnough(filters.minBacktestTrades)
                        && stats.positive(filters.minExpectancyR),
                "stats+");

        match.requiredFactors = filters.requiredFactors(match.selectedFactors);
        match.passed = match.selectedFactors == 0 || match.passedFactors >= match.requiredFactors;
        return match;
    }

    private void addFactor(FilterMatch match, boolean enabled, boolean passed, String label) {
        if (!enabled) return;
        match.selectedFactors++;
        if (passed) {
            match.passedFactors++;
            if (match.passedLabels.length() > 0) match.passedLabels.append(", ");
            match.passedLabels.append(label);
        }
    }

    private AddOutcome offerAlert(AlertItem alert) {
        if (alert == null) return AddOutcome.skipped("");
        boolean beep = false;
        AddOutcome outcome;
        synchronized (SAVED_ALERTS) {
            String key = alert.key();
            if (SAVED_KEYS.contains(key)) {
                return AddOutcome.skipped("duplicate setup");
            }

            String symbolKey = alert.symbolKey();
            double quality = alertQuality(alert);
            Double conflictQuality = conflictQualityBySymbol.get(symbolKey);
            if (conflictQuality != null && quality <= conflictQuality + DIRECTION_CONFLICT_GAP) {
                return AddOutcome.conflict("Конфликт направления: " + alert.symbol + " пока пропущен");
            }
            if (conflictQuality != null) {
                conflictQualityBySymbol.remove(symbolKey);
            }

            List<AlertItem> sameSymbol = sameSymbolAlerts(symbolKey);
            AlertItem opposite = strongestOpposite(alert, sameSymbol);
            if (opposite != null) {
                double gap = quality - alertQuality(opposite);
                if (Math.abs(gap) < DIRECTION_CONFLICT_GAP) {
                    removeAlerts(sameSymbol);
                    double blockQuality = Math.max(quality, alertQuality(opposite));
                    conflictQualityBySymbol.put(symbolKey, blockQuality);
                    SwingUtilities.invokeLater(this::reloadListModelFromSaved);
                    return AddOutcome.conflict("Конфликт направления: " + alert.symbol
                            + " " + opposite.direction + " vs " + alert.direction + " · лучше пропустить");
                }
                if (gap < 0.0) {
                    return AddOutcome.conflict("Конфликт направления: " + alert.symbol
                            + " слабее уже найденного " + opposite.direction);
                }
            }

            boolean replaced = false;
            if (!sameSymbol.isEmpty()) {
                AlertItem bestExisting = bestAlert(sameSymbol);
                if (bestExisting != null && compareAlertPriority(alert, bestExisting) >= 0
                        && quality <= alertQuality(bestExisting) + 2.0) {
                    return AddOutcome.skipped("weaker same-symbol setup");
                }
                removeAlerts(sameSymbol);
                replaced = true;
            }

            SAVED_ALERTS.add(alert);
            SAVED_KEYS.add(alert.key());
            seenKeys.add(alert.key());
            SAVED_ALERTS.sort(RadarWindow::compareAlertPriority);
            while (SAVED_ALERTS.size() > 80) {
                AlertItem removed = SAVED_ALERTS.remove(SAVED_ALERTS.size() - 1);
                SAVED_KEYS.remove(removed.key());
            }
            beep = alert.confirmed && alert.score >= 70;
            outcome = AddOutcome.accepted(replaced);
        }
        SwingUtilities.invokeLater(this::reloadListModelFromSaved);
        if (beep) Toolkit.getDefaultToolkit().beep();
        return outcome;
    }

    private List<AlertItem> sameSymbolAlerts(String symbolKey) {
        List<AlertItem> matches = new ArrayList<>();
        for (AlertItem item : SAVED_ALERTS) {
            if (item != null && item.symbolKey().equals(symbolKey)) {
                matches.add(item);
            }
        }
        return matches;
    }

    private AlertItem strongestOpposite(AlertItem alert, List<AlertItem> candidates) {
        AlertItem best = null;
        for (AlertItem item : candidates) {
            if (item.longSide == alert.longSide) continue;
            if (best == null || compareAlertPriority(item, best) < 0) {
                best = item;
            }
        }
        return best;
    }

    private AlertItem bestAlert(List<AlertItem> candidates) {
        AlertItem best = null;
        for (AlertItem item : candidates) {
            if (best == null || compareAlertPriority(item, best) < 0) {
                best = item;
            }
        }
        return best;
    }

    private void removeAlerts(List<AlertItem> alerts) {
        if (alerts == null || alerts.isEmpty()) return;
        for (AlertItem item : alerts) {
            SAVED_ALERTS.remove(item);
            SAVED_KEYS.remove(item.key());
            seenKeys.remove(item.key());
        }
    }

    private static double alertQuality(AlertItem alert) {
        if (alert == null) return 0.0;
        double quality = alert.score;
        quality += alert.modelRank / 10.0;
        quality += alert.passedFactors * 2.0;
        quality += alert.confirmed ? 8.0 : 0.0;
        quality += alert.stale ? -12.0 : 0.0;
        quality += Math.max(-15.0, Math.min(15.0, alert.statsExpectancyR * 10.0));
        quality += Math.max(0.0, Math.min(5.0, alert.statsWinrate / 10.0));
        quality -= Math.min(6.0, Math.abs(alert.distancePct));
        quality += ltfQualityBonus(alert.ltf);
        return quality;
    }

    private static double ltfQualityBonus(String ltf) {
        if (ltf == null) return 0.0;
        return switch (ltf) {
            case "1m"  -> -18.0;
            case "5m"  -> -8.0;
            case "15m" -> 0.0;
            case "30m" -> +3.0;
            case "1h"  -> +6.0;
            case "4h"  -> +10.0;
            default    -> 0.0;
        };
    }

    private List<AlertItem> savedSnapshot() {
        synchronized (SAVED_ALERTS) {
            return new ArrayList<>(SAVED_ALERTS);
        }
    }

    private void applyRefreshedAlerts(List<AlertItem> refreshed) {
        List<AlertItem> incoming = refreshed == null ? List.of() : new ArrayList<>(refreshed);
        SwingUtilities.invokeLater(() -> {
            List<AlertItem> resolved = arbitrateAlerts(incoming);
            resolved.sort(RadarWindow::compareAlertPriority);
            synchronized (SAVED_ALERTS) {
                SAVED_ALERTS.clear();
                SAVED_KEYS.clear();
                for (AlertItem item : resolved) {
                    SAVED_ALERTS.add(item);
                    SAVED_KEYS.add(item.key());
                }
            }
            seenKeys.clear();
            seenKeys.addAll(SAVED_KEYS);
            reloadListModelFromSaved();
        });
    }

    private List<AlertItem> arbitrateAlerts(List<AlertItem> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<AlertItem> sorted = new ArrayList<>(source);
        sorted.sort(RadarWindow::compareAlertPriority);
        Map<String, AlertItem> selected = new HashMap<>();
        Map<String, Double> blocked = new HashMap<>();
        for (AlertItem alert : sorted) {
            String symbolKey = alert.symbolKey();
            double quality = alertQuality(alert);
            Double blockQuality = blocked.get(symbolKey);
            if (blockQuality != null && quality <= blockQuality + DIRECTION_CONFLICT_GAP) continue;

            AlertItem existing = selected.get(symbolKey);
            if (existing == null) {
                selected.put(symbolKey, alert);
                continue;
            }

            if (existing.longSide != alert.longSide) {
                double gap = quality - alertQuality(existing);
                if (Math.abs(gap) < DIRECTION_CONFLICT_GAP) {
                    selected.remove(symbolKey);
                    blocked.put(symbolKey, Math.max(quality, alertQuality(existing)));
                    continue;
                }
                if (gap > 0.0) selected.put(symbolKey, alert);
                continue;
            }

            if (compareAlertPriority(alert, existing) < 0) {
                selected.put(symbolKey, alert);
            }
        }
        conflictQualityBySymbol.clear();
        conflictQualityBySymbol.putAll(blocked);
        return new ArrayList<>(selected.values());
    }

    private void restoreSavedAlerts() {
        int restored = 0;
        synchronized (SAVED_ALERTS) {
            SAVED_ALERTS.sort(RadarWindow::compareAlertPriority);
            for (AlertItem item : SAVED_ALERTS) {
                seenKeys.add(item.key());
                restored++;
            }
        }
        reloadListModelFromSaved();
        seenKeys.addAll(SAVED_KEYS);
        if (restored > 0) {
            setStatus("Сохраненные сетапы: " + restored + ". Нажми «Обновить текущий список» для перескана.");
        }
    }

    private void reloadListModelFromSaved() {
        listModel.clear();
        synchronized (SAVED_ALERTS) {
            for (AlertItem item : SAVED_ALERTS) {
                listModel.addElement(item);
            }
        }
        alertList.repaint();
    }

    private static int compareAlertPriority(AlertItem left, AlertItem right) {
        int byTf = Double.compare(ltfQualityBonus(right.ltf), ltfQualityBonus(left.ltf));
        if (byTf != 0) return byTf;
        int byModel = Integer.compare(right.modelRank, left.modelRank);
        if (byModel != 0) return byModel;
        int byScore = Integer.compare(right.score, left.score);
        if (byScore != 0) return byScore;
        int byExpectancy = Double.compare(right.statsExpectancyR, left.statsExpectancyR);
        if (byExpectancy != 0) return byExpectancy;
        int byWinrate = Double.compare(right.statsWinrate, left.statsWinrate);
        if (byWinrate != 0) return byWinrate;
        int byConfirmed = Boolean.compare(right.confirmed, left.confirmed);
        if (byConfirmed != 0) return byConfirmed;
        int byFresh = Boolean.compare(left.stale, right.stale);
        if (byFresh != 0) return byFresh;
        int byDistance = Double.compare(Math.abs(left.distancePct), Math.abs(right.distancePct));
        if (byDistance != 0) return byDistance;
        return String.valueOf(left.symbol).compareTo(String.valueOf(right.symbol));
    }

    private void openFiltersDialog() {
        RadarFilters draft = activeSavedFilters().copy();
        JDialog dialog = new JDialog(this, I18n.t("Фильтры радара"), true);
        dialog.setSize(560, 660);
        dialog.setMinimumSize(new Dimension(500, 540));
        dialog.setResizable(true);
        dialog.setLocationRelativeTo(this);
        AppIcons.install(dialog);
        WindowsTitleBar.applyAppTheme(dialog);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        dialog.setContentPane(root);

        JLabel title = new JLabel(I18n.t("Фильтры качества входа"));
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(19f));

        JLabel subtitle = new JLabel(I18n.t("Радар покажет только сетапы, которые проходят SMC score и выбранные confluence-факторы."));
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);

        Integer[] scoreValues = {50, 60, 70, 80, 90, 95};
        JComboBox<Integer> minScoreBox = new JComboBox<>(scoreValues);
        minScoreBox.setSelectedItem(Math.min(draft.minScore, 95));
        UIUtils.styleComboBox(minScoreBox, BG, FG, GRID);

        JPanel controls = new JPanel(new MigLayout("insets 0, gap 10 10, fillx", "[190!][grow,fill]", "[]"));
        controls.setOpaque(false);
        controls.add(filterLabel(I18n.t("Минимальный SMC")));
        controls.add(minScoreBox, "growx");

        JCheckBox htf = filterCheck(I18n.t("HTF по направлению сделки"), draft.useHtfAlignment);
        JCheckBox sweep = filterCheck(I18n.t("Снятие ликвидности перед входом"), draft.useLiquiditySweep);
        JCheckBox displacement = filterCheck(I18n.t("Displacement 60+ после импульса"), draft.useDisplacement);
        JCheckBox structure = filterCheck(I18n.t("Свежий CHoCH/BOS body close"), draft.useStructureShift);
        JCheckBox zone = filterCheck(I18n.t("Качественная OB/FVG зона 70+"), draft.useZoneQuality);
        JCheckBox volume = filterCheck(I18n.t("Объем / delta confirmation 45+"), draft.useVolume);
        JCheckBox regime = filterCheck(I18n.t("Не торговать низковолатильный chop"), draft.useRegimeFilter);
        JCheckBox distance = filterCheck(I18n.t("Осталось до входа не больше 0.85%"), draft.useEntryDistance);
        JCheckBox killzone = filterCheck(I18n.t("Только активная killzone/session"), draft.useKillzone);
        JCheckBox deviation = filterCheck(I18n.t("Deviation / smart-money trap"), draft.useDeviationFilter);
        JCheckBox imbalance = filterCheck(I18n.t("Imbalance по направлению сделки"), draft.useImbalanceFilter);
        JCheckBox eventRisk = filterCheck(I18n.t("Фильтр фундаментального/event risk"), draft.useEventRiskFilter);
        JCheckBox shock = filterCheck(I18n.t("Отсекать новостные/аномальные импульсы"), draft.useVolatilityShockFilter);
        JCheckBox smartModel = filterCheck(I18n.t("Только smart-money model (BRK/IFVG/PROP/MIT/A+/L2L)"), draft.requireSmartMoneyModel);
        JCheckBox aPlusModel = filterCheck(I18n.t("Только A+ полная цепочка"), draft.requireAPlusModel);
        JCheckBox l2lModel = filterCheck(I18n.t("Требовать цель ликвидности L2L"), draft.requireLiquidityTargetModel);
        JCheckBox pdModel = filterCheck(I18n.t("Требовать premium/discount alignment"), draft.requirePremiumDiscountAlignment);
        JCheckBox stats = filterCheck(I18n.t("Использовать статистику похожих бэктест-сетапов"), draft.useBacktestStats);
        JSpinner minStatsTrades = new JSpinner(new SpinnerNumberModel(draft.minBacktestTrades, 3, 100, 1));
        JSpinner minExpectancy = new JSpinner(new SpinnerNumberModel(draft.minExpectancyR, -1.0, 2.0, 0.05));
        styleFilterSpinner(minStatsTrades);
        styleFilterSpinner(minExpectancy);

        JPanel factors = new JPanel();
        factors.setOpaque(false);
        factors.setLayout(new BoxLayout(factors, BoxLayout.Y_AXIS));
        factors.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(12, 12, 12, 12)));
        factors.add(filterSectionTitle(I18n.t("Факторы confluence")));
        factors.add(htf);
        factors.add(sweep);
        factors.add(displacement);
        factors.add(structure);
        factors.add(zone);
        factors.add(volume);
        factors.add(regime);
        factors.add(distance);
        factors.add(killzone);
        factors.add(deviation);
        factors.add(imbalance);
        factors.add(eventRisk);
        factors.add(shock);
        factors.add(Box.createVerticalStrut(8));
        factors.add(filterSectionTitle(I18n.t("Smart-money models")));
        factors.add(smartModel);
        factors.add(aPlusModel);
        factors.add(l2lModel);
        factors.add(pdModel);
        factors.add(Box.createVerticalStrut(8));
        factors.add(filterSectionTitle(I18n.t("Статистика бэктеста")));
        factors.add(stats);
        factors.add(compactRow(I18n.t("Минимум похожих сделок"), minStatsTrades));
        factors.add(compactRow(I18n.t("Минимальное expectancy, R"), minExpectancy));

        JPanel center = new FilterScrollPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(controls);
        center.add(Box.createVerticalStrut(12));
        center.add(factors);

        JButton cancel = new JButton(I18n.t("Отмена"));
        UIUtils.stylePrimaryButton(cancel, MUTED);
        cancel.addActionListener(e -> dialog.dispose());

        JButton apply = new JButton(I18n.t("Применить"));
        UIUtils.stylePrimaryButton(apply, BULL);
        apply.addActionListener(e -> {
            Object score = minScoreBox.getSelectedItem();
            draft.minScore = score instanceof Integer value ? Math.min(value, 95) : 70;
            draft.useHtfAlignment = htf.isSelected();
            draft.useLiquiditySweep = sweep.isSelected();
            draft.useDisplacement = displacement.isSelected();
            draft.useStructureShift = structure.isSelected();
            draft.useZoneQuality = zone.isSelected();
            draft.useVolume = volume.isSelected();
            draft.useRegimeFilter = regime.isSelected();
            draft.useEntryDistance = distance.isSelected();
            draft.useKillzone = killzone.isSelected();
            draft.useDeviationFilter = deviation.isSelected();
            draft.useImbalanceFilter = imbalance.isSelected();
            draft.useEventRiskFilter = eventRisk.isSelected();
            draft.useVolatilityShockFilter = shock.isSelected();
            draft.requireSmartMoneyModel = smartModel.isSelected();
            draft.requireAPlusModel = aPlusModel.isSelected();
            draft.requireLiquidityTargetModel = l2lModel.isSelected();
            draft.requirePremiumDiscountAlignment = pdModel.isSelected();
            draft.useBacktestStats = stats.isSelected();
            draft.minBacktestTrades = ((Number) minStatsTrades.getValue()).intValue();
            draft.minExpectancyR = ((Number) minExpectancy.getValue()).doubleValue();
            saveActiveFilters(draft);
            updateFilterBadge();
            setStatus("Фильтры применены: " + activeSavedFilters().summary() + ". Запусти новый скан или обнови текущий список.");
            dialog.dispose();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(cancel);
        actions.add(apply);

        root.add(header, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(center,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        UIUtils.styleScrollPane(scroll, GRID);
        scroll.setBorder(BorderFactory.createLineBorder(GRID));
        scroll.setViewportBorder(null);
        scroll.setOpaque(true);
        scroll.setBackground(PANEL_BG);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(PANEL_BG);
        scroll.getVerticalScrollBar().setOpaque(true);
        scroll.getVerticalScrollBar().setBackground(PANEL_BG);
        scroll.getVerticalScrollBar().setForeground(MUTED);
        scroll.getHorizontalScrollBar().setOpaque(true);
        scroll.getHorizontalScrollBar().setBackground(PANEL_BG);
        scroll.getHorizontalScrollBar().setForeground(MUTED);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getVerticalScrollBar().setBlockIncrement(120);
        root.add(scroll, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private JLabel filterLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FG);
        label.setFont(UIUtils.labelFont(12.3f));
        return label;
    }

    private JLabel filterSectionTitle(String text) {
        JLabel label = filterLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 0, 8, 0));
        return label;
    }

    private JPanel compactRow(String labelText, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel label = new JLabel(labelText);
        label.setForeground(MUTED);
        label.setFont(UIUtils.bodyFont(12.4f));
        control.setPreferredSize(new Dimension(96, 26));
        row.add(label, BorderLayout.CENTER);
        row.add(control, BorderLayout.EAST);
        return row;
    }

    private void styleFilterSpinner(JSpinner spinner) {
        spinner.setFont(UIUtils.bodyFont(12.4f));
        spinner.setForeground(FG);
        spinner.setBackground(BG);
        spinner.setBorder(BorderFactory.createLineBorder(GRID));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            JTextField field = defaultEditor.getTextField();
            field.setForeground(FG);
            field.setBackground(BG);
            field.setCaretColor(FG);
            field.setSelectionColor(ACCENT);
            field.setSelectedTextColor(Color.WHITE);
            field.setBorder(new EmptyBorder(0, 8, 0, 6));
        }
        for (Component child : spinner.getComponents()) {
            child.setBackground(FIELD_BG);
            child.setForeground(FG);
        }
    }

    private String factorWord(int count) {
        int last = Math.abs(count) % 10;
        int lastTwo = Math.abs(count) % 100;
        if (last == 1 && lastTwo != 11) return "фактор";
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return "фактора";
        return "факторов";
    }

    private JCheckBox filterCheck(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setForeground(FG);
        box.setFont(UIUtils.bodyFont(12.7f));
        box.setFocusPainted(false);
        box.setBorder(new EmptyBorder(5, 0, 5, 0));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        return box;
    }

    private void updateFilterBadge() {
        filterBadge.setText(activeSavedFilters().summary());
    }

    private void openSelectedSignal() {
        AlertItem selected = alertList.getSelectedValue();
        if (selected != null) {
            if (selected.stale) {
                setStatus("Сигнал устарел. Нажми «Обновить текущий список», чтобы перепроверить вход.");
                return;
            }
            if (!selected.confirmed) {
                setStatus("Это watchlist без подтвержденной точки входа. Радар теперь открывает только подтвержденные входы.");
                return;
            }
            onSignalSelect.accept(selected);
            setStatus("Открыт график: " + selected.symbol + " · HTF " + selected.htf + " / LTF " + selected.ltf);
        }
    }

    private void stopScanning() {
        isRunning = false;
        if (scannerThread != null) scannerThread.interrupt();
        ExecutorService executor = scannerExecutor;
        if (executor != null) executor.shutdownNow();
        updateRadarProgress(0, "Остановлено.");
    }

    private void updateRadarProgress(int percent, String text) {
        SwingUtilities.invokeLater(() -> {
            int value = Math.max(0, Math.min(100, percent));
            statusLbl.setText(text);
            progressBar.setVisible(value > 0 && value < 100 || isRunning);
            progressBar.setValue(value);
            progressBar.setString(value + "%");
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLbl.setText(text));
    }

    private ExecutorService newRadarExecutor(String threadName) {
        return Executors.newFixedThreadPool(RADAR_WORKERS, task -> {
            Thread thread = new Thread(task, threadName + "-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            // Radar runs continuously in the background (a full multi-engine SMCAnalyzer pass per
            // symbol/timeframe, 4 workers, every ~90s) while the user may also have a live chart
            // open repainting at ~30fps on the EDT. At default (equal) priority, a CPU-constrained
            // machine schedules radar's worker threads and the EDT as peers, so a scan cycle can
            // visibly starve the chart's paint cadence -- reported as "chart specifically lags
            // while using the radar." Lowering worker priority doesn't change what they compute,
            // only how the OS scheduler favors them under contention: the UI thread wins ties.
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        });
    }

    private void sleepInterruptibly(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private TimeframePair[] buildPairs(String preferredHtf, String preferredLtf) {
        List<TimeframePair> result = new ArrayList<>();
        addPair(result, preferredHtf, preferredLtf);
        for (String[] pair : DEFAULT_PAIRS) addPair(result, pair[0], pair[1]);
        return result.toArray(new TimeframePair[0]);
    }

    private void addPair(List<TimeframePair> pairs, String htf, String ltf) {
        if (htf == null || ltf == null) return;
        for (TimeframePair pair : pairs) {
            if (pair.htf.equals(htf) && pair.ltf.equals(ltf)) return;
        }
        pairs.add(new TimeframePair(htf, ltf));
    }

    private static String fmt(double value) {
        return String.format("%.4f", value);
    }

    private static double remainingToEntryPct(boolean longSide, double currentPrice, double entry) {
        if (currentPrice <= 0.0 || entry <= 0.0) return 0.0;
        double remaining = longSide
                ? Math.max(0.0, currentPrice - entry)
                : Math.max(0.0, entry - currentPrice);
        return remaining / currentPrice * 100.0;
    }

    private static boolean entryDistanceAcceptable(TradePlan plan, double currentPrice) {
        if (plan == null || !Double.isFinite(currentPrice) || currentPrice <= 0.0) return false;
        if (stopAlreadyInvalidated(plan, currentPrice)) return false;
        return remainingToEntryPct(plan.longSide, currentPrice, plan.entry) <= 0.85;
    }

    private static boolean volatilityShock(AnalysisResult result) {
        if (result == null || result.candles == null || result.candles.size() < 24) return false;
        Candle last = result.candles.get(result.candles.size() - 1);
        int from = Math.max(0, result.candles.size() - 25);
        double rangeSum = 0.0;
        double volumeSum = 0.0;
        int count = 0;
        for (int i = from; i < result.candles.size() - 1; i++) {
            Candle c = result.candles.get(i);
            rangeSum += c.range();
            volumeSum += Math.max(0.0, c.volume);
            count++;
        }
        if (count == 0) return false;
        double avgRange = rangeSum / count;
        double avgVolume = volumeSum / count;
        boolean rangeShock = avgRange > 0.0 && last.range() > avgRange * 3.2;
        boolean volumeShock = avgVolume > 0.0 && last.volume > avgVolume * 4.0;
        boolean hugeBody = last.bodyPercent() > 0.78 && avgRange > 0.0 && last.body() > avgRange * 2.2;
        return (rangeShock && hugeBody) || (rangeShock && volumeShock);
    }

    private static boolean stopAlreadyInvalidated(TradePlan plan, double currentPrice) {
        if (plan == null || !Double.isFinite(currentPrice) || currentPrice <= 0.0
                || !Double.isFinite(plan.stop) || plan.stop <= 0.0) {
            return false;
        }
        return plan.longSide ? currentPrice <= plan.stop : currentPrice >= plan.stop;
    }

    private RadarFilters activeSavedFilters() {
        return SAVED_FILTERS;
    }

    private void saveActiveFilters(RadarFilters filters) {
        SAVED_FILTERS = filters.copy();
        saveFilters(SAVED_FILTERS);
    }

    private static RadarFilters loadSavedFilters() {
        RadarFilters filters = new RadarFilters();
        String prefix = FILTER_PREF;
        // Schema migration v→2: reset exotic flags that break "all selected must pass"
        if (SETTINGS.getInt(prefix + "schema", 0, 0, 99) < 2) {
            SETTINGS.putInt(prefix + "schema", 2);
            SETTINGS.putInt(prefix + "useDeviationFilter", 0);
            SETTINGS.putInt(prefix + "useImbalanceFilter", 0);
            SETTINGS.putInt(prefix + "useKillzone", 0);
            SETTINGS.putInt(prefix + "useVolatilityShockFilter", 0);
            SETTINGS.putInt(prefix + "useEntryDistance", 0);
        }
        filters.minScore = SETTINGS.getInt(prefix + "minScore", filters.minScore, 50, 95);
        filters.useHtfAlignment = boolPref(prefix, "useHtfAlignment", filters.useHtfAlignment);
        filters.useLiquiditySweep = boolPref(prefix, "useLiquiditySweep", filters.useLiquiditySweep);
        filters.useDisplacement = boolPref(prefix, "useDisplacement", filters.useDisplacement);
        filters.useStructureShift = boolPref(prefix, "useStructureShift", filters.useStructureShift);
        filters.useZoneQuality = boolPref(prefix, "useZoneQuality", filters.useZoneQuality);
        filters.useVolume = boolPref(prefix, "useVolume", filters.useVolume);
        filters.useRegimeFilter = boolPref(prefix, "useRegimeFilter", filters.useRegimeFilter);
        filters.useEntryDistance = boolPref(prefix, "useEntryDistance", filters.useEntryDistance);
        filters.useKillzone = boolPref(prefix, "useKillzone", filters.useKillzone);
        filters.useDeviationFilter = boolPref(prefix, "useDeviationFilter", filters.useDeviationFilter);
        filters.useImbalanceFilter = boolPref(prefix, "useImbalanceFilter", filters.useImbalanceFilter);
        filters.useEventRiskFilter = boolPref(prefix, "useEventRiskFilter", filters.useEventRiskFilter);
        filters.useVolatilityShockFilter = boolPref(prefix, "useVolatilityShockFilter", filters.useVolatilityShockFilter);
        filters.requireSmartMoneyModel = boolPref(prefix, "requireSmartMoneyModel", filters.requireSmartMoneyModel);
        filters.requireAPlusModel = boolPref(prefix, "requireAPlusModel", filters.requireAPlusModel);
        filters.requireLiquidityTargetModel = boolPref(prefix, "requireLiquidityTargetModel", filters.requireLiquidityTargetModel);
        filters.requirePremiumDiscountAlignment = boolPref(prefix, "requirePremiumDiscountAlignment", filters.requirePremiumDiscountAlignment);
        filters.useBacktestStats = boolPref(prefix, "useBacktestStats", filters.useBacktestStats);
        filters.minBacktestTrades = SETTINGS.getInt(prefix + "minBacktestTrades", filters.minBacktestTrades, 3, 100);
        filters.minExpectancyR = SETTINGS.getDouble(prefix + "minExpectancyR", filters.minExpectancyR, -1.0, 2.0);
        return filters.copy();
    }

    private static void saveFilters(RadarFilters filters) {
        if (filters == null) return;
        String prefix = FILTER_PREF;
        SETTINGS.putInt(prefix + "minScore", filters.minScore);
        putBoolPref(prefix, "useHtfAlignment", filters.useHtfAlignment);
        putBoolPref(prefix, "useLiquiditySweep", filters.useLiquiditySweep);
        putBoolPref(prefix, "useDisplacement", filters.useDisplacement);
        putBoolPref(prefix, "useStructureShift", filters.useStructureShift);
        putBoolPref(prefix, "useZoneQuality", filters.useZoneQuality);
        putBoolPref(prefix, "useVolume", filters.useVolume);
        putBoolPref(prefix, "useRegimeFilter", filters.useRegimeFilter);
        putBoolPref(prefix, "useEntryDistance", filters.useEntryDistance);
        putBoolPref(prefix, "useKillzone", filters.useKillzone);
        putBoolPref(prefix, "useDeviationFilter", filters.useDeviationFilter);
        putBoolPref(prefix, "useImbalanceFilter", filters.useImbalanceFilter);
        putBoolPref(prefix, "useEventRiskFilter", filters.useEventRiskFilter);
        putBoolPref(prefix, "useVolatilityShockFilter", filters.useVolatilityShockFilter);
        putBoolPref(prefix, "requireSmartMoneyModel", filters.requireSmartMoneyModel);
        putBoolPref(prefix, "requireAPlusModel", filters.requireAPlusModel);
        putBoolPref(prefix, "requireLiquidityTargetModel", filters.requireLiquidityTargetModel);
        putBoolPref(prefix, "requirePremiumDiscountAlignment", filters.requirePremiumDiscountAlignment);
        putBoolPref(prefix, "useBacktestStats", filters.useBacktestStats);
        SETTINGS.putInt(prefix + "minBacktestTrades", filters.minBacktestTrades);
        SETTINGS.putDouble(prefix + "minExpectancyR", filters.minExpectancyR);
    }

    private static boolean boolPref(String prefix, String key, boolean fallback) {
        return SETTINGS.getInt(prefix + key, fallback ? 1 : 0, 0, 1) == 1;
    }

    private static void putBoolPref(String prefix, String key, boolean value) {
        SETTINGS.putInt(prefix + key, value ? 1 : 0);
    }

    private void updateLivePrice(AnalysisResult result, String symbol, BinanceClient.Market market) {
        if (result == null || result.plan == null) return;
        try {
            result.currentPrice = BinanceClient.fetchLastPrice(symbol, market);
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(Exception ex) {
        if (ex == null) return "unknown error";
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) return message;
        return ex.getClass().getSimpleName();
    }

    private static class TimeframePair {
        final String htf;
        final String ltf;

        TimeframePair(String htf, String ltf) {
            this.htf = htf;
            this.ltf = ltf;
        }
    }

    private static class ScanJob {
        final String symbol;
        final TimeframePair pair;
        final int index;

        ScanJob(String symbol, TimeframePair pair, int index) {
            this.symbol = symbol;
            this.pair = pair;
            this.index = index;
        }
    }

    private static class ScanOutcome {
        final String symbol;
        final TimeframePair pair;
        final CandidateReview review;
        final boolean error;
        final boolean interrupted;

        private ScanOutcome(ScanJob job, CandidateReview review, boolean error, boolean interrupted) {
            this.symbol = job.symbol;
            this.pair = job.pair;
            this.review = review;
            this.error = error;
            this.interrupted = interrupted;
        }

        static ScanOutcome success(ScanJob job, CandidateReview review) {
            return new ScanOutcome(job, review, false, false);
        }

        static ScanOutcome error(ScanJob job, String message) {
            CandidateReview review = new CandidateReview();
            review.reason = message == null ? "API error" : message;
            return new ScanOutcome(job, review, true, false);
        }

        static ScanOutcome interrupted(ScanJob job) {
            return new ScanOutcome(job, new CandidateReview(), false, true);
        }
    }

    private static class RefreshOutcome {
        final AlertItem source;
        final AlertItem updated;
        final boolean error;
        final boolean interrupted;

        private RefreshOutcome(AlertItem source, AlertItem updated, boolean error, boolean interrupted) {
            this.source = source;
            this.updated = updated;
            this.error = error;
            this.interrupted = interrupted;
        }

        static RefreshOutcome success(AlertItem source, AlertItem updated) {
            return new RefreshOutcome(source, updated, false, false);
        }

        static RefreshOutcome error(AlertItem source, String message) {
            return new RefreshOutcome(source, null, true, false);
        }

        static RefreshOutcome interrupted(AlertItem source) {
            return new RefreshOutcome(source, null, false, true);
        }
    }

    private static class RefreshStats {
        int updated;
        int stale;
        int errors;
    }

    private static class CandidateReview {
        AlertItem alert;
        boolean hasPlan;
        int score;
        String reason = "";
    }

    private static class AddOutcome {
        final boolean accepted;
        final boolean replaced;
        final boolean conflict;
        final String message;

        private AddOutcome(boolean accepted, boolean replaced, boolean conflict, String message) {
            this.accepted = accepted;
            this.replaced = replaced;
            this.conflict = conflict;
            this.message = message == null ? "" : message;
        }

        static AddOutcome accepted(boolean replaced) {
            return new AddOutcome(true, replaced, false, "");
        }

        static AddOutcome conflict(String message) {
            return new AddOutcome(false, false, true, message);
        }

        static AddOutcome skipped(String message) {
            return new AddOutcome(false, false, false, message);
        }
    }

    private static class FilterMatch {
        final String requiredLabel;
        final StringBuilder passedLabels = new StringBuilder();
        final int minScore;
        final int score;
        int selectedFactors;
        int passedFactors;
        int requiredFactors;
        boolean passed;
        boolean scoreRejected;
        String hardRejectReason = "";

        FilterMatch(String requiredLabel, int minScore, int score) {
            this.requiredLabel = requiredLabel;
            this.minScore = minScore;
            this.score = score;
        }

        String summary() {
            if (scoreRejected) return "SMC " + score + " < " + minScore;
            if (!hardRejectReason.isBlank()) return hardRejectReason;
            String passedText = passedLabels.length() == 0 ? "нет" : passedLabels.toString();
            return passedFactors + "/" + requiredFactors + " · " + requiredLabel + " · " + passedText;
        }

        void reject(String reason) {
            this.hardRejectReason = reason == null || reason.isBlank() ? "не прошел фильтр" : reason;
            this.passed = false;
        }
    }

    private static class RadarFilters {
        int minScore = 70;
        boolean useHtfAlignment = true;
        boolean useLiquiditySweep = true;
        boolean useDisplacement = true;
        boolean useStructureShift = true;
        boolean useZoneQuality = true;
        boolean useVolume = false;
        boolean useRegimeFilter = true;
        boolean useEntryDistance = false;
        boolean useKillzone = false;
        boolean useDeviationFilter = false;
        boolean useImbalanceFilter = false;
        boolean useEventRiskFilter = false;
        boolean useVolatilityShockFilter = false;
        boolean requireSmartMoneyModel = false;
        boolean requireAPlusModel = false;
        boolean requireLiquidityTargetModel = false;
        boolean requirePremiumDiscountAlignment = false;
        boolean useBacktestStats = false;
        int minBacktestTrades = 20;
        double minExpectancyR = 0.0;
        RadarFilters copy() {
            RadarFilters copy = new RadarFilters();
            copy.minScore = Math.max(50, Math.min(95, minScore));
            copy.useHtfAlignment = useHtfAlignment;
            copy.useLiquiditySweep = useLiquiditySweep;
            copy.useDisplacement = useDisplacement;
            copy.useStructureShift = useStructureShift;
            copy.useZoneQuality = useZoneQuality;
            copy.useVolume = useVolume;
            copy.useRegimeFilter = useRegimeFilter;
            copy.useEntryDistance = useEntryDistance;
            copy.useKillzone = useKillzone;
            copy.useDeviationFilter = useDeviationFilter;
            copy.useImbalanceFilter = useImbalanceFilter;
            copy.useEventRiskFilter = useEventRiskFilter;
            copy.useVolatilityShockFilter = useVolatilityShockFilter;
            copy.requireSmartMoneyModel = requireSmartMoneyModel;
            copy.requireAPlusModel = requireAPlusModel;
            copy.requireLiquidityTargetModel = requireLiquidityTargetModel;
            copy.requirePremiumDiscountAlignment = requirePremiumDiscountAlignment;
            copy.useBacktestStats = useBacktestStats;
            // 10 trades is still a thin sample for a fat-tailed R-multiple distribution, but it's
            // a meaningful floor above the old default of 3 — see Snapshot.shrunkExpectancy()/
            // winrateLowerBound95() for how small samples get pulled toward a neutral prior
            // instead of being trusted at face value.
            copy.minBacktestTrades = Math.max(10, Math.min(100, minBacktestTrades));
            copy.minExpectancyR = Math.max(-1.0, Math.min(2.0, minExpectancyR));
            return copy;
        }

        int requiredFactors(int selectedFactors) {
            // Strict AND when there's barely anything selected — no slack to work with, and a
            // 1-of-2 pass would be a coin flip, not confluence.
            if (selectedFactors <= 2) return selectedFactors;
            // Graduated confluence: a strong majority of confirmations can carry one or two
            // borderline/noisy checks instead of an all-or-nothing AND gate. Previously a
            // single weak factor (e.g. volume just under the bar) killed an otherwise
            // 5-of-6-confluent setup even though nothing else about it was actually bad.
            int allowedMisses = Math.min(2, selectedFactors / 4);
            return selectedFactors - allowedMisses;
        }

        String requiredLabel() {
            return "почти все выбранные (допуск на 1-2 слабых)";
        }

        String summary() {
            String text = "SMC " + minScore + "+ · confluence с допуском на слабые факторы";
            if (requireSmartMoneyModel) text += " · SMC model";
            if (requireAPlusModel) text += " · A+";
            if (requireLiquidityTargetModel) text += " · L2L";
            if (requirePremiumDiscountAlignment) text += " · PD";
            if (useBacktestStats) text += " · stats " + minBacktestTrades + "+ / " + String.format(Locale.US, "%.2fR", minExpectancyR);
            return text;
        }
    }

    private static class FilterScrollPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static class AlertRenderer extends JPanel implements ListCellRenderer<AlertItem> {
        private final JLabel title = new JLabel();
        private final JLabel detail = new JLabel();
        private final JLabel meta = new JLabel();
        // Левая полоска качества: цвет = рейтинг A+/A/B/C
        private Color stripeColor = MUTED;

        AlertRenderer() {
            setLayout(new BorderLayout(8, 4));
            // Левый отступ 14px — оставляем место для полоски качества
            setBorder(new EmptyBorder(9, 14, 9, 10));
            setOpaque(true);
            title.setFont(UIUtils.labelFont(13.8f));
            detail.setFont(UIUtils.bodyFont(12.4f));
            meta.setFont(UIUtils.captionFont(11.4f));

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(title);
            text.add(Box.createVerticalStrut(4));
            text.add(detail);
            text.add(Box.createVerticalStrut(3));
            text.add(meta);
            add(text, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            // Цветная полоска слева — мгновенный визуальный сигнал качества
            g.setColor(stripeColor);
            g.fillRect(0, 3, 4, getHeight() - 6);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends AlertItem> list,
                                                      AlertItem value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            Color bg = isSelected ? ACCENT.darker() : BG;
            setBackground(bg);

            // Определяем рейтинг и EV из planSnapshot (если есть) или из score+confirmed
            String rating;
            double ev = Double.NaN;
            if (value.planSnapshot != null) {
                rating = value.planSnapshot.qualityRating();
                // Use this alert's own backtest evidence (already computed when it was built,
                // see buildAlert/statsTrades/statsWinrate) instead of the confidence-guess
                // fallback, whenever there's enough of a sample to trust it.
                ev = value.planSnapshot.expectedValueR(RadarBacktestStats.calibratedWinRate01(
                        value.statsTrades, value.statsWinrate, value.planSnapshot.confidence));
            } else {
                if (value.score >= 78 && value.confirmed) rating = "A+";
                else if (value.score >= 65 && value.confirmed) rating = "A";
                else if (value.score >= 52) rating = "B";
                else rating = "C";
            }

            // Цвет полоски по рейтингу
            stripeColor = switch (rating) {
                case "A+" -> new Color(BULL.getRed(), BULL.getGreen(), BULL.getBlue(), 220);
                case "A"  -> new Color(BULL.getRed(), BULL.getGreen(), BULL.getBlue(), 145);
                case "B"  -> new Color(YELLOW.getRed(), YELLOW.getGreen(), YELLOW.getBlue(), 185);
                default   -> new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 105);
            };

            Color sideColor = value.stale ? MUTED : (value.longSide ? BULL : BEAR);
            title.setForeground(sideColor);
            detail.setForeground(value.stale ? MUTED : FG);
            meta.setForeground(MUTED);

            String model = value.modelBadge == null || value.modelBadge.isBlank()
                    ? ""
                    : " · " + value.modelBadge;
            String evText = Double.isFinite(ev)
                    ? String.format(" · EV %.2fR", ev)
                    : "";
            title.setText("[" + rating + "] " + value.symbol + " · " + value.direction
                    + model + " · SMC " + value.score + "/100" + evText
                    + (value.stale ? " · устарел" : ""));
            detail.setText(value.market.label + " · HTF " + value.htf + " / LTF " + value.ltf
                    + " · цена " + fmt(value.currentPrice)
                    + " · вход " + fmt(value.entry));
            String modelText = value.modelName == null || value.modelName.isBlank()
                    ? value.zoneType
                    : value.zoneType + " · " + value.modelName;
            meta.setText(modelText + " · " + value.status
                    + " · до входа " + String.format("%.2f", value.distancePct) + "%"
                    + " · фильтр " + value.passedFactors + "/" + value.requiredFactors
                    + (value.statsTrades > 0
                    ? " · WR " + String.format("%.0f", value.statsWinrate) + "% / Exp "
                    + String.format("%.2fR", value.statsExpectancyR)
                    : "")
                    + " · " + value.time);
            return this;
        }
    }
}
