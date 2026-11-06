package app.ui;

import app.automation.TradeForecastEngine;
import app.backtest.BacktestAssumptions;
import app.learning.AdaptiveFactorWeights;
import app.learning.BacktestLearningTrainer;
import app.logic.Backtester;
import app.logic.BinanceClient;
import app.logic.SMCAnalyzer;
import app.ml.MlBridgeClient;
import app.ml.MlFeatureVector;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.TradePlan;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static app.Colors.*;

public class TradeAutomationDialog extends JDialog {
    private static final int HTF_LIMIT = 200;
    private static final int LTF_LIMIT = 500;
    private static final int HISTORY_LIMIT = 80;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final java.util.List<HistoryEntry> HISTORY =
            Collections.synchronizedList(new java.util.ArrayList<>());

    private final JFrame parent;
    private final String htfInterval;
    private final String trendMethod;
    private final BinanceClient.Market market;
    private final BacktestAssumptions baseAssumptions;
    private final int backtestDepth;
    private final Consumer<AnalysisResult> onResultUpdate;
    private final JTextArea logArea = new JTextArea();
    private final JEditorPane forecastPane = editor();
    private final JEditorPane dataPane = editor();
    private final JEditorPane historyPreviewPane = editor();
    private final DefaultListModel<HistoryEntry> historyModel = new DefaultListModel<>();
    private final JList<HistoryEntry> historyList = new JList<>(historyModel);
    private final JComboBox<String> forecastProfileBox = new JComboBox<>(
            new String[]{"Мягкий", "Средний", "Точный"});
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final java.util.List<JLabel> stepStatuses = new java.util.ArrayList<>();
    private final java.util.List<AbstractButton> actionButtons = new java.util.ArrayList<>();
    private JScrollPane logTab;
    private JScrollPane forecastTab;
    private JScrollPane dataTab;

    private AnalysisResult result;
    private AdaptiveFactorWeights mlWeights;
    private Backtester.Report lastBacktestReport;
    private boolean busy;

    public static void showDialog(JFrame parent,
                                  AnalysisResult result,
                                  String htfInterval,
                                  String trendMethod,
                                  BinanceClient.Market market,
                                  BacktestAssumptions assumptions,
                                  int backtestDepth,
                                  Consumer<AnalysisResult> onResultUpdate) {
        TradeAutomationDialog dialog = new TradeAutomationDialog(parent, result, htfInterval,
                trendMethod, market, assumptions, backtestDepth, onResultUpdate);
        dialog.setVisible(true);
    }

    private TradeAutomationDialog(JFrame parent,
                                  AnalysisResult result,
                                  String htfInterval,
                                  String trendMethod,
                                  BinanceClient.Market market,
                                  BacktestAssumptions assumptions,
                                  int backtestDepth,
                                  Consumer<AnalysisResult> onResultUpdate) {
        super(parent, "План торговли", false);
        this.parent = parent;
        this.result = result;
        this.htfInterval = htfInterval;
        this.trendMethod = trendMethod;
        this.market = market;
        this.baseAssumptions = assumptions;
        this.backtestDepth = Math.max(300, Math.min(10_000, backtestDepth));
        this.onResultUpdate = onResultUpdate;
        forecastProfileBox.setSelectedItem("Точный");

        setSize(1160, 700);
        setMinimumSize(new Dimension(940, 620));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot());
        refreshDataPane();
        appendLog("План открыт для " + result.symbol + " · " + sideText(result.plan));
    }

    private JComponent buildRoot() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        root.add(buildHeader(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSteps(), buildRightTabs());
        split.setBorder(null);
        split.setDividerLocation(610);
        split.setResizeWeight(0.55);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JLabel title = new JLabel("План торговли · " + result.symbol + " · " + sideText(result.plan));
        title.setForeground(result.plan.longSide ? BULL : BEAR);
        title.setFont(UIUtils.displayFont(20f));

        JLabel subtitle = new JLabel("Пошаговая автоматизация: перепроверка свечей, ML, бэктест, финальный прогноз");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(4));
        titleBox.add(subtitle);

        JButton runAll = button("Запустить цепочку", BULL);
        runAll.addActionListener(e -> runFullChain());
        actionButtons.add(runAll);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(buildProfileControl());
        right.add(runAll);

        header.add(titleBox, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildProfileControl() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);

        JLabel label = new JLabel("План:");
        label.setForeground(MUTED);
        label.setFont(UIUtils.labelFont(12.3f));

        forecastProfileBox.setPreferredSize(new Dimension(108, 30));
        forecastProfileBox.setToolTipText("Мягкий: watchlist, Средний: баланс, Точный: строгий фильтр исполнения");
        UIUtils.styleComboBox(forecastProfileBox, BG, FG, GRID);
        forecastProfileBox.addActionListener(e -> {
            appendLog("Режим торгового плана: " + selectedForecastProfile().label);
            refreshDataPane();
        });

        panel.add(label);
        panel.add(forecastProfileBox);
        return panel;
    }

    private JComponent buildSteps() {
        JPanel panel = new JPanel();
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(12, 12, 12, 12)));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(stepRow("1", "Перепроверить свечи и точку входа",
                "Загружает свежие HTF/LTF свечи, пересчитывает SMC, DOM, OI и синхронизирует график.",
                "Проверить", e -> runRefresh()));
        panel.add(stepRow("2", "ML анализ весов факторов",
                "Отправляет текущие признаки сетапа в Python ML bridge и получает адаптивные веса.",
                "ML анализ", e -> runMlAnalysis()));
        panel.add(stepRow("3", "Быстрый бэктест",
                "Проверяет идею на последних 1000 свечах с conservative OHLC и market impact.",
                "Бэктест", e -> runBacktest(false)));
        panel.add(stepRow("4", "Тиковый/Data Lake бэктест",
                "Если локальные aggTrades есть в Data Lake, использует их; иначе fallback/загрузка тиков.",
                "Tick/DataLake", e -> runBacktest(true)));
        panel.add(stepRow("5", "Финальный прогноз",
                "Агрегирует SMC, ML, DOM, derivatives, order flow и бэктест в один execution score.",
                "Прогноз", e -> runForecast()));

        JButton report = button("Открыть последний отчет бэктеста", ACCENT);
        report.addActionListener(e -> {
            if (lastBacktestReport == null) {
                appendLog("Отчета пока нет. Сначала запусти бэктест.");
                return;
            }
            BacktestReportDialog.showReport(parent, result.symbol, htfInterval, result.ltfInterval, lastBacktestReport);
        });
        actionButtons.add(report);
        report.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton intelligence = button("Статистика всех бэктестов", FIELD_BG);
        intelligence.addActionListener(e -> BacktestIntelligenceDialog.showDialog(this));
        actionButtons.add(intelligence);
        intelligence.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(10));
        panel.add(report);
        panel.add(Box.createVerticalStrut(8));
        panel.add(intelligence);

        return panel;
    }

    private JComponent stepRow(String number, String title, String description,
                               String buttonText, java.awt.event.ActionListener action) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(8, 0, 11, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));

        JLabel badge = new JLabel(number, SwingConstants.CENTER);
        badge.setPreferredSize(new Dimension(34, 34));
        badge.setOpaque(true);
        badge.setBackground(FIELD_BG);
        badge.setForeground(FG);
        badge.setFont(UIUtils.labelFont(13.2f));

        JLabel name = new JLabel(title);
        name.setForeground(FG);
        name.setFont(UIUtils.labelFont(13.8f));

        JLabel text = new JLabel("<html><body style='width:340px;'>" + escape(description) + "</body></html>");
        text.setForeground(MUTED);
        text.setFont(UIUtils.captionFont(11.8f));

        JLabel status = new JLabel("ожидает");
        status.setForeground(MUTED);
        status.setFont(UIUtils.labelFont(11.4f));
        stepStatuses.add(status);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(name);
        copy.add(Box.createVerticalStrut(4));
        copy.add(text);
        copy.add(Box.createVerticalStrut(5));
        copy.add(status);

        JButton run = button(buttonText, FIELD_BG);
        run.addActionListener(action);
        actionButtons.add(run);

        row.add(badge, BorderLayout.WEST);
        row.add(copy, BorderLayout.CENTER);
        row.add(run, BorderLayout.EAST);
        return row;
    }

    private JComponent buildRightTabs() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(BG);
        logArea.setForeground(FG);
        logArea.setCaretColor(FG);
        logArea.setFont(UIUtils.monoFont(11.4f, Font.PLAIN));

        UIUtils.styleTabbedPane(rightTabs, PANEL_BG, BG, FG, MUTED, ACCENT);
        logTab = scroll(logArea);
        forecastTab = scroll(forecastPane);
        dataTab = scroll(dataPane);
        rightTabs.addTab("Логи", logTab);
        rightTabs.addTab("Прогноз", forecastTab);
        rightTabs.addTab("Данные", dataTab);
        rightTabs.addTab("История", buildHistoryTab());
        return rightTabs;
    }

    private JComponent buildHistoryTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        historyList.setBackground(BG);
        historyList.setForeground(FG);
        historyList.setSelectionBackground(ACCENT.darker());
        historyList.setSelectionForeground(FG);
        historyList.setFont(UIUtils.labelFont(12.3f));
        historyList.setFixedCellHeight(58);
        historyList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                HistoryEntry entry = value instanceof HistoryEntry h ? h : null;
                label.setOpaque(true);
                label.setBorder(new EmptyBorder(6, 8, 6, 8));
                label.setBackground(isSelected ? ACCENT.darker() : BG);
                label.setForeground(FG);
                label.setFont(UIUtils.labelFont(12.3f));
                if (entry != null) {
                    label.setText("<html><b>" + escape(entry.symbol) + " · " + escape(entry.side)
                            + " · " + escape(entry.source) + "</b><br><span style='color:" + hex(MUTED) + ";'>"
                            + escape(entry.timeText()) + " · " + escape(entry.profileLabel)
                            + " · SMC " + entry.smcScore + "/100 · "
                            + escape(entry.verdictText()) + "</span></html>");
                }
                return label;
            }
        });
        historyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showHistoryPreview(historyList.getSelectedValue());
        });
        historyList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) openHistoryForecast();
            }
        });

        historyPreviewPane.setText(historyPreviewHtml(null));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll(historyList), scroll(historyPreviewPane));
        split.setBorder(null);
        split.setResizeWeight(0.45);
        split.setDividerLocation(220);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton forecast = button("Прогноз", ACCENT);
        JButton data = button("Данные", FIELD_BG);
        JButton logs = button("Логи", FIELD_BG);
        JButton report = button("Отчет", new Color(255, 152, 0));
        forecast.addActionListener(e -> openHistoryForecast());
        data.addActionListener(e -> openHistoryData());
        logs.addActionListener(e -> openHistoryLogs());
        report.addActionListener(e -> openHistoryReport());
        buttons.add(forecast);
        buttons.add(data);
        buttons.add(logs);
        buttons.add(report);

        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        reloadHistoryModel();
        return root;
    }

    private JScrollPane scroll(Component component) {
        JScrollPane scroll = new JScrollPane(component);
        UIUtils.styleScrollPane(scroll, GRID);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private JEditorPane editor() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setBackground(BG);
        pane.setForeground(FG);
        pane.setFont(UIUtils.bodyFont(12.5f));
        return pane;
    }

    private JButton button(String text, Color color) {
        JButton button = new JButton(text);
        UIUtils.stylePrimaryButton(button, color);
        return button;
    }

    private void runRefresh() {
        runWorker("Перепроверка свечей", 0, () -> {
            result = refreshAnalysis();
            SwingUtilities.invokeLater(() -> {
                if (onResultUpdate != null) onResultUpdate.accept(result);
                refreshDataPane();
            });
        });
    }

    private void runMlAnalysis() {
        runWorker("ML анализ", 1, () -> {
            mlWeights = requestMlWeights();
            appendLog("ML weights: " + mlWeights.summary());
            SwingUtilities.invokeLater(this::refreshDataPane);
        });
    }

    private void runBacktest(boolean tickMode) {
        runWorker(tickMode ? "Тиковый/DataLake бэктест" : "Быстрый бэктест",
                tickMode ? 3 : 2,
                () -> {
                    lastBacktestReport = runBacktestInternal(tickMode);
                    appendBacktestSummary(lastBacktestReport);
                    applyBacktestLearning(lastBacktestReport);
                    SwingUtilities.invokeLater(() -> {
                        refreshDataPane();
                        addHistorySnapshot(tickMode ? "Тиковый/DataLake бэктест" : "Быстрый бэктест", null);
                    });
                });
    }

    private void runForecast() {
        runWorker("Финальный прогноз", 4, () -> {
            appendLog("Перед прогнозом обновляю свечи, чтобы не считать по устаревшему снимку...");
            result = refreshAnalysis();
            TradeForecastEngine.ForecastProfile profile = selectedForecastProfile();
            appendLog("Режим прогноза: " + profile.label);
            TradeForecastEngine.Decision decision = new TradeForecastEngine()
                    .evaluate(result, mlWeights, lastBacktestReport, profile);
            SwingUtilities.invokeLater(() -> {
                if (onResultUpdate != null) onResultUpdate.accept(result);
                refreshDataPane();
                showDecision(decision);
                addHistorySnapshot("Финальный прогноз", decision);
            });
            appendLog("Forecast: " + decision.verdict + " · score " + decision.score + "/100");
        });
    }

    private void runFullChain() {
        runWorker("Полная цепочка", -1, () -> {
            setStep(0, "в работе", YELLOW);
            result = refreshAnalysis();
            SwingUtilities.invokeLater(() -> {
                if (onResultUpdate != null) onResultUpdate.accept(result);
                refreshDataPane();
            });
            setStep(0, "готово", BULL);

            setStep(1, "в работе", YELLOW);
            mlWeights = requestMlWeights();
            appendLog("ML weights: " + mlWeights.summary());
            setStep(1, "готово", BULL);

            setStep(2, "в работе", YELLOW);
            lastBacktestReport = runBacktestInternal(false);
            appendBacktestSummary(lastBacktestReport);
            applyBacktestLearning(lastBacktestReport);
            setStep(2, "готово", BULL);

            setStep(3, "в работе", YELLOW);
            lastBacktestReport = runBacktestInternal(true);
            appendBacktestSummary(lastBacktestReport);
            applyBacktestLearning(lastBacktestReport);
            setStep(3, "готово", BULL);

            setStep(4, "в работе", YELLOW);
            TradeForecastEngine.ForecastProfile profile = selectedForecastProfile();
            appendLog("Режим прогноза: " + profile.label);
            TradeForecastEngine.Decision decision = new TradeForecastEngine()
                    .evaluate(result, mlWeights, lastBacktestReport, profile);
            SwingUtilities.invokeLater(() -> {
                refreshDataPane();
                showDecision(decision);
                addHistorySnapshot("Полная цепочка", decision);
            });
            appendLog("Forecast: " + decision.verdict + " · score " + decision.score + "/100");
            setStep(4, "готово", BULL);
        });
    }

    private AnalysisResult refreshAnalysis() throws Exception {
        appendLog("Загружаю HTF " + htfInterval + " candles...");
        List<Candle> htf = BinanceClient.fetchCandles(result.symbol, htfInterval, HTF_LIMIT, market);
        appendLog("Загружаю LTF " + result.ltfInterval + " candles...");
        List<Candle> ltf = BinanceClient.fetchCandles(result.symbol, result.ltfInterval, LTF_LIMIT, market);
        appendLog("Пересчитываю SMC/DOM/OI/OrderFlow...");
        AnalysisResult refreshed = SMCAnalyzer.analyze(result.symbol, result.ltfInterval, htf, ltf, trendMethod, market);
        refreshed.currentPrice = BinanceClient.fetchLastPrice(result.symbol, market);
        appendLog("Свечи синхронизированы. SMC " + refreshed.smcScore + "/100");
        return refreshed;
    }

    private AdaptiveFactorWeights requestMlWeights() {
        MlFeatureVector vector = MlFeatureVector.from(result.symbol, result.smcScore,
                result.derivativesContext, result.orderBookContext, result.marketRegime);
        AdaptiveFactorWeights fallback = result.adaptiveWeights == null
                ? AdaptiveFactorWeights.defaults(result.symbol)
                : result.adaptiveWeights;
        appendLog("Отправляю признаки в ML bridge...");
        return new MlBridgeClient().requestWeights(vector, fallback);
    }

    private void applyBacktestLearning(Backtester.Report report) {
        AdaptiveFactorWeights trained = new BacktestLearningTrainer().train(result.symbol, report, this::appendLog);
        if (trained != null) {
            mlWeights = trained;
            appendLog("Самообучение применено к текущему прогнозу: " + trained.summary());
        }
    }

    private Backtester.Report runBacktestInternal(boolean tickMode) throws Exception {
        BacktestAssumptions assumptions = tickMode ? tickAssumptions() : baseAssumptions;
        appendLog("Загружаю LTF историю для бэктеста: " + result.symbol + " " + result.ltfInterval
                + " · " + backtestDepth + " свечей");
        List<Candle> ltfHistory = BinanceClient.fetchCandlesHistory(result.symbol, result.ltfInterval, backtestDepth, market);
        int htfHistoryDepth = BinanceClient.matchingHtfHistoryLimit(htfInterval, ltfHistory);
        appendLog("Загружаю HTF контекст: " + htfInterval + " · " + htfHistoryDepth + " свечей");
        List<Candle> htfHistory = BinanceClient.fetchCandlesHistory(result.symbol, htfInterval, htfHistoryDepth, market);
        AtomicInteger lastLogged = new AtomicInteger(0);
        return Backtester.run(result.symbol, htfHistory, ltfHistory, trendMethod, market, assumptions,
                (percent, message) -> {
                    if (percent >= lastLogged.get() + 12 || percent >= 98) {
                        lastLogged.set(percent);
                        appendLog("Бэктест " + percent + "% · " + message);
                    }
                });
    }

    private BacktestAssumptions tickAssumptions() {
        return new BacktestAssumptions(
                baseAssumptions.spreadBps,
                baseAssumptions.slippageBps,
                baseAssumptions.takerFeeBps,
                baseAssumptions.tp1Size,
                baseAssumptions.tp2Size,
                baseAssumptions.tp3Size,
                true,
                Math.max(3, baseAssumptions.maxAggTradePagesPerCandle),
                baseAssumptions.minConfidenceScore,
                baseAssumptions.requireHtfDisplacementAligned,
                baseAssumptions.useMarketImpact,
                Math.max(baseAssumptions.executionNotionalUsd, 50_000.0),
                Math.max(baseAssumptions.executionSlices, 50),
                baseAssumptions.marketImpactBps,
                baseAssumptions.passiveSliceImprovementBps);
    }

    private TradeForecastEngine.ForecastProfile selectedForecastProfile() {
        return TradeForecastEngine.ForecastProfile.fromLabel(forecastProfileBox.getSelectedItem());
    }

    private void runWorker(String title, int stepIndex, ThrowingRunnable runnable) {
        if (busy) {
            appendLog("Операция уже выполняется.");
            return;
        }
        busy = true;
        setBusy(true);
        if (stepIndex >= 0) setStep(stepIndex, "в работе", YELLOW);
        appendLog("START: " + title);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                runnable.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (stepIndex >= 0) setStep(stepIndex, "готово", BULL);
                    appendLog("DONE: " + title);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    if (stepIndex >= 0) setStep(stepIndex, "ошибка", BEAR);
                    appendLog("ERROR: " + title + " · " + cause.getMessage());
                } finally {
                    busy = false;
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean value) {
        SwingUtilities.invokeLater(() -> {
            for (AbstractButton button : actionButtons) button.setEnabled(!value);
            forecastProfileBox.setEnabled(!value);
        });
    }

    private void setStep(int index, String text, Color color) {
        if (index < 0 || index >= stepStatuses.size()) return;
        SwingUtilities.invokeLater(() -> {
            JLabel label = stepStatuses.get(index);
            label.setText(text);
            label.setForeground(color);
        });
    }

    private void appendBacktestSummary(Backtester.Report report) {
        appendLog("Бэктест завершен: trades=" + report.executedTrades
                + " winrate=" + fmt(report.winrate) + "%"
                + " exp=" + fmt(report.expectancy) + "R"
                + " PF=" + fmt(report.profitFactor)
                + " DD=" + fmt(report.maxDrawdown) + "R"
                + " WS=" + report.websocketTickCandles
                + " DataLake=" + report.dataLakeTickCandles);
    }

    private void refreshDataPane() {
        dataPane.setText(currentDataHtml());
        dataPane.setCaretPosition(0);
    }

    private String currentDataHtml() {
        TradePlan p = result.plan;
        if (p == null) {
            return html("""
                    <h2>Текущий сетап</h2>
                    <div class='hero'>Торговый план отсутствует. Нужно дождаться точки входа на графике.</div>
                    """);
        }
        return html("""
                <h2>Текущий сетап</h2>
                <table>
                  <tr><td>Символ</td><td><b>%s</b></td></tr>
                  <tr><td>Направление</td><td><b>%s</b></td></tr>
                  <tr><td>SMC</td><td><b>%d/100</b></td></tr>
                  <tr><td>Цена</td><td><b>%.4f</b></td></tr>
                  <tr><td>Вход</td><td><b>%.4f</b></td></tr>
                  <tr><td>Стоп</td><td><b>%.4f</b></td></tr>
                  <tr><td>TP1 / TP2 / TP3</td><td><b>%.4f / %.4f / %.4f</b></td></tr>
                  <tr><td>R:R профиль</td><td><b>%s</b></td></tr>
                  <tr><td>Break-even WR</td><td><b>%s</b></td></tr>
                  <tr><td>Deviation</td><td><b>%s</b></td></tr>
                  <tr><td>Imbalance</td><td><b>%s</b></td></tr>
                  <tr><td>Event risk</td><td><b>%s</b></td></tr>
                  <tr><td>Режим плана</td><td><b>%s</b></td></tr>
                  <tr><td>Глубина бэктеста</td><td>%d свечей LTF</td></tr>
                  <tr><td>ML веса</td><td>%s</td></tr>
                  <tr><td>Бэктест</td><td>%s</td></tr>
                </table>
                """.formatted(
                escape(result.symbol),
                escape(sideText(p)),
                result.smcScore,
                result.currentPrice,
                p.entry,
                p.stop,
                p.tp1,
                p.tp2,
                p.tp3,
                escape(rrProfile(p)),
                escape(breakEvenWinrate(p)),
                escape(deviationSummary()),
                escape(imbalanceSummary()),
                escape(eventRiskSummary()),
                escape(selectedForecastProfile().label),
                backtestDepth,
                mlWeights == null ? "не запускался" : escape(mlWeights.summary()),
                lastBacktestReport == null ? "не запускался"
                        : "trades " + lastBacktestReport.executedTrades + " / exp " + fmt(lastBacktestReport.expectancy) + "R"
        ));
    }

    private String rrProfile(TradePlan p) {
        if (p == null) return "-";
        return "1:" + fmt(p.rr1()) + " / 1:" + fmt(p.rr2()) + " / 1:" + fmt(p.rr3())
                + " · " + p.targetModel;
    }

    private String breakEvenWinrate(TradePlan p) {
        if (p == null) return "-";
        double weightedReward = p.weightedRewardR(baseAssumptions.tp1Size, baseAssumptions.tp2Size, baseAssumptions.tp3Size);
        if (weightedReward <= 0.0) return "100%";
        return fmt(100.0 / (1.0 + weightedReward)) + "%";
    }

    private String deviationSummary() {
        if (result == null || result.deviationContext == null || !result.deviationContext.available) return "нет подтверждения";
        return result.deviationContext.label + " · " + result.deviationContext.score + "/100 · "
                + result.deviationContext.reversalDirection;
    }

    private String imbalanceSummary() {
        if (result == null || result.imbalanceContext == null || !result.imbalanceContext.available) return "нет подтверждения";
        return result.imbalanceContext.label + " · " + result.imbalanceContext.score + "/100";
    }

    private String eventRiskSummary() {
        if (result == null || result.eventRiskContext == null || !result.eventRiskContext.available) return "нет данных";
        return result.eventRiskContext.score + "/100 · " + result.eventRiskContext.label;
    }

    private void showDecision(TradeForecastEngine.Decision decision) {
        forecastPane.setText(forecastHtml(decision));
        forecastPane.setCaretPosition(0);
        if (forecastTab != null) rightTabs.setSelectedComponent(forecastTab);
    }

    private String forecastHtml(TradeForecastEngine.Decision decision) {
        StringBuilder good = new StringBuilder();
        for (String item : decision.positives) good.append("<li>").append(escape(item)).append("</li>");
        StringBuilder risk = new StringBuilder();
        for (String item : decision.risks) risk.append("<li>").append(escape(item)).append("</li>");

        Color c = decision.score >= 76 ? BULL : decision.score >= 65 ? YELLOW : BEAR;
        return html("""
                <h2 style='color:%s;'>%s · %d/100</h2>
                <div class='hero'>%s</div>
                <h3>Что поддерживает</h3>
                <ul>%s</ul>
                <h3>Риски</h3>
                <ul>%s</ul>
                """.formatted(hex(c), escape(decision.verdict), decision.score,
                escape(decision.action), good, risk));
    }

    private void addHistorySnapshot(String source, TradeForecastEngine.Decision decision) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> addHistorySnapshot(source, decision));
            return;
        }
        TradePlan p = result.plan;
        String forecastHtml = decision == null
                ? html("<h2>Прогноз еще не запускался</h2><div class='hero'>В этом снимке сохранен отчет бэктеста без финального прогноза.</div>")
                : forecastHtml(decision);
        HistoryEntry entry = new HistoryEntry(
                LocalDateTime.now(),
                source,
                selectedForecastProfile().label,
                result.symbol,
                sideText(p),
                htfInterval,
                result.ltfInterval,
                market.label,
                result.smcScore,
                p == null ? 0.0 : result.currentPrice,
                p == null ? 0.0 : p.entry,
                backtestDepth,
                decision == null ? -1 : decision.score,
                decision == null ? "" : decision.verdict,
                lastBacktestReport,
                currentDataHtml(),
                forecastHtml,
                logArea.getText()
        );
        synchronized (HISTORY) {
            HISTORY.add(0, entry);
            while (HISTORY.size() > HISTORY_LIMIT) HISTORY.remove(HISTORY.size() - 1);
        }
        reloadHistoryModel();
        historyList.setSelectedValue(entry, true);
        showHistoryPreview(entry);
    }

    private void reloadHistoryModel() {
        HistoryEntry selected = historyList.getSelectedValue();
        historyModel.clear();
        synchronized (HISTORY) {
            for (HistoryEntry entry : HISTORY) historyModel.addElement(entry);
        }
        if (selected != null && HISTORY.contains(selected)) {
            historyList.setSelectedValue(selected, true);
        } else if (!historyModel.isEmpty()) {
            historyList.setSelectedIndex(0);
        } else {
            showHistoryPreview(null);
        }
    }

    private HistoryEntry selectedHistory() {
        HistoryEntry entry = historyList.getSelectedValue();
        if (entry == null) appendLog("В истории пока ничего не выбрано.");
        return entry;
    }

    private void showHistoryPreview(HistoryEntry entry) {
        historyPreviewPane.setText(historyPreviewHtml(entry));
        historyPreviewPane.setCaretPosition(0);
    }

    private String historyPreviewHtml(HistoryEntry entry) {
        if (entry == null) {
            return html("""
                    <h2>История пуста</h2>
                    <div class='hero'>Запусти бэктест или финальный прогноз, и снимок появится здесь.</div>
                    """);
        }
        String backtest = entry.report == null
                ? "не запускался"
                : "trades " + entry.report.executedTrades
                + " · winrate " + fmt(entry.report.winrate) + "%"
                + " · exp " + fmt(entry.report.expectancy) + "R"
                + " · PF " + fmt(entry.report.profitFactor)
                + " · DD " + fmt(entry.report.maxDrawdown) + "R";
        String verdict = entry.forecastScore < 0 ? "нет прогноза" : entry.verdict + " · " + entry.forecastScore + "/100";
        return html("""
                <h2>%s · %s</h2>
                <table>
                  <tr><td>Время</td><td>%s</td></tr>
                  <tr><td>Источник</td><td>%s</td></tr>
                  <tr><td>Режим плана</td><td>%s</td></tr>
                  <tr><td>Таймфреймы</td><td>%s / %s</td></tr>
                  <tr><td>Рынок</td><td>%s</td></tr>
                  <tr><td>SMC</td><td>%d/100</td></tr>
                  <tr><td>Цена / вход</td><td>%.4f / %.4f</td></tr>
                  <tr><td>Глубина</td><td>%d свечей LTF</td></tr>
                  <tr><td>Прогноз</td><td>%s</td></tr>
                  <tr><td>Бэктест</td><td>%s</td></tr>
                </table>
                <div class='hero'>Кнопки снизу открывают выбранный снимок во вкладках Прогноз, Данные, Логи или отдельный отчет бэктеста.</div>
                """.formatted(
                escape(entry.symbol), escape(entry.side),
                escape(entry.timeText()), escape(entry.source),
                escape(entry.profileLabel),
                escape(entry.htf), escape(entry.ltf), escape(entry.marketLabel),
                entry.smcScore, entry.currentPrice, entry.entryPrice,
                entry.backtestDepth, escape(verdict), escape(backtest)));
    }

    private void openHistoryForecast() {
        HistoryEntry entry = selectedHistory();
        if (entry == null) return;
        forecastPane.setText(entry.forecastHtml);
        forecastPane.setCaretPosition(0);
        if (forecastTab != null) rightTabs.setSelectedComponent(forecastTab);
    }

    private void openHistoryData() {
        HistoryEntry entry = selectedHistory();
        if (entry == null) return;
        dataPane.setText(entry.dataHtml);
        dataPane.setCaretPosition(0);
        if (dataTab != null) rightTabs.setSelectedComponent(dataTab);
    }

    private void openHistoryLogs() {
        HistoryEntry entry = selectedHistory();
        if (entry == null) return;
        logArea.setText(entry.logSnapshot);
        logArea.setCaretPosition(logArea.getDocument().getLength());
        if (logTab != null) rightTabs.setSelectedComponent(logTab);
    }

    private void openHistoryReport() {
        HistoryEntry entry = selectedHistory();
        if (entry == null) return;
        if (entry.report == null) {
            appendLog("В выбранном снимке нет отчета бэктеста.");
            return;
        }
        BacktestReportDialog.showReport(parent, entry.symbol, entry.htf, entry.ltf, entry.report);
    }

    private String html(String body) {
        return """
                <html><head><style>
                body { font-family: __FONT__; font-size: 12.5px; color: __FG__;
                       background: __BG__; margin: 12px 14px; line-height: 1.5; }
                h2 { color: __ACCENT__; font-size: 17px; margin: 4px 0 10px 0; }
                h3 { color: __YELLOW__; font-size: 13.5px; margin: 12px 0 6px 0; }
                table { width: 100%; border-collapse: collapse; }
                td { border-bottom: 1px solid __GRID__; padding: 6px 4px; }
                td:first-child { color: __MUTED__; width: 38%; }
                .hero { background: __CARD__; border: 1px solid __GRID__; padding: 10px 12px; margin-bottom: 10px; }
                li { margin-bottom: 5px; }
                </style></head><body>
                """
                .replace("__FONT__", UIUtils.HTML_FONT_STACK)
                .replace("__FG__", hex(FG))
                .replace("__BG__", hex(BG))
                .replace("__CARD__", hex(CARD_BG))
                .replace("__GRID__", hex(GRID))
                .replace("__MUTED__", hex(MUTED))
                .replace("__ACCENT__", hex(ACCENT))
                .replace("__YELLOW__", hex(YELLOW))
                + body + "</body></html>";
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(LOG_TIME.format(LocalDateTime.now()) + " | " + line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            rightTabs.setSelectedIndex(0);
        });
    }

    private String sideText(TradePlan plan) {
        if (plan == null) return "--";
        return plan.longSide ? "ЛОНГ" : "ШОРТ";
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static class HistoryEntry {
        final LocalDateTime time;
        final String source;
        final String profileLabel;
        final String symbol;
        final String side;
        final String htf;
        final String ltf;
        final String marketLabel;
        final int smcScore;
        final double currentPrice;
        final double entryPrice;
        final int backtestDepth;
        final int forecastScore;
        final String verdict;
        final Backtester.Report report;
        final String dataHtml;
        final String forecastHtml;
        final String logSnapshot;

        HistoryEntry(LocalDateTime time, String source, String profileLabel, String symbol, String side,
                     String htf, String ltf, String marketLabel, int smcScore,
                     double currentPrice, double entryPrice, int backtestDepth,
                     int forecastScore, String verdict, Backtester.Report report,
                     String dataHtml, String forecastHtml, String logSnapshot) {
            this.time = time == null ? LocalDateTime.now() : time;
            this.source = source == null ? "" : source;
            this.profileLabel = profileLabel == null ? "" : profileLabel;
            this.symbol = symbol == null ? "" : symbol;
            this.side = side == null ? "" : side;
            this.htf = htf == null ? "" : htf;
            this.ltf = ltf == null ? "" : ltf;
            this.marketLabel = marketLabel == null ? "" : marketLabel;
            this.smcScore = smcScore;
            this.currentPrice = currentPrice;
            this.entryPrice = entryPrice;
            this.backtestDepth = backtestDepth;
            this.forecastScore = forecastScore;
            this.verdict = verdict == null ? "" : verdict;
            this.report = report;
            this.dataHtml = dataHtml == null ? "" : dataHtml;
            this.forecastHtml = forecastHtml == null ? "" : forecastHtml;
            this.logSnapshot = logSnapshot == null ? "" : logSnapshot;
        }

        String timeText() {
            return HISTORY_TIME.format(time);
        }

        String verdictText() {
            if (forecastScore >= 0) return verdict + " " + forecastScore + "/100";
            if (report != null) return "бэктест exp " + String.format("%.2fR", report.expectancy);
            return "снимок";
        }

        @Override public String toString() {
            return timeText() + " · " + symbol + " · " + source;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
