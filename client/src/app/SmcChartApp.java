package app;

import app.logic.BinanceClient;
import app.logic.CurrentChartAnalyzer;
import app.logic.SMCAnalyzer;
import app.backtest.BacktestAssumptions;
import app.backtest.Tick;
import app.backtest.WebSocketTickStore;
import app.common.ProjectPaths;
import app.common.UiSettings;
import app.common.I18n;
import app.execution.fix.AccountSnapshot;
import app.execution.fix.ClosedTradeJournal;
import app.execution.fix.ExchangeAccountSettings;
import app.execution.fix.ExchangeGatewayFactory;
import app.execution.fix.ExecutionGateway;
import app.execution.fix.ExecutionJournal;
import app.execution.fix.ExecutionLedger;
import app.execution.fix.ExecutionOrder;
import app.execution.fix.ExecutionReport;
import app.execution.fix.HftExecutionService;
import app.execution.fix.OrderNormalization;
import app.execution.fix.OpenOrderSnapshot;
import app.execution.fix.OpenPositionSnapshot;
import app.execution.fix.RiskSettings;
import app.infrastructure.LiveMarketDataService;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.ChartInsight;
import app.model.TradePlan;
import app.portfolio.CorrelationEngine;
import app.strategy.StrategyEngine;
import app.strategy.StrategySignal;
import app.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static app.Colors.*;

public class SmcChartApp extends JFrame {
    private static final Logger LOG = LoggerFactory.getLogger(SmcChartApp.class);
    private static final int HTF_LIMIT = 200, LTF_LIMIT = 500;
    private static final int SIDE_PANEL_WIDTH = 360;
    private static final int SIDE_PANEL_MIN_WIDTH = 320;
    private static final int SIDE_PANEL_COMPACT_WIDTH = 54;
    private static final String SYMBOLS_SETTINGS_KEY = "symbols.list";
    private static final String DEFAULT_SYMBOLS_CSV =
            "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT," +
            "XRPUSDT,TRXUSDT,ADAUSDT,LINKUSDT,XLMUSDT,ATOMUSDT," +
            "DOGEUSDT,PEPEUSDT,SHIBUSDT,WIFUSDT," +
            "SUIUSDT,APTUSDT,NEARUSDT,ARBUSDT,TONUSDT,AVAXUSDT,DOTUSDT,OPUSDT,MATICUSDT,SEIUSDT," +
            "AAVEUSDT,UNIUSDT,LDOUSDT,MKRUSDT," +
            "FETUSDT,RNDRUSDT,TIAUSDT," +
            "LTCUSDT,FTMUSDT,INJUSDT,BCHUSDT,ETCUSDT,SANDUSDT,WLDUSDT,XAUUSDT";
    private String[] SYMBOLS = loadSavedSymbols();
    private final JComboBox<String> symbolBox = new JComboBox<>(SYMBOLS);
    private final JComboBox<String> htfBox = new JComboBox<>(new String[]{"15m","1h","4h","1d"});
    private final JComboBox<String> ltfBox = new JComboBox<>(new String[]{"1m","5m","15m","30m","1h","4h","1d"});
    private final JComboBox<String> trendMethodBox = new JComboBox<>(new String[]{"(HH/HL)", "SMA (50)"});
    private final JComboBox<String> marketBox = new JComboBox<>(new String[]{"Futures", "Spot"});
    private final JComboBox<String> backtestModeBox = new JComboBox<>(new String[]{"Быстрый", "Тиковый"});
    private final JComboBox<String> backtestDepthBox = new JComboBox<>(new String[]{"1000 свечей", "2500 свечей", "5000 свечей", "10000 свечей"});
    private final JComboBox<String> scoreFilterBox = new JComboBox<>(new String[]{"SMC 55+", "SMC 70+", "SMC 80+", "SMC 85+"});
    private final JComboBox<String> htfDisplacementBox = new JComboBox<>(new String[]{"HTF disp: выкл", "HTF disp: вкл"});
    private final JComboBox<String> tpProfileBox = new JComboBox<>(new String[]{"TP 40/40/20", "TP 50/30/20"});

    private final JButton helpBtn = new JButton("?");
    private final JButton analyzeBtn = new JButton(I18n.t("Обновить"));
    private final JToggleButton forecastBtn = new JToggleButton(I18n.t("Прогноз"));
    private final JButton aiForecastBtn = new JButton(I18n.t("ИИ Прогноз"));
    private final JButton backtestBtn = new JButton(I18n.t("Бэктест"));
    private final JButton radarBtn = new JButton(I18n.t("Радар"));
    private final JButton infraBtn = new JButton(I18n.t("Инфра"));
    private final JButton accountBtn = new JButton(I18n.t("Аккаунт"));
    private final JButton tradePlanBtn = new JButton(I18n.t("План торговли"));
    private final JButton strategiesBtn = new JButton(I18n.t("Стратегии"));
    private final JButton chartLongBtn = new JButton(I18n.t("Открыть лонг"));
    private final JButton chartShortBtn = new JButton(I18n.t("Открыть шорт"));
    private final JButton chartCloseBtn = new JButton(I18n.t("Закрыть позицию"));
    private final JButton chartFullscreenBtn = new JButton("⛶");
    private final JProgressBar progressBar = new JProgressBar();
    private final ProgressStripPanel progressPanel = new ProgressStripPanel();
    private final JLabel statusLbl = new JLabel(" ");
    private final JLabel strategySummaryLbl = new JLabel(I18n.t("Итог стратегий: ждем график"));
    private final JLabel liveTickLbl = new JLabel(" ");
    private final JLabel accountStatusLbl = new JLabel(" ");

    private final ChartPanel chart = new ChartPanel();
    private final SidePanel side = new SidePanel();
    private final CurrentChartAnalyzer currentChartAnalyzer = new CurrentChartAnalyzer();
    private final JPanel pairTabsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private JPanel topStackPanel;
    private JScrollPane masterSideScroll;
    private JPanel sidePaneContainer;
    private JPanel sideCompactRail;
    private JButton sideCompactBtn;
    private JSplitPane mainSplit;
    private JPanel bottomBarPanel;
    private final JPanel fullscreenSidePlaceholder = new JPanel(new BorderLayout());
    private final List<PairTab> openPairTabs = new ArrayList<>();
    private final CorrelationEngine correlationEngine = new CorrelationEngine();
    private Timer priceTimer;
    private Timer liveUiThrottleTimer;
    private LiveMarketDataService liveMarketData;
    // What the live socket in `liveMarketData` is currently subscribed to, so re-analyzing the
    // SAME symbol/interval/market (e.g. the automatic re-analysis fired on every closed candle)
    // can keep reusing the existing WebSocket/HttpClient instead of tearing it down and building
    // a brand new one every cycle. See startLiveMarketData() for why that churn matters.
    private String liveSymbol = "";
    private String liveInterval = "";
    private BinanceClient.Market liveMarket;
    private Timer accountSnapshotTimer;
    private boolean accountSnapshotRunning = false;
    private boolean executionReconcileRunning = false;
    private AccountSnapshot accountSnapshot = null;
    private String accountSnapshotError = "";
    private final Map<String, Double> paperMarkPrices = new ConcurrentHashMap<>();
    private final Object pendingLivePriceLock = new Object();
    private String pendingLiveSymbol = "";
    private BinanceClient.Market pendingLiveMarket = BinanceClient.Market.FUTURES;
    private double pendingLivePrice = Double.NaN;
    private String pendingLiveSource = "";
    private long lastWebSocketPriceAt = 0L;
    private long lastSidePriceUiAt = 0L;
    private long lastPaperSnapshotUiAt = 0L;
    private long lastLiveChartInsightAt = 0L;
    private long lastClosedCandleAnalysisAt = 0L;
    private String lastClosedCandleAnalysisKey = "";
    private int analysisGeneration = 0;
    private boolean priceRefreshRunning = false;
    private boolean suppressControlEvents = false;
    private boolean suppressTabEvents = false;
    private boolean analysisRunning = false;
    private Boolean pendingTradeTicketLongSide = null;
    private String pendingTradeTicketSymbol = "";
    private boolean pendingTradeTicket = false;
    private final ForecastPlanState forecastState = new ForecastPlanState();
    private boolean chartFullscreen = false;
    private boolean sidePanelCompact = false;
    private int normalDividerLocation = SIDE_PANEL_WIDTH;
    private int normalWindowState = Frame.NORMAL;
    private Rectangle normalWindowBounds;
    private PairTab activePairTab = null;
    private String selectedStrategyId = "";
    private final Map<String, Long> pairTabRefreshStartedAt = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        configureJava2DPipeline();
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        applySavedTheme();
        UIUtils.installLookAndFeel();
        LOG.info("Starting SMC Chart desktop terminal");
        SwingUtilities.invokeLater(() -> {
            applySavedTheme();
            UIUtils.applyGlobalUi();
            WindowsTitleBar.installAppThemeSync();
            AppIcons.installTaskbarIcon();
            if (!LicenseLoginDialog.authenticate(null)) {
                LOG.info("SMC Chart startup stopped by license dialog");
                System.exit(0);
                return;
            }
            SmcChartApp app = new SmcChartApp();
            app.setVisible(true);
            app.applyNativeTitleBarTheme();
        });
    }

    /**
     * Picks the Java2D rendering pipeline per platform.
     *
     * <p>The chart draws hundreds of antialiased shapes plus translucent zones (AlphaComposite,
     * gradients) on every frame, and the live animation timer repaints at 30fps. Which pipeline
     * Java2D uses for that decides whether the window is smooth, unusable, or visibly broken.
     * All three options below were measured on Linux/niri/XWayland with an NVIDIA GPU:
     *
     * <ul>
     *   <li><b>XRender</b> (the old setting here) cannot accelerate antialiased fills or most
     *       alpha blends on the proprietary NVIDIA driver, and it gets far worse under XWayland.
     *       Every unaccelerated operation forces a pixel readback from the X server, renders in
     *       software, then pushes the pixels back: ~485ms per frame, i.e. 2fps. That was the
     *       "smooth on Windows, unusable on Linux" symptom.</li>
     *   <li><b>OpenGL</b> draws the same frame in ~1.4ms, but it is <em>not</em> safe here: after
     *       the window manager resizes the window, Swing stops painting the newly exposed area.
     *       The window then shows a stale, partially drawn UI on a flat unpainted background,
     *       which looks exactly like a frozen application even though the EDT is idle and the
     *       app is still running. Reproduced by resizing the window a few times under niri.</li>
     *   <li><b>Software rendering into system memory</b> is what we use. Turning off both xrender
     *       and pmoffscreen keeps every offscreen surface in RAM instead of in X11 pixmaps, so
     *       there are no readbacks and no GL driver involved. It is correct across resizes, and
     *       it is comfortably fast enough: ~6.4ms per frame on a synthetic worst case, and the
     *       real loaded chart costs the EDT only ~1.7ms per frame (5% of one core) against the
     *       33ms budget of the animation timer.</li>
     *   <li><b>Windows</b> keeps the previous configuration, which was already smooth there.</li>
     * </ul>
     *
     * <p>Every property is only set when it was not already supplied on the command line, so
     * {@code -Dsun.java2d.opengl=true} still wins if you want to experiment.
     */
    private static void configureJava2DPipeline() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") || os.contains("bsd") || os.contains("unix")) {
            setPropertyIfAbsent("sun.java2d.opengl", "false");
            setPropertyIfAbsent("sun.java2d.xrender", "false");
            setPropertyIfAbsent("sun.java2d.pmoffscreen", "false");
        } else if (os.contains("win")) {
            setPropertyIfAbsent("sun.java2d.opengl", "false");
            setPropertyIfAbsent("sun.java2d.d3d", "false");
            setPropertyIfAbsent("sun.java2d.ddforcevram", "false");
        }
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public SmcChartApp() {
        applySavedTheme();
        AppIcons.install(this);

        setTitle(I18n.english() ? "SMC Chart - Smart Money Concept Analyzer" : "SMC Chart — Smart Money Concept Analyzer");
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                applyNativeTitleBarTheme();
            }
        });
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1488, 880);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);

        setLayout(new BorderLayout());
        topStackPanel = buildTopStack();
        add(topStackPanel, BorderLayout.NORTH);

        masterSideScroll = new JScrollPane(side);
        masterSideScroll.setBorder(null);
        masterSideScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        masterSideScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        masterSideScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        masterSideScroll.getVerticalScrollBar().setUnitIncrement(16);
        masterSideScroll.setWheelScrollingEnabled(true);
        installSidePanelWheelScroll(side, masterSideScroll);

        sidePaneContainer = buildSidePaneContainer();
        applySidePanelMode(false);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidePaneContainer, buildChartArea());
        mainSplit.setDividerLocation(normalDividerLocation);
        mainSplit.setDividerSize(1);
        mainSplit.setBorder(null);
        mainSplit.setOpaque(false);
        mainSplit.setBackground(BG);
        mainSplit.setResizeWeight(0.0);
        add(mainSplit, BorderLayout.CENTER);

        bottomBarPanel = buildBottomBar();
        add(bottomBarPanel, BorderLayout.SOUTH);

        // Связываем графику с боковой панелью через коллбэк!
        side.bindChart(chart);
        chart.setOnZoneDoubleClicked((zone, price) -> side.showZone(zone, price));
        side.setPositionActionListener(new SidePanel.PositionActionListener() {
            @Override
            public void openPositionChart(String symbol, double entryPrice) {
                SmcChartApp.this.openPositionChart(symbol, entryPrice);
            }

            @Override
            public void closePosition(String symbol, boolean longSide, double entryPrice, double quantity) {
                SmcChartApp.this.closePositionFromPanel(symbol, longSide, entryPrice, quantity);
            }

            @Override
            public void cancelOrder(String clientOrderId, String symbol) {
                SmcChartApp.this.cancelOrderFromPanel(clientOrderId, symbol);
            }
        });

        analyzeBtn.addActionListener(e -> runAnalysis());
        symbolBox.addActionListener(e -> { if (!suppressControlEvents && "comboBoxChanged".equals(e.getActionCommand())) runAnalysis(); });
        trendMethodBox.addActionListener(e -> { if (!suppressControlEvents && "comboBoxChanged".equals(e.getActionCommand())) runAnalysis(); });
        htfBox.addActionListener(e -> { if (!suppressControlEvents && "comboBoxChanged".equals(e.getActionCommand())) runAnalysis(); });
        ltfBox.addActionListener(e -> { if (!suppressControlEvents && "comboBoxChanged".equals(e.getActionCommand())) runAnalysis(); });
        marketBox.addActionListener(e -> { if (!suppressControlEvents && "comboBoxChanged".equals(e.getActionCommand())) runAnalysis(); });
        forecastBtn.addActionListener(e -> {
            if (!forecastBtn.isSelected()) {
                forecastState.clearSticky();
                chart.clearRadarForecastPlan();
            } else {
                AnalysisResult current = chart.getResult();
                TradePlan plan = effectiveTradePlan(current);
                forecastState.stick(selectedSymbol(), plan);
                if (plan != null && current != null) chart.showRadarForecastPlan(current.symbol, plan);
            }
            chart.setShowForecast(forecastBtn.isSelected());
            forecastBtn.setEnabled(canShowForecast(chart.getResult()));
            updateTradePlanButtonState(chart.getResult());
            updateStrategySummary(chart.getResult());
        });
        backtestBtn.addActionListener(e -> runBacktest());
        radarBtn.addActionListener(e -> runRadar());
        infraBtn.addActionListener(e -> app.ui.InfrastructureDialog.showDialog(this));
        accountBtn.addActionListener(e -> openAccountDialog());
        tradePlanBtn.addActionListener(e -> openTradeAutomationPlan());
        strategiesBtn.addActionListener(e -> openStrategiesDialog());
        chartLongBtn.addActionListener(e -> openTradeTicket(true));
        chartShortBtn.addActionListener(e -> openTradeTicket(false));
        chartCloseBtn.addActionListener(e -> closeCurrentChartPosition());
        chartFullscreenBtn.addActionListener(e -> toggleChartFullscreen());
        updateAccountButton();
        refreshTheme();
        startAccountSnapshotTimer();
        SwingUtilities.invokeLater(() -> applySidePanelMode(sidePanelCompact));
        SwingUtilities.invokeLater(this::runAnalysis);
        startLiveUiThrottleTimer();
        startLivePriceTimer();
        refreshLanguage();
    }

    private static void applySavedTheme() {
        int max = Math.max(0, Colors.Theme.values().length - 1);
        int themeIdx = UiSettings.get().getInt("ui.theme", 0, 0, max);
        Colors.applyTheme(Colors.themeAt(themeIdx));
    }

    public static void applyThemeAndRefresh(Colors.Theme theme) {
        if (theme == null) return;
        UiSettings.get().putInt("ui.theme", theme.ordinal());
        Colors.applyTheme(theme);
        UIUtils.applyGlobalUi();
        for (Window win : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(win);
            if (win instanceof SmcChartApp app) {
                app.refreshTheme();
            } else {
                refreshGenericTheme(win);
            }
            applyNativeTitleBarTheme(win);
            win.invalidate();
            win.validate();
            win.repaint();
        }
    }

    public static void applyLanguageAndRefresh(I18n.Language language) {
        I18n.setLanguage(language);
        for (Window win : Window.getWindows()) {
            I18n.refreshTree(win);
            if (win instanceof SmcChartApp app) {
                app.refreshLanguage();
            } else if (win instanceof RadarWindow radar) {
                radar.refreshLanguage();
            } else {
                refreshGenericTheme(win);
            }
            win.invalidate();
            win.validate();
            win.repaint();
        }
    }

    private static void applyNativeTitleBarTheme(Window window) {
        WindowsTitleBar.applyAppTheme(window);
    }

    private void applyNativeTitleBarTheme() {
        applyNativeTitleBarTheme(this);
    }

    private static void refreshGenericTheme(Component component) {
        if (component == null) return;
        if (component instanceof ChartPanel cp) {
            cp.setBackground(BG);
            cp.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, GRID));
        } else if (component instanceof SidePanel sp) {
            sp.refreshThemeComponents();
        } else if (component instanceof JTabbedPane tabs) {
            UIUtils.styleTabbedPane(tabs, PANEL_BG, BG, FG, MUTED, ACCENT);
        } else if (component instanceof JScrollPane sp) {
            UIUtils.styleScrollPane(sp, GRID);
            sp.getViewport().setBackground(BG);
            sp.setBackground(BG);
        } else if (component instanceof JEditorPane editor) {
            editor.setBackground(BG);
            editor.setForeground(FG);
        } else if (component instanceof JComboBox<?> combo) {
            UIUtils.styleComboBox(combo, BG, FG, GRID);
        } else if (component instanceof JCheckBox box) {
            box.setForeground(FG);
            box.setBackground(FIELD_BG);
        } else if (component instanceof JSlider slider) {
            slider.setForeground(MUTED);
            slider.setBackground(FIELD_BG);
        } else if (component instanceof JLabel label) {
            refreshGenericLabel(label);
        } else if (component instanceof JPanel panel) {
            Object role = panel.getClientProperty("theme.role");
            if ("panel".equals(role)) panel.setBackground(PANEL_BG);
            else if ("bg".equals(role)) panel.setBackground(BG);
            else if ("card".equals(role)) panel.setBackground(CARD_BG);
            else if ("field".equals(role)) panel.setBackground(FIELD_BG);
            else if ("rail".equals(role)) panel.setBackground(RAIL_BG);
            else if (panel.isOpaque()) panel.setBackground(PANEL_BG);
        }

        if (component instanceof JComponent jc) {
            Object borderRole = jc.getClientProperty("theme.border");
            if ("grid".equals(borderRole)) jc.setBorder(BorderFactory.createLineBorder(GRID));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) refreshGenericTheme(child);
        }
    }

    private static void refreshGenericLabel(JLabel label) {
        Object role = label.getClientProperty("theme.fg");
        if ("muted".equals(role)) label.setForeground(MUTED);
        else if ("fg".equals(role)) label.setForeground(FG);
        else if ("accent".equals(role)) label.setForeground(ACCENT);
    }

    private JPanel buildSidePaneContainer() {
        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(PANEL_BG);
        container.putClientProperty("theme.role", "panel");
        container.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, GRID));

        sideCompactBtn = new JButton("<<");
        sideCompactBtn.setPreferredSize(new Dimension(32, 24));
        sideCompactBtn.setMargin(new Insets(0, 0, 0, 0));
        sideCompactBtn.setFocusable(false);
        sideCompactBtn.setToolTipText("Свернуть левую панель");
        UIUtils.stylePrimaryButton(sideCompactBtn, new Color(50, 55, 70));
        sideCompactBtn.addActionListener(e -> toggleSidePanelCompact());
        side.setHeaderActionComponent(sideCompactBtn);

        sideCompactRail = new JPanel(new BorderLayout());
        sideCompactRail.setBackground(PANEL_BG);
        sideCompactRail.putClientProperty("theme.role", "panel");
        sideCompactRail.setBorder(new EmptyBorder(8, 7, 0, 7));
        container.add(masterSideScroll, BorderLayout.CENTER);
        return container;
    }

    private void toggleSidePanelCompact() {
        applySidePanelMode(!sidePanelCompact);
    }

    private void applySidePanelMode(boolean compact) {
        sidePanelCompact = compact;
        int width = compact ? SIDE_PANEL_COMPACT_WIDTH : SIDE_PANEL_WIDTH;
        int minWidth = compact ? SIDE_PANEL_COMPACT_WIDTH : SIDE_PANEL_MIN_WIDTH;
        Dimension preferred = new Dimension(width, 0);
        Dimension minimum = new Dimension(minWidth, 0);

        if (sidePaneContainer != null) {
            sidePaneContainer.setPreferredSize(preferred);
            sidePaneContainer.setMinimumSize(minimum);
            sidePaneContainer.remove(masterSideScroll);
            if (sideCompactRail != null) sidePaneContainer.remove(sideCompactRail);
            if (compact) {
                side.setHeaderActionComponent(null);
                if (sideCompactRail != null && sideCompactBtn != null) {
                    sideCompactRail.removeAll();
                    sideCompactRail.add(sideCompactBtn, BorderLayout.NORTH);
                    sidePaneContainer.add(sideCompactRail, BorderLayout.CENTER);
                }
            } else {
                if (sideCompactRail != null && sideCompactBtn != null) sideCompactRail.remove(sideCompactBtn);
                if (sideCompactBtn != null) side.setHeaderActionComponent(sideCompactBtn);
                sidePaneContainer.add(masterSideScroll, BorderLayout.CENTER);
            }
        }
        if (masterSideScroll != null) {
            masterSideScroll.setPreferredSize(preferred);
            masterSideScroll.setMinimumSize(minimum);
        }
        if (sideCompactBtn != null) {
            sideCompactBtn.setText(compact ? ">>" : "<<");
            sideCompactBtn.setPreferredSize(compact ? new Dimension(40, 30) : new Dimension(32, 24));
            sideCompactBtn.setToolTipText(compact ? "Развернуть левую панель" : "Свернуть левую панель");
        }
        if (!compact) normalDividerLocation = SIDE_PANEL_WIDTH;
        if (mainSplit != null && !chartFullscreen) {
            mainSplit.setDividerLocation(width);
        }
        revalidate();
        repaint();
    }

    private JPanel buildTopStack() {
        JPanel stack = new JPanel(new BorderLayout());
        stack.setBackground(PANEL_BG);
        stack.putClientProperty("theme.role", "panel");
        stack.add(buildTopBar(), BorderLayout.NORTH);
        stack.add(buildPairTabsBar(), BorderLayout.SOUTH);
        return stack;
    }

    private JComponent buildPairTabsBar() {
        pairTabsPanel.setBackground(RAIL_BG);
        pairTabsPanel.putClientProperty("theme.role", "rail");
        pairTabsPanel.setBorder(new EmptyBorder(3, 8, 3, 8));

        JScrollPane scroll = new JScrollPane(pairTabsPanel);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(RAIL_BG);
        scroll.setPreferredSize(new Dimension(0, 38));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));
        return scroll;
    }

    private JPanel buildChartArea() {
        JPanel chartArea = new JPanel(new BorderLayout());
        chartArea.setBackground(BG);
        chartArea.putClientProperty("theme.role", "bg");
        chartArea.add(chart, BorderLayout.CENTER);

        progressBar.setMinimum(0);
        progressBar.setMaximum(100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("0%");
        progressBar.setForeground(ACCENT);
        progressBar.setBackground(BG);
        progressBar.setBorder(BorderFactory.createLineBorder(GRID));
        progressBar.setFont(UIUtils.labelFont(11.4f));
        progressBar.setUI(new app.ui.ModernProgressBarUI());
        progressBar.setPreferredSize(new Dimension(0, 20));

        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.setVisible(false);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buildChartTradeBar(), BorderLayout.NORTH);
        south.add(progressPanel, BorderLayout.SOUTH);
        chartArea.add(south, BorderLayout.SOUTH);
        return chartArea;
    }

    private JComponent buildChartTradeBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(RAIL_BG);
        bar.putClientProperty("theme.role", "rail");
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, GRID),
                new EmptyBorder(6, 10, 6, 10)));

        accountStatusLbl.setForeground(MUTED);
        accountStatusLbl.setFont(UIUtils.labelFont(11.8f));
        bar.add(accountStatusLbl, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        chartFullscreenBtn.setPreferredSize(new Dimension(42, 30));
        chartFullscreenBtn.setFocusable(false);
        chartFullscreenBtn.setToolTipText("Компактный режим графика");
        chartCloseBtn.setPreferredSize(new Dimension(138, 30));
        chartLongBtn.setPreferredSize(new Dimension(128, 30));
        chartShortBtn.setPreferredSize(new Dimension(128, 30));
        chartCloseBtn.setEnabled(false);
        chartLongBtn.setEnabled(false);
        chartShortBtn.setEnabled(false);
        UIUtils.stylePrimaryButton(chartFullscreenBtn, new Color(75, 85, 99));
        UIUtils.stylePrimaryButton(chartCloseBtn, new Color(245, 158, 11));
        UIUtils.stylePrimaryButton(chartLongBtn, BULL);
        UIUtils.stylePrimaryButton(chartShortBtn, BEAR);
        actions.add(chartFullscreenBtn);
        actions.add(chartCloseBtn);
        actions.add(chartShortBtn);
        actions.add(chartLongBtn);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private void toggleChartFullscreen() {
        if (mainSplit == null || sidePaneContainer == null || topStackPanel == null || bottomBarPanel == null) return;
        chartFullscreen = !chartFullscreen;
        if (chartFullscreen) {
            normalDividerLocation = sidePanelCompact ? SIDE_PANEL_COMPACT_WIDTH : SIDE_PANEL_WIDTH;
            normalWindowState = getExtendedState();
            normalWindowBounds = getBounds();
            topStackPanel.setVisible(true);
            bottomBarPanel.setVisible(false);
            fullscreenSidePlaceholder.setOpaque(true);
            fullscreenSidePlaceholder.setBackground(BG);
            fullscreenSidePlaceholder.setPreferredSize(new Dimension(0, 0));
            mainSplit.setLeftComponent(fullscreenSidePlaceholder);
            mainSplit.setDividerSize(0);
            mainSplit.setDividerLocation(0);
            chartFullscreenBtn.setText("↙");
            chartFullscreenBtn.setToolTipText("Вернуть обычный режим");
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        } else {
            topStackPanel.setVisible(true);
            bottomBarPanel.setVisible(true);
            mainSplit.setLeftComponent(sidePaneContainer);
            mainSplit.setDividerSize(1);
            applySidePanelMode(sidePanelCompact);
            chartFullscreenBtn.setText("⛶");
            chartFullscreenBtn.setToolTipText("Компактный режим графика");
            setExtendedState(normalWindowState);
            if ((normalWindowState & Frame.MAXIMIZED_BOTH) == 0 && normalWindowBounds != null) {
                setBounds(normalWindowBounds);
            }
        }
        revalidate();
        repaint();
    }

    private void installSidePanelWheelScroll(Component component, JScrollPane target) {
        component.addMouseWheelListener(e -> {
            if (scrollPaneByWheel(target, e)) e.consume();
        });
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installSidePanelWheelScroll(child, target);
            }
        }
    }

    private boolean scrollPaneByWheel(JScrollPane scroll, MouseWheelEvent event) {
        if (scroll == null) return false;
        JScrollBar bar = scroll.getVerticalScrollBar();
        if (bar == null) return false;
        int max = Math.max(bar.getMinimum(), bar.getMaximum() - bar.getVisibleAmount());
        if (max <= bar.getMinimum()) return false;
        int increment = Math.max(16, bar.getUnitIncrement());
        int amount = event.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL
                ? event.getUnitsToScroll() * increment
                : event.getWheelRotation() * Math.max(increment * 5, bar.getBlockIncrement());
        int next = Math.max(bar.getMinimum(), Math.min(max, bar.getValue() + amount));
        if (next == bar.getValue()) return false;
        bar.setValue(next);
        return true;
    }

    private JPanel buildTopBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 7));
        p.setBackground(PANEL_BG);
        p.putClientProperty("theme.role", "panel");
        JLabel title = new JLabel("SMC Chart"); title.setForeground(ACCENT); title.putClientProperty("theme.fg", "accent"); title.setFont(UIUtils.displayFont(19f));
        p.add(title); p.add(Box.createHorizontalStrut(10));

        p.add(lbl("Инструмент:")); symbolBox.setEditable(true); symbolBox.setSelectedItem("BTCUSDT"); symbolBox.setPreferredSize(new Dimension(150, 28)); UIUtils.styleComboBox(symbolBox, BG, FG, GRID); p.add(symbolBox);
        p.add(lbl("Рынок:")); marketBox.setSelectedItem("Futures"); marketBox.setPreferredSize(new Dimension(105, 28)); UIUtils.styleComboBox(marketBox, BG, FG, GRID); p.add(marketBox);
        p.add(lbl("HTF:")); htfBox.setSelectedItem("1h"); htfBox.setPreferredSize(new Dimension(86, 28));
        UIUtils.styleComboBox(htfBox, BG, FG, GRID); p.add(htfBox);
        p.add(lbl("Метод:"));
        trendMethodBox.setPreferredSize(new Dimension(148, 28)); UIUtils.styleComboBox(trendMethodBox, BG, FG, GRID); p.add(trendMethodBox);
        p.add(lbl("График:")); ltfBox.setSelectedItem("5m"); ltfBox.setPreferredSize(new Dimension(92, 28));
        UIUtils.styleComboBox(ltfBox, BG, FG, GRID); p.add(ltfBox);
        p.add(lbl("Бэктест:")); backtestModeBox.setSelectedItem("Быстрый"); backtestModeBox.setPreferredSize(new Dimension(132, 28)); UIUtils.styleComboBox(backtestModeBox, BG, FG, GRID); p.add(backtestModeBox);
        p.add(lbl("История:")); backtestDepthBox.setSelectedItem("1000 свечей"); backtestDepthBox.setPreferredSize(new Dimension(132, 28)); UIUtils.styleComboBox(backtestDepthBox, BG, FG, GRID); p.add(backtestDepthBox);
        scoreFilterBox.setPreferredSize(new Dimension(122, 28)); UIUtils.styleComboBox(scoreFilterBox, BG, FG, GRID); p.add(scoreFilterBox);
        htfDisplacementBox.setPreferredSize(new Dimension(125, 28)); UIUtils.styleComboBox(htfDisplacementBox, BG, FG, GRID); p.add(htfDisplacementBox);
        tpProfileBox.setPreferredSize(new Dimension(128, 28)); UIUtils.styleComboBox(tpProfileBox, BG, FG, GRID); p.add(tpProfileBox);

        liveTickLbl.setForeground(MUTED); p.add(liveTickLbl);
        return p;
    }

    private JPanel buildBottomBar() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PANEL_BG);
        root.putClientProperty("theme.role", "panel");
        root.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID));

        JPanel actionRow = new JPanel(new BorderLayout());
        actionRow.setBackground(PANEL_BG);
        actionRow.putClientProperty("theme.role", "panel");
        actionRow.setBorder(new EmptyBorder(0, 6, 0, 8));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        leftActions.setBackground(PANEL_BG);
        leftActions.putClientProperty("theme.role", "panel");

        JLabel actionTitle = lbl("Действия:");
        actionTitle.setBorder(new EmptyBorder(0, 0, 0, 4));
        leftActions.add(actionTitle);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        actions.setBackground(PANEL_BG);
        actions.putClientProperty("theme.role", "panel");

        analyzeBtn.setPreferredSize(new Dimension(122, 30));
        forecastBtn.setPreferredSize(new Dimension(112, 30));
        radarBtn.setPreferredSize(new Dimension(96, 30));
        backtestBtn.setPreferredSize(new Dimension(112, 30));
        infraBtn.setPreferredSize(new Dimension(96, 30));
        accountBtn.setPreferredSize(new Dimension(260, 30));
        tradePlanBtn.setPreferredSize(new Dimension(138, 30));
        strategiesBtn.setPreferredSize(new Dimension(126, 30));
        helpBtn.setText(I18n.t("Справка"));
        helpBtn.setPreferredSize(new Dimension(104, 30));

        UIUtils.stylePrimaryButton(analyzeBtn, new Color(41, 98, 255));
        UIUtils.stylePrimaryButton(backtestBtn, new Color(255, 172, 28));
        UIUtils.stylePrimaryButton(radarBtn, new Color(0, 188, 212));
        UIUtils.stylePrimaryButton(infraBtn, new Color(75, 85, 99));
        UIUtils.stylePrimaryButton(accountBtn, new Color(76, 167, 255, 234));
        UIUtils.stylePrimaryButton(tradePlanBtn, new Color(38, 166, 154));
        UIUtils.stylePrimaryButton(strategiesBtn, new Color(124, 77, 255));
        UIUtils.stylePrimaryButton(forecastBtn, new Color(124, 77, 255));
        UIUtils.stylePrimaryButton(helpBtn, new Color(50, 55, 70));
        forecastBtn.setEnabled(false);
        tradePlanBtn.setVisible(false);
        tradePlanBtn.setEnabled(false);
        helpBtn.addActionListener(e -> app.ui.HelpDialog.showHelp(this));

        aiForecastBtn.setPreferredSize(new Dimension(120, 30));
        UIUtils.stylePrimaryButton(aiForecastBtn, new Color(80, 40, 140));
        aiForecastBtn.addActionListener(e -> app.ui.AiForecastDialog.show(this, chart.getResult()));

        JButton symbolsBtn = new JButton(I18n.t("Монеты"));
        symbolsBtn.setPreferredSize(new Dimension(96, 30));
        UIUtils.stylePrimaryButton(symbolsBtn, new Color(50, 55, 70));
        symbolsBtn.addActionListener(e -> openSymbolsDialog());

        actions.add(backtestBtn);
        actions.add(radarBtn);
        actions.add(infraBtn);
        actions.add(accountBtn);
        actions.add(tradePlanBtn);
        actions.add(forecastBtn);
        actions.add(aiForecastBtn);
        actions.add(analyzeBtn, 0);
        actions.add(symbolsBtn);
        actions.add(helpBtn);

        leftActions.add(strategiesBtn);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setBackground(PANEL_BG);
        statusRow.putClientProperty("theme.role", "panel");
        statusRow.setBorder(new EmptyBorder(0, 8, 3, 12));
        statusLbl.setForeground(MUTED);
        statusLbl.putClientProperty("theme.fg", "muted");
        statusLbl.setFont(UIUtils.captionFont(11.2f));
        statusRow.add(statusLbl, BorderLayout.WEST);
        strategySummaryLbl.setForeground(MUTED);
        strategySummaryLbl.setFont(UIUtils.captionFont(11.4f));
        strategySummaryLbl.setHorizontalAlignment(SwingConstants.CENTER);
        strategySummaryLbl.setBorder(new EmptyBorder(0, 12, 0, 12));
        statusRow.add(strategySummaryLbl, BorderLayout.CENTER);
        JLabel hint = new JLabel(I18n.t("Колесо — зум  •  ЛКМ + перетаскивание — панорама  •  2× клик по зоне — детали"));
        hint.setForeground(MUTED);
        hint.putClientProperty("theme.fg", "muted");
        hint.setFont(UIUtils.captionFont(11.1f));
        statusRow.add(hint, BorderLayout.EAST);

        actionRow.add(leftActions, BorderLayout.WEST);
        actionRow.add(actions, BorderLayout.EAST);

        root.add(actionRow, BorderLayout.NORTH);
        root.add(statusRow, BorderLayout.SOUTH);
        return root;
    }

    // ── Symbol list persistence ──────────────────────────────────────────────

    private String[] loadSavedSymbols() {
        String csv = UiSettings.get().getString(SYMBOLS_SETTINGS_KEY, DEFAULT_SYMBOLS_CSV);
        if (csv == null || csv.isBlank()) csv = DEFAULT_SYMBOLS_CSV;
        String[] parts = csv.split(",");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim().toUpperCase();
            if (!s.isBlank()) result.add(s);
        }
        return result.isEmpty()
                ? DEFAULT_SYMBOLS_CSV.split(",")
                : result.toArray(new String[0]);
    }

    private void saveSymbols(String[] symbols) {
        UiSettings.get().putString(SYMBOLS_SETTINGS_KEY,
                symbols == null ? DEFAULT_SYMBOLS_CSV : String.join(",", symbols));
    }

    private void applySymbols(String[] updated) {
        SYMBOLS = updated;
        saveSymbols(updated);
        symbolBox.setModel(new DefaultComboBoxModel<>(SYMBOLS));
        statusLbl.setText("Список монет обновлён: " + updated.length + " инструментов.");
    }

    private void openSymbolsDialog() {
        JDialog dialog = new JDialog(this, "Список монет", true);
        dialog.setSize(420, 540);
        dialog.setMinimumSize(new Dimension(360, 400));
        dialog.setLocationRelativeTo(this);
        AppIcons.install(dialog);
        WindowsTitleBar.applyAppTheme(dialog);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String s : SYMBOLS) listModel.addElement(s);

        JList<String> list = new JList<>(listModel);
        list.setBackground(BG);
        list.setForeground(FG);
        list.setSelectionBackground(ACCENT.darker());
        list.setSelectionForeground(Color.WHITE);
        list.setFont(UIUtils.bodyFont(13f));
        list.setFixedCellHeight(28);
        list.setBorder(new EmptyBorder(4, 8, 4, 8));
        JScrollPane scroll = new JScrollPane(list);
        UIUtils.styleScrollPane(scroll, GRID);

        JTextField inputField = new JTextField();
        inputField.setBackground(FIELD_BG);
        inputField.setForeground(FG);
        inputField.setCaretColor(FG);
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(4, 8, 4, 8)));
        inputField.setFont(UIUtils.bodyFont(13f));
        inputField.setPreferredSize(new Dimension(180, 32));
        inputField.setToolTipText("Введите тикер, например BTCUSDT");

        JButton addBtn = new JButton("+ Добавить");
        UIUtils.stylePrimaryButton(addBtn, BULL);
        addBtn.setPreferredSize(new Dimension(120, 32));

        Runnable doAdd = () -> {
            String sym = inputField.getText().trim().toUpperCase();
            if (sym.isBlank()) return;
            if (!sym.endsWith("USDT") && !sym.endsWith("BTC") && !sym.endsWith("ETH")) sym += "USDT";
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).equalsIgnoreCase(sym)) {
                    list.setSelectedIndex(i);
                    list.ensureIndexIsVisible(i);
                    inputField.setText("");
                    return;
                }
            }
            listModel.addElement(sym);
            list.setSelectedIndex(listModel.size() - 1);
            list.ensureIndexIsVisible(listModel.size() - 1);
            inputField.setText("");
        };
        addBtn.addActionListener(e -> doAdd.run());
        inputField.addActionListener(e -> doAdd.run());

        JButton removeBtn = new JButton("✕ Удалить");
        UIUtils.stylePrimaryButton(removeBtn, BEAR);
        removeBtn.setPreferredSize(new Dimension(110, 32));
        removeBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || listModel.size() <= 1) return;
            listModel.remove(idx);
            list.setSelectedIndex(Math.min(idx, listModel.size() - 1));
        });

        JButton upBtn = new JButton("↑");
        JButton downBtn = new JButton("↓");
        UIUtils.stylePrimaryButton(upBtn, FIELD_BG);
        UIUtils.stylePrimaryButton(downBtn, FIELD_BG);
        upBtn.setPreferredSize(new Dimension(48, 32));
        downBtn.setPreferredSize(new Dimension(48, 32));
        upBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx <= 0) return;
            String val = listModel.remove(idx);
            listModel.add(idx - 1, val);
            list.setSelectedIndex(idx - 1);
        });
        downBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || idx >= listModel.size() - 1) return;
            String val = listModel.remove(idx);
            listModel.add(idx + 1, val);
            list.setSelectedIndex(idx + 1);
        });

        JButton resetBtn = new JButton("Сбросить");
        UIUtils.stylePrimaryButton(resetBtn, MUTED);
        resetBtn.setPreferredSize(new Dimension(110, 32));
        resetBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Вернуть список монет по умолчанию?", "Сброс",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            listModel.clear();
            for (String s : DEFAULT_SYMBOLS_CSV.split(",")) listModel.addElement(s.trim());
        });

        JButton applyBtn = new JButton("Применить");
        UIUtils.stylePrimaryButton(applyBtn, ACCENT);
        applyBtn.setPreferredSize(new Dimension(110, 32));
        applyBtn.addActionListener(e -> {
            String[] updated = new String[listModel.size()];
            for (int i = 0; i < listModel.size(); i++) updated[i] = listModel.get(i);
            applySymbols(updated);
            dialog.dispose();
        });

        JButton cancelBtn = new JButton("Отмена");
        UIUtils.stylePrimaryButton(cancelBtn, FIELD_BG);
        cancelBtn.setPreferredSize(new Dimension(90, 32));
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Layout
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("Инструменты для анализа и радара");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.titleFont(15.5f));
        JLabel hint = new JLabel("Изменения применятся сразу и сохранятся при перезапуске.");
        hint.setForeground(MUTED);
        hint.setFont(UIUtils.captionFont(11.4f));
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(3));
        header.add(hint);

        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.setOpaque(false);
        addRow.add(inputField);
        addRow.add(addBtn);

        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editRow.setOpaque(false);
        editRow.add(upBtn);
        editRow.add(downBtn);
        editRow.add(removeBtn);
        editRow.add(Box.createHorizontalStrut(8));
        editRow.add(resetBtn);

        JPanel inputArea = new JPanel();
        inputArea.setOpaque(false);
        inputArea.setLayout(new BoxLayout(inputArea, BoxLayout.Y_AXIS));
        inputArea.add(addRow);
        inputArea.add(Box.createVerticalStrut(6));
        inputArea.add(editRow);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(cancelBtn);
        footer.add(applyBtn);

        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(inputArea, BorderLayout.SOUTH);

        JPanel wrapper = new JPanel(new BorderLayout(0, 10));
        wrapper.setBackground(PANEL_BG);
        wrapper.add(root, BorderLayout.CENTER);
        wrapper.add(footer, BorderLayout.SOUTH);
        wrapper.setBorder(new EmptyBorder(0, 0, 10, 10));

        dialog.setContentPane(wrapper);
        dialog.setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private JLabel lbl(String t) {
        JLabel l = new JLabel(I18n.t(t));
        l.putClientProperty("i18n.source", t);
        l.setForeground(MUTED);
        l.putClientProperty("theme.fg", "muted");
        l.setFont(UIUtils.labelFont(12.2f));
        return l;
    }

    private BinanceClient.Market selectedMarket() {
        return BinanceClient.marketFromLabel((String) marketBox.getSelectedItem());
    }

    private BacktestAssumptions selectedBacktestAssumptions() {
        Object selected = backtestModeBox.getSelectedItem();
        String modeText = selected == null ? "" : selected.toString().toLowerCase(Locale.ROOT);
        boolean tickMode = backtestModeBox.getSelectedIndex() == 1
                || modeText.contains("тиков")
                || modeText.contains("tick");
        int minScore = selectedScoreFilter();
        boolean requireHtfDisplacement = htfDisplacementBox.getSelectedIndex() == 1;
        double tp1 = 0.40;
        double tp2 = 0.40;
        double tp3 = 0.20;
        Object tpProfile = tpProfileBox.getSelectedItem();
        if (tpProfile != null && tpProfile.toString().contains("50/30/20")) {
            tp1 = 0.50;
            tp2 = 0.30;
            tp3 = 0.20;
        }
        return BacktestAssumptions.tuned(tickMode, minScore, requireHtfDisplacement, tp1, tp2, tp3);
    }

    private int selectedScoreFilter() {
        Object selected = scoreFilterBox.getSelectedItem();
        if (selected == null) return 55;
        String text = selected.toString();
        if (text.contains("85")) return 85;
        if (text.contains("80")) return 80;
        if (text.contains("70")) return 70;
        return 55;
    }

    private int selectedBacktestDepth() {
        Object selected = backtestDepthBox.getSelectedItem();
        if (selected == null) return 1000;
        String digits = selected.toString().replaceAll("[^0-9]", "");
        if (digits.isBlank()) return 1000;
        try {
            return Math.max(300, Math.min(10_000, Integer.parseInt(digits)));
        } catch (NumberFormatException ex) {
            return 1000;
        }
    }

    private void addOrSelectPairTab(AnalysisResult result, String htf,
                                    String trendMethod, BinanceClient.Market market) {
        String symbol = result.symbol;
        String ltf = result.ltfInterval;
        PairTab next = new PairTab(symbol, htf, ltf, trendMethod, market);
        for (PairTab tab : openPairTabs) {
            if (tab.key().equals(next.key())) {
                tab.result = result;
                if (chartResultMatches(result)) tab.viewState = chart.captureViewState();
                attachStickyForecastToTab(tab);
                selectPairTab(tab, false);
                updatePortfolioRisk();
                return;
            }
        }
        if (openPairTabs.size() >= 12) {
            PairTab removed = openPairTabs.remove(0);
            pairTabsPanel.remove(removed.component);
        }
        next.result = result;
        if (chartResultMatches(result)) next.viewState = chart.captureViewState();
        attachStickyForecastToTab(next);
        openPairTabs.add(next);
        next.component = buildPairTabComponent(next);
        pairTabsPanel.add(next.component);
        selectPairTab(next, false);
        updatePortfolioRisk();
        pairTabsPanel.revalidate();
        pairTabsPanel.repaint();
    }

    private JPanel buildPairTabComponent(PairTab tab) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(new EmptyBorder(3, 9, 3, 4));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel title = new JLabel(tab.symbol + "  " + tab.ltf);
        title.setFont(UIUtils.labelFont(12.2f));
        JButton close = new JButton("x");
        close.setFont(UIUtils.labelFont(10.6f));
        close.setMargin(new Insets(0, 4, 0, 4));
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setContentAreaFilled(false);
        close.setForeground(MUTED);

        panel.add(title, BorderLayout.CENTER);
        panel.add(close, BorderLayout.EAST);
        panel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!suppressTabEvents) selectPairTab(tab, true);
            }
        });
        close.addActionListener(e -> closePairTab(tab));
        tab.label = title;
        return panel;
    }

    private void selectPairTab(PairTab tab, boolean run) {
        if (tab == null) return;
        if (activePairTab != tab) rememberActiveTabView();
        activePairTab = tab;
        restoreTabForecast(tab);
        refreshPairTabStyles();
        updateTradePlanButtonState(activePairTab == null ? null : activePairTab.result);
        if (!run) return;
        suppressTabEvents = true;
        suppressControlEvents = true;
        try {
            symbolBox.setModel(new DefaultComboBoxModel<>(SYMBOLS));
            marketBox.setEnabled(true);
            backtestModeBox.setEnabled(true);
            accountBtn.setEnabled(true);
            symbolBox.setSelectedItem(tab.symbol);
            htfBox.setSelectedItem(tab.htf);
            ltfBox.setSelectedItem(tab.ltf);
            trendMethodBox.setSelectedItem(tab.trendMethod);
            marketBox.setSelectedItem(tab.market.label);
        } finally {
            suppressControlEvents = false;
            suppressTabEvents = false;
        }
        if (!restoreCachedPairTab(tab)) {
            runAnalysis();
        }
    }

    private boolean restoreCachedPairTab(PairTab tab) {
        if (tab == null || tab.result == null) return false;
        if (analysisRunning) {
            analysisGeneration++;
            analysisRunning = false;
            analyzeBtn.setEnabled(true);
        }
        AnalysisResult cached = tab.result;
        stopLiveMarketData();
        chart.setData(cached);
        if (tab.viewState != null) {
            chart.restoreViewState(tab.viewState);
        }
        side.update(cached);
        applyCurrentChartInsight(cached, tab.htf, tab.ltf, tab.market, false);
        restoreTabForecast(tab);
        refreshPaperSnapshotFromCache();
        startLiveMarketData(cached, tab.market);
        forecastBtn.setEnabled(canShowForecast(cached));
        updateTradePlanButtonState(cached);
        StrategySignal strategy = refreshSelectedStrategy(cached);
        updateStrategySummary(cached);
        int candles = cached.candles == null ? 0 : cached.candles.size();
        String strategySuffix = strategy == null ? "" : " · стратегия " + strategy.statusText();
        statusLbl.setText("Вкладка из памяти · " + cached.symbol + " · " + cached.ltfInterval
                + " · свечей: " + candles + strategySuffix);
        progressPanel.hideAnimated();
        tickPrice();
        maybeRefreshCachedPairTab(tab);
        return true;
    }

    private void maybeRefreshCachedPairTab(PairTab tab) {
        if (!cachedPairTabNeedsRefresh(tab) || analysisRunning) return;
        long now = System.currentTimeMillis();
        String key = tab.key();
        long previous = pairTabRefreshStartedAt.getOrDefault(key, 0L);
        if (now - previous < 90_000L) return;
        pairTabRefreshStartedAt.put(key, now);

        statusLbl.setText("Вкладка из памяти · тихо догружаю свежие свечи...");
        new SwingWorker<AnalysisResult, Void>() {
            @Override
            protected AnalysisResult doInBackground() throws Exception {
                List<Candle> htf = BinanceClient.fetchCandles(tab.symbol, tab.htf, HTF_LIMIT, tab.market);
                List<Candle> ltf = BinanceClient.fetchCandles(tab.symbol, tab.ltf, LTF_LIMIT, tab.market);
                AnalysisResult refreshed = SMCAnalyzer.analyze(tab.symbol, tab.ltf, htf, ltf, tab.trendMethod, tab.market);
                refreshed.currentPrice = BinanceClient.fetchLastPrice(tab.symbol, tab.market);
                paperMarkPrices.put(tab.symbol.toUpperCase(Locale.ROOT), refreshed.currentPrice);
                return refreshed;
            }

            @Override
            protected void done() {
                try {
                    AnalysisResult refreshed = get();
                    tab.result = refreshed;
                    attachStickyForecastToTab(tab);
                    if (tab != activePairTab) return;
                    ChartPanel.ViewState state = tab.viewState;
                    chart.setData(refreshed);
                    if (state != null) chart.restoreViewState(state);
                    side.update(refreshed);
                    applyCurrentChartInsight(refreshed, tab.htf, tab.ltf, tab.market, false);
                    refreshPaperSnapshotFromCache();
                    forecastBtn.setEnabled(canShowForecast(refreshed));
                    updateTradePlanButtonState(refreshed);
                    StrategySignal strategy = refreshSelectedStrategy(refreshed);
                    updateStrategySummary(refreshed);
                    String suffix = strategy == null ? "" : " · стратегия " + strategy.statusText();
                    statusLbl.setText("Вкладка обновлена в фоне · " + refreshed.symbol + " · "
                            + refreshed.ltfInterval + suffix);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    LOG.debug("Cached tab refresh failed for {}: {}", tab.key(), cause.toString());
                    if (tab == activePairTab) {
                        statusLbl.setText("Вкладка из памяти · фон. обновление не удалось: "
                                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                    }
                }
            }
        }.execute();
    }

    private boolean cachedPairTabNeedsRefresh(PairTab tab) {
        if (tab == null || tab.result == null || tab.result.candles == null || tab.result.candles.isEmpty()) {
            return false;
        }
        Candle last = tab.result.candles.get(tab.result.candles.size() - 1);
        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(tab.ltf));
        return System.currentTimeMillis() - last.time > intervalMs * 2L;
    }

    private void rememberActiveTabView() {
        if (activePairTab == null) return;
        AnalysisResult current = chart.getResult();
        if (!tabMatchesResult(activePairTab, current)) return;
        activePairTab.result = current;
        activePairTab.viewState = chart.captureViewState();
        attachStickyForecastToTab(activePairTab);
    }

    private boolean chartResultMatches(AnalysisResult result) {
        AnalysisResult current = chart.getResult();
        if (current == result) return true;
        if (current == null || result == null) return false;
        return current.symbol != null
                && result.symbol != null
                && current.symbol.equalsIgnoreCase(result.symbol)
                && current.ltfInterval != null
                && current.ltfInterval.equalsIgnoreCase(result.ltfInterval);
    }

    private boolean tabMatchesResult(PairTab tab, AnalysisResult result) {
        return tab != null
                && result != null
                && result.symbol != null
                && result.ltfInterval != null
                && tab.symbol.equalsIgnoreCase(result.symbol)
                && tab.ltf.equalsIgnoreCase(result.ltfInterval);
    }

    private void attachStickyForecastToTab(PairTab tab) {
        if (tab == null || !forecastState.hasPlan() || forecastState.stickySymbol().isBlank()) return;
        if (!forecastState.stickySymbol().equalsIgnoreCase(tab.symbol)) return;
        tab.forecastPlan = forecastState.plan();
    }

    private void restoreTabForecast(PairTab tab) {
        if (tab == null) return;
        if (tab.forecastPlan != null) {
            forecastState.stick(tab.symbol, tab.forecastPlan);
            forecastBtn.setSelected(true);
            chart.showRadarForecastPlan(tab.symbol, tab.forecastPlan);
            chart.setShowForecast(true);
            return;
        }
        forecastState.clear();
        forecastBtn.setSelected(false);
        chart.clearRadarForecastPlan();
        chart.setShowForecast(false);
    }

    private void clearForecastOverlay() {
        forecastState.clear();
        forecastBtn.setSelected(false);
        chart.clearRadarForecastPlan();
        chart.setShowForecast(false);
        forecastBtn.setEnabled(canShowForecast(chart.getResult()));
        updateTradePlanButtonState(chart.getResult());
        updateStrategySummary(chart.getResult());
    }

    private void closePairTab(PairTab tab) {
        if (tab == activePairTab) rememberActiveTabView();
        int index = openPairTabs.indexOf(tab);
        openPairTabs.remove(tab);
        pairTabsPanel.remove(tab.component);
        if (tab == activePairTab) {
            PairTab next = openPairTabs.isEmpty() ? null : openPairTabs.get(Math.max(0, index - 1));
            activePairTab = null;
            if (next != null) selectPairTab(next, true);
            else updateTradePlanButtonState(null);
        }
        refreshPairTabStyles();
        pairTabsPanel.revalidate();
        pairTabsPanel.repaint();
    }

    private void refreshPairTabStyles() {
        for (PairTab tab : openPairTabs) {
            boolean active = tab == activePairTab;
            Color bg = tab.correlationBlocked
                    ? new Color(55, 26, 33)
                    : active ? PANEL_BG : BG;
            tab.component.setBackground(bg);
            tab.component.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, active ? 2 : 1, 0,
                            tab.correlationBlocked ? BEAR : active ? ACCENT : GRID),
                    new EmptyBorder(3, 9, 3, 4)));
            tab.label.setForeground(tab.correlationBlocked ? BEAR : active ? FG : MUTED);
            tab.component.setToolTipText(tab.correlationBlocked ? tab.blockReason : tab.key());
        }
    }

    public void refreshTheme() {
        getContentPane().setBackground(BG);
        refreshGenericTheme(getContentPane());
        restyleThemeAwareControls();
        if (topStackPanel != null) {
            topStackPanel.setBackground(PANEL_BG);
            for (Component c : topStackPanel.getComponents()) {
                if (c instanceof JPanel panel) panel.setBackground(PANEL_BG);
            }
        }
        if (bottomBarPanel != null) {
            bottomBarPanel.setBackground(PANEL_BG);
            for (Component c : bottomBarPanel.getComponents()) {
                if (c instanceof JPanel panel) panel.setBackground(PANEL_BG);
            }
        }
        if (pairTabsPanel != null) {
            pairTabsPanel.setBackground(RAIL_BG);
        }
        if (masterSideScroll != null) {
            masterSideScroll.setBackground(PANEL_BG);
            masterSideScroll.getViewport().setBackground(PANEL_BG);
            masterSideScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        }
        if (sidePaneContainer != null) {
            sidePaneContainer.setBackground(PANEL_BG);
            sidePaneContainer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, GRID));
        }
        if (sideCompactRail != null) sideCompactRail.setBackground(PANEL_BG);
        if (mainSplit != null) mainSplit.setBackground(BG);
        if (progressBar != null) {
            progressBar.setForeground(ACCENT);
            progressBar.setBackground(BG);
            progressBar.setBorder(BorderFactory.createLineBorder(GRID));
            progressBar.setUI(new app.ui.ModernProgressBarUI());
        }
        if (side != null) side.refreshThemeComponents();
        
        refreshPairTabStyles();
        if (chart != null) {
            chart.setBackground(BG);
            chart.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, GRID));
            chart.repaint();
        }
        updateAccountButton();
        revalidate();
        repaint();
        applyNativeTitleBarTheme();
    }

    public void refreshLanguage() {
        setTitle(I18n.english() ? "SMC Chart - Smart Money Concept Analyzer" : "SMC Chart — Smart Money Concept Analyzer");
        refreshLocalizedComboModels();
        I18n.refreshTree(getContentPane());
        if (side != null) side.refreshLanguageComponents();
        helpBtn.setText(I18n.t("Справка"));
        analyzeBtn.setText(I18n.t("Обновить"));
        forecastBtn.setText(I18n.t("Прогноз"));
        aiForecastBtn.setText(I18n.t("ИИ Прогноз"));
        backtestBtn.setText(I18n.t("Бэктест"));
        radarBtn.setText(I18n.t("Радар"));
        infraBtn.setText(I18n.t("Инфра"));
        tradePlanBtn.setText(I18n.t("План торговли"));
        strategiesBtn.setText(I18n.t("Стратегии"));
        chartLongBtn.setText(I18n.t("Открыть лонг"));
        chartShortBtn.setText(I18n.t("Открыть шорт"));
        chartCloseBtn.setText(I18n.t("Закрыть позицию"));
        updateAccountButton();
        updateStrategySummary(chart == null ? null : chart.getResult());
        revalidate();
        repaint();
    }

    private void refreshLocalizedComboModels() {
        if (I18n.english()) {
            replaceComboItems(backtestModeBox, new String[]{"Fast", "Tick"});
            replaceComboItems(backtestDepthBox, new String[]{"1000 candles", "2500 candles", "5000 candles", "10000 candles"});
            replaceComboItems(htfDisplacementBox, new String[]{"HTF disp: off", "HTF disp: on"});
        } else {
            replaceComboItems(backtestModeBox, new String[]{"Быстрый", "Тиковый"});
            replaceComboItems(backtestDepthBox, new String[]{"1000 свечей", "2500 свечей", "5000 свечей", "10000 свечей"});
            replaceComboItems(htfDisplacementBox, new String[]{"HTF disp: выкл", "HTF disp: вкл"});
        }
    }

    private void replaceComboItems(JComboBox<String> box, String[] items) {
        int selected = Math.max(0, box.getSelectedIndex());
        boolean oldSuppress = suppressControlEvents;
        suppressControlEvents = true;
        try {
            box.setModel(new DefaultComboBoxModel<>(items));
            box.setSelectedIndex(Math.min(selected, Math.max(0, items.length - 1)));
        } finally {
            suppressControlEvents = oldSuppress;
        }
    }

    private void restyleThemeAwareControls() {
        UIUtils.styleComboBox(symbolBox, BG, FG, GRID);
        UIUtils.styleComboBox(marketBox, BG, FG, GRID);
        UIUtils.styleComboBox(htfBox, BG, FG, GRID);
        UIUtils.styleComboBox(trendMethodBox, BG, FG, GRID);
        UIUtils.styleComboBox(ltfBox, BG, FG, GRID);
        UIUtils.styleComboBox(backtestModeBox, BG, FG, GRID);
        UIUtils.styleComboBox(backtestDepthBox, BG, FG, GRID);
        UIUtils.styleComboBox(scoreFilterBox, BG, FG, GRID);
        UIUtils.styleComboBox(htfDisplacementBox, BG, FG, GRID);
        UIUtils.styleComboBox(tpProfileBox, BG, FG, GRID);

        UIUtils.stylePrimaryButton(analyzeBtn, ACCENT);
        UIUtils.stylePrimaryButton(backtestBtn, new Color(245, 166, 35));
        UIUtils.stylePrimaryButton(radarBtn, CYAN);
        UIUtils.stylePrimaryButton(infraBtn, mutedButton());
        UIUtils.stylePrimaryButton(accountBtn, new Color(76, 167, 255));
        UIUtils.stylePrimaryButton(tradePlanBtn, BULL);
        UIUtils.stylePrimaryButton(strategiesBtn, PURPLE);
        UIUtils.stylePrimaryButton(forecastBtn, PURPLE);
        UIUtils.stylePrimaryButton(aiForecastBtn, new Color(80, 40, 140));
        UIUtils.stylePrimaryButton(helpBtn, mutedButton());
        UIUtils.stylePrimaryButton(sideCompactBtn, mutedButton());
        UIUtils.stylePrimaryButton(chartFullscreenBtn, mutedButton());
        UIUtils.stylePrimaryButton(chartCloseBtn, new Color(245, 158, 11));
        UIUtils.stylePrimaryButton(chartLongBtn, BULL);
        UIUtils.stylePrimaryButton(chartShortBtn, BEAR);

        statusLbl.setForeground(MUTED);
        updateStrategySummary(chart == null ? null : chart.getResult());
        if (liveTickLbl.getForeground() != BULL && liveTickLbl.getForeground() != BEAR) {
            liveTickLbl.setForeground(MUTED);
        }
        accountStatusLbl.setForeground(accountSnapshotError.isBlank()
                ? ExchangeAccountSettings.current().configured() ? BULL : MUTED
                : BEAR);
    }

    private Color mutedButton() {
        return new Color(
                Math.min(255, MUTED.getRed() + 6),
                Math.min(255, MUTED.getGreen() + 10),
                Math.min(255, MUTED.getBlue() + 16));
    }

    private void updatePortfolioRisk() {
        for (PairTab tab : openPairTabs) {
            tab.correlationBlocked = false;
            tab.blockReason = "";
        }
        for (PairTab tab : openPairTabs) {
            if (!hasTradePlan(tab)) continue;
            for (PairTab other : openPairTabs) {
                if (tab == other || !hasTradePlan(other)) continue;
                if (tab.result.plan.longSide != other.result.plan.longSide) continue;
                double corr = correlationEngine.pearsonReturns(tab.result, other.result, 120);
                if (Math.abs(corr) < 0.90) continue;
                if (other.result.smcScore > tab.result.smcScore
                        || (other.result.smcScore == tab.result.smcScore && other.symbol.compareTo(tab.symbol) < 0)) {
                    tab.correlationBlocked = true;
                    tab.blockReason = "Portfolio risk: corr " + String.format("%.2f", corr)
                            + " with " + other.symbol + ". Stronger SMC candidate: "
                            + other.symbol + " " + other.result.smcScore + "/100";
                }
            }
        }
        refreshPairTabStyles();
        if (activePairTab != null && activePairTab.correlationBlocked) {
            statusLbl.setText(activePairTab.blockReason);
        }
    }

    private boolean hasTradePlan(PairTab tab) {
        return tab != null && tab.result != null && tab.result.plan != null;
    }

    private void beginProgress(String message) {
        progressPanel.showAnimated();
        updateProgress(1, message);
    }

    private void updateProgress(int percent, String message) {
        int value = Math.max(0, Math.min(100, percent));
        Runnable ui = () -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(value);
            progressBar.setString(value + "%");
            if (message != null && !message.isBlank()) statusLbl.setText(message);
        };
        if (SwingUtilities.isEventDispatchThread()) ui.run();
        else SwingUtilities.invokeLater(ui);
    }

    private void finishProgress(String message) {
        updateProgress(100, message);
        Timer hideTimer = new Timer(520, e -> progressPanel.hideAnimated());
        hideTimer.setRepeats(false);
        hideTimer.start();
    }

    private void startLivePriceTimer() {
        priceTimer = new Timer(2500, e -> tickPrice());
        priceTimer.setInitialDelay(1500);
        priceTimer.start();
    }

    private void startLiveUiThrottleTimer() {
        liveUiThrottleTimer = new Timer(16, e -> flushPendingLivePrice());
        liveUiThrottleTimer.setInitialDelay(16);
        liveUiThrottleTimer.start();
    }

    private void queueLivePrice(String symbol, BinanceClient.Market market, double price, String source) {
        if (symbol == null || symbol.isBlank() || !Double.isFinite(price) || price <= 0.0) return;
        if (source != null && source.toLowerCase(Locale.ROOT).startsWith("ws")) {
            lastWebSocketPriceAt = System.currentTimeMillis();
        }
        synchronized (pendingLivePriceLock) {
            pendingLiveSymbol = symbol.trim().toUpperCase(Locale.ROOT);
            pendingLiveMarket = market == null ? BinanceClient.Market.FUTURES : market;
            pendingLivePrice = price;
            pendingLiveSource = source == null || source.isBlank() ? "live" : source;
        }
    }

    private void flushPendingLivePrice() {
        String symbol;
        BinanceClient.Market market;
        double price;
        String source;
        synchronized (pendingLivePriceLock) {
            if (!Double.isFinite(pendingLivePrice) || pendingLivePrice <= 0.0) return;
            symbol = pendingLiveSymbol;
            market = pendingLiveMarket;
            price = pendingLivePrice;
            source = pendingLiveSource;
            pendingLivePrice = Double.NaN;
            pendingLiveSource = "";
        }
        AnalysisResult current = chart.getResult();
        if (current == null || current.symbol == null
                || !symbol.equalsIgnoreCase(current.symbol)
                || selectedMarket() != market) {
            return;
        }
        double oldPrice = current.currentPrice;
        chart.applyLivePrice(price);
        paperMarkPrices.put(symbol.toUpperCase(Locale.ROOT), price);
        long now = System.currentTimeMillis();
        if (now - lastPaperSnapshotUiAt >= 500L) {
            refreshPaperSnapshotFromCache();
            lastPaperSnapshotUiAt = now;
        }
        if (now - lastLiveChartInsightAt >= 1000L) {
            applyCurrentChartInsight(current, (String) htfBox.getSelectedItem(), current.ltfInterval, market, false);
            lastLiveChartInsightAt = now;
        }
        if (now - lastSidePriceUiAt >= 120L) {
            side.updatePrice(price, oldPrice);
            liveTickLbl.setText(source + " · " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            liveTickLbl.setForeground(price >= oldPrice ? BULL : BEAR);
            lastSidePriceUiAt = now;
        }
    }

    private void startAccountSnapshotTimer() {
        accountSnapshotTimer = new Timer(60_000, e -> refreshAccountSnapshot(false));
        accountSnapshotTimer.setInitialDelay(1200);
        accountSnapshotTimer.start();
    }

    private void refreshAccountSnapshot(boolean force) {
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        if (!settings.configured()) {
            accountSnapshot = null;
            accountSnapshotError = "";
            updateAccountButton();
            return;
        }
        if (accountSnapshotRunning) return;
        if (!force && settings.mode == ExchangeAccountSettings.Mode.PAPER && accountSnapshot != null) return;

        accountSnapshotRunning = true;
        accountSnapshotError = "";
        updateAccountButton();

        ExchangeAccountSettings requested = settings.copy();
        new SwingWorker<AccountSnapshot, Void>() {
            @Override
            protected AccountSnapshot doInBackground() throws Exception {
                if (requested.mode == ExchangeAccountSettings.Mode.PAPER) {
                    return paperAccountSnapshot(requested);
                }
                ExecutionGateway gateway = ExchangeGatewayFactory.create(requested);
                return gateway.accountSnapshot();
            }

            @Override
            protected void done() {
                accountSnapshotRunning = false;
                if (!sameAccount(requested, ExchangeAccountSettings.current())) {
                    updateAccountButton();
                    return;
                }
                try {
                    accountSnapshot = get();
                    accountSnapshotError = "";
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    accountSnapshot = null;
                    accountSnapshotError = cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage();
                }
                updateAccountButton();
            }
        }.execute();
    }

    private void reconcileExecutionStateAsync(String reason) {
        if (executionReconcileRunning) return;
        ExchangeAccountSettings account = ExchangeAccountSettings.current();
        if (!account.configured()) return;
        executionReconcileRunning = true;
        RiskSettings settings = RiskSettings.load();
        ExchangeAccountSettings requested = account.copy();
        new SwingWorker<ExecutionLedger.ReconciliationResult, Void>() {
            @Override
            protected ExecutionLedger.ReconciliationResult doInBackground() throws Exception {
                ExecutionGateway gateway = ExchangeGatewayFactory.create(requested);
                HftExecutionService service = new HftExecutionService(gateway,
                        settings.toRiskGate(), ExecutionJournal.defaultJournal());
                service.setAutoProtectiveOrders(settings.autoProtectiveOrders);
                return service.reconcileOpenOrders(settings.autoCancelOrphans);
            }

            @Override
            protected void done() {
                executionReconcileRunning = false;
                if (!sameAccount(requested, ExchangeAccountSettings.current())) return;
                try {
                    ExecutionLedger.ReconciliationResult result = get();
                    statusLbl.setText("Reconcile: " + result.summary());
                    if (!result.clean()) {
                        accountStatusLbl.setText("Reconcile warning · " + result.summary());
                        accountStatusLbl.setForeground(YELLOW);
                    }
                    refreshAccountSnapshot(true);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLbl.setText("Reconcile error");
                    accountStatusLbl.setText("Reconcile error · " + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()));
                    accountStatusLbl.setForeground(BEAR);
                }
            }
        }.execute();
    }

    private void startLiveMarketData(AnalysisResult result, BinanceClient.Market market) {
        if (result == null || result.symbol == null || result.symbol.isBlank()) {
            stopLiveMarketData();
            return;
        }
        String symbol = result.symbol;
        String interval = result.ltfInterval;
        if (liveMarketData != null && symbol.equalsIgnoreCase(liveSymbol)
                && interval.equalsIgnoreCase(liveInterval) && market == liveMarket) {
            // Same symbol/interval/market is already streaming (this runs on every automatic
            // re-analysis, ~once per closed candle) -- keep the existing WebSocket alive instead
            // of tearing down and rebuilding the whole connection. Each rebuild used to create a
            // brand new java.net.http.HttpClient; on Java 17 HttpClient has no explicit close()
            // (that only arrived in JDK 21), so its internal selector-manager thread is only
            // reclaimed whenever GC gets around to collecting the abandoned client -- rebuilding
            // it every ~45s+ leaked selector threads faster than GC reliably cleaned them up,
            // which is what turned into the freeze/lag after a few minutes.
            return;
        }
        stopLiveMarketData();
        lastWebSocketPriceAt = 0L;
        lastSidePriceUiAt = 0L;
        lastPaperSnapshotUiAt = 0L;
        lastLiveChartInsightAt = 0L;
        liveSymbol = symbol;
        liveInterval = interval;
        liveMarket = market;
        liveMarketData = new LiveMarketDataService(symbol, market, interval, new LiveMarketDataService.Listener() {
            @Override
            public void onState(app.infrastructure.ResilientWebSocketClient.State state, String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (chart.getResult() != null && symbol.equals(chart.getResult().symbol)) {
                        liveTickLbl.setText("ws " + state.name().toLowerCase() + (reason == null || reason.isBlank() ? "" : " · " + reason));
                        liveTickLbl.setForeground(state == app.infrastructure.ResilientWebSocketClient.State.CONNECTED ? BULL : MUTED);
                    }
                });
                if (state == app.infrastructure.ResilientWebSocketClient.State.CONNECTED) {
                    reconcileExecutionStateAsync("websocket connected: " + (reason == null ? "" : reason));
                }
            }

            @Override
            public void onLastPrice(double price) {
                queueLivePrice(symbol, market, price, "ws live");
            }

            @Override
            public void onAggTrade(long aggregateId, long time, double price, double quantity, boolean buyerMaker) {
                WebSocketTickStore.appendAsync(symbol, market, new Tick(aggregateId, time, price, quantity, buyerMaker));
            }

            @Override
            public void onKline(long openTime, double open, double high, double low, double close,
                                double volume, boolean closed) {
                if (closed) scheduleClosedCandleAnalysis(symbol, interval, market, openTime);
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (chart.getResult() != null && symbol.equals(chart.getResult().symbol)) {
                        liveTickLbl.setText("ws retry");
                        liveTickLbl.setForeground(MUTED);
                    }
                });
            }
        });
        liveMarketData.start();
    }

    private void stopLiveMarketData() {
        if (liveMarketData != null) {
            liveMarketData.close();
            liveMarketData = null;
        }
        liveSymbol = "";
        liveInterval = "";
        liveMarket = null;
        synchronized (pendingLivePriceLock) {
            pendingLiveSymbol = "";
            pendingLivePrice = Double.NaN;
            pendingLiveSource = "";
        }
        lastWebSocketPriceAt = 0L;
        lastLiveChartInsightAt = 0L;
    }

    private void scheduleClosedCandleAnalysis(String symbol,
                                              String interval,
                                              BinanceClient.Market market,
                                              long openTime) {
        if (symbol == null || symbol.isBlank() || interval == null || interval.isBlank()) return;
        BinanceClient.Market safeMarket = market == null ? BinanceClient.Market.FUTURES : market;
        String key = safeMarket.label
                + "|" + symbol.toUpperCase(Locale.ROOT)
                + "|" + interval
                + "|" + openTime;
        long now = System.currentTimeMillis();
        if (key.equals(lastClosedCandleAnalysisKey) || now - lastClosedCandleAnalysisAt < 45_000L) return;
        lastClosedCandleAnalysisKey = key;
        lastClosedCandleAnalysisAt = now;
        SwingUtilities.invokeLater(() -> {
            AnalysisResult current = chart.getResult();
            if (current == null || current.symbol == null
                    || !symbol.equalsIgnoreCase(current.symbol)
                    || !interval.equalsIgnoreCase(current.ltfInterval)
                    || selectedMarket() != safeMarket
                    || analysisRunning) {
                return;
            }
            statusLbl.setText("Свеча закрылась · пересчитываю SMC-анализ...");
            Timer timer = new Timer(350, e -> {
                if (!analysisRunning) runAnalysis();
            });
            timer.setRepeats(false);
            timer.start();
        });
    }

    private void runRadar(){
            String htf = (String) htfBox.getSelectedItem();
            String ltf = (String) ltfBox.getSelectedItem();
            String trendMethod = (String) trendMethodBox.getSelectedItem();
            BinanceClient.Market market = selectedMarket();

            RadarWindow radar = new RadarWindow(this, SYMBOLS, htf, ltf, trendMethod, market, signal -> {
                suppressControlEvents = true;
                try {
                    symbolBox.setModel(new DefaultComboBoxModel<>(SYMBOLS));
                    marketBox.setEnabled(true);
                    backtestModeBox.setEnabled(true);
                    accountBtn.setEnabled(true);
                    symbolBox.setSelectedItem(signal.symbol);
                    htfBox.setSelectedItem(signal.htf);
                    ltfBox.setSelectedItem(signal.ltf);
                    trendMethodBox.setSelectedItem(signal.method);
                    marketBox.setSelectedItem(signal.market.label);
                } finally {
                    suppressControlEvents = false;
                }
                String forecastSymbol = signal.symbol == null ? "" : signal.symbol;
                forecastState.armRadar(forecastSymbol, signal.planSnapshot);
                if (signal.planSnapshot != null) chart.showRadarForecastPlan(forecastSymbol, signal.planSnapshot);
                recordRadarDecision(signal, "open_chart");
                runAnalysis();
            });
            radar.setVisible(true);
    }

    private void applyCurrentChartInsight(AnalysisResult result,
                                          String htfInterval,
                                          String ltfInterval,
                                          BinanceClient.Market market) {
        applyCurrentChartInsight(result, htfInterval, ltfInterval, market, true);
    }

    private void applyCurrentChartInsight(AnalysisResult result,
                                          String htfInterval,
                                          String ltfInterval,
                                          BinanceClient.Market market,
                                          boolean resetPanelScroll) {
        ChartInsight insight = currentChartAnalyzer.analyze(result, htfInterval, ltfInterval, market);
        chart.setChartInsight(insight);
        side.updateChartInsight(insight, resetPanelScroll);
    }

    private void openTradeAutomationPlan() {
        AnalysisResult current = chart.getResult();
        AnalysisResult planned = withEffectiveTradePlan(current);
        if (!hasEntryPoint(planned)) {
            statusLbl.setText("План торговли появится, когда на графике есть точка входа.");
            return;
        }
        app.ui.TradeAutomationDialog.showDialog(
                this,
                planned,
                (String) htfBox.getSelectedItem(),
                (String) trendMethodBox.getSelectedItem(),
                selectedMarket(),
                selectedBacktestAssumptions(),
                selectedBacktestDepth(),
                refreshed -> {
                    chart.setData(refreshed);
                    side.update(refreshed);
                    applyCurrentChartInsight(refreshed, (String) htfBox.getSelectedItem(),
                            refreshed.ltfInterval, selectedMarket());
                    addOrSelectPairTab(refreshed, (String) htfBox.getSelectedItem(),
                            (String) trendMethodBox.getSelectedItem(), selectedMarket());
                    forecastBtn.setEnabled(canShowForecast(refreshed));
                    updateTradePlanButtonState(refreshed);
                    StrategySignal strategy = refreshSelectedStrategy(refreshed);
                    updateStrategySummary(refreshed);
                    String strategySuffix = strategy == null ? "" : " · стратегия " + strategy.statusText();
                    statusLbl.setText("План торговли синхронизировал свечи · SMC " + refreshed.smcScore + "/100" + strategySuffix);
                });
    }

    private void openStrategiesDialog() {
        AnalysisResult current = withEffectiveTradePlan(chart.getResult());
        if (current == null) {
            statusLbl.setText("Стратегии появятся после загрузки графика.");
            return;
        }
        app.ui.StrategyDialog.showDialog(this, current, signal -> {
            if (signal == null) {
                selectedStrategyId = "";
                chart.setStrategySignal(null);
                statusLbl.setText("Слой стратегии убран с графика.");
                return;
            }
            selectedStrategyId = signal.id;
            chart.setStrategySignal(signal);
            String extra = signal.suitable ? "визуализация включена" : "не применять: " + firstStrategyBlocker(signal);
            statusLbl.setText("Стратегия: " + signal.name + " · " + signal.statusText() + " · " + extra);
        });
    }

    private StrategySignal refreshSelectedStrategy(AnalysisResult source) {
        if (source == null || selectedStrategyId == null || selectedStrategyId.isBlank()) {
            chart.setStrategySignal(null);
            return null;
        }
        StrategySignal signal = StrategyEngine.evaluate(selectedStrategyId, withEffectiveTradePlan(source));
        chart.setStrategySignal(signal);
        return signal;
    }

    private void updateStrategySummary(AnalysisResult source) {
        if (strategySummaryLbl == null) return;
        AnalysisResult result = withEffectiveTradePlan(source);
        if (result == null) {
            strategySummaryLbl.setText("Итог стратегий: нет графика");
            strategySummaryLbl.setForeground(MUTED);
            strategySummaryLbl.setToolTipText(null);
            return;
        }

        List<StrategySignal> signals = StrategyEngine.evaluateAll(result);
        int total = 0;
        int suitable = 0;
        int longCount = 0;
        int shortCount = 0;
        int scoreSum = 0;
        StrategySignal best = null;
        StrategySignal bestSuitable = null;

        for (StrategySignal signal : signals) {
            if (signal == null || "no-chart".equals(signal.id)) continue;
            total++;
            scoreSum += signal.score;
            if (best == null || signal.score > best.score) best = signal;
            if (signal.suitable) {
                suitable++;
                if (signal.longSide) longCount++;
                else shortCount++;
                if (bestSuitable == null || signal.score > bestSuitable.score) bestSuitable = signal;
            }
        }

        if (total == 0) {
            strategySummaryLbl.setText("Итог стратегий: нет данных");
            strategySummaryLbl.setForeground(MUTED);
            strategySummaryLbl.setToolTipText(null);
            return;
        }

        StrategySignal leader = bestSuitable != null ? bestSuitable : best;
        int avg = Math.round(scoreSum / (float) total);
        String bias = suitable == 0
                ? "нет входа"
                : longCount == shortCount ? "баланс" : longCount > shortCount ? "LONG перевес" : "SHORT перевес";
        String leaderName = leader == null ? "-" : shortStrategyName(leader.name);
        String text = "Итог стратегий: " + suitable + "/" + total
                + " · LONG " + longCount + " / SHORT " + shortCount
                + " · " + bias
                + " · лучшая " + leaderName + " " + (leader == null ? 0 : leader.score)
                + " · ср. " + avg;
        strategySummaryLbl.setText(text);
        strategySummaryLbl.setForeground(suitable == 0 ? YELLOW : longCount > shortCount ? BULL : shortCount > longCount ? BEAR : CYAN);
        strategySummaryLbl.setToolTipText("<html>"
                + "Подходит: " + suitable + " из " + total + "<br>"
                + "LONG: " + longCount + " · SHORT: " + shortCount + "<br>"
                + "Лучшая модель: " + (leader == null ? "-" : leader.name) + " " + (leader == null ? 0 : leader.score) + "/100<br>"
                + "Средний score: " + avg + "/100"
                + "</html>");
    }

    private String shortStrategyName(String name) {
        if (name == null || name.isBlank()) return "-";
        String value = name
                .replace(" continuation", "")
                .replace(" confirmation", "")
                .replace(" continuation", "");
        if (value.length() <= 24) return value;
        return value.substring(0, 23) + "...";
    }

    private String firstStrategyBlocker(StrategySignal signal) {
        if (signal == null || signal.blockers.isEmpty()) {
            return "текущий сетап не подтверждает модель";
        }
        return signal.blockers.get(0);
    }

    private void openAccountDialog() {
        app.ui.ExchangeAccountDialog.showDialog(this, settings -> {
            accountSnapshot = null;
            accountSnapshotError = "";
            updateAccountButton();
            refreshAccountSnapshot(true);
            statusLbl.setText("Аккаунт: " + settings.summary());
        });
    }

    private void openTradeTicket(Boolean longSide) {
        AnalysisResult current = chart.getResult();
        String selectedSymbol = selectedSymbol();
        if (current == null
                || selectedSymbol.isBlank()
                || current.symbol == null
                || !current.symbol.equalsIgnoreCase(selectedSymbol)
                || analysisRunning) {
            pendingTradeTicket = true;
            pendingTradeTicketLongSide = longSide;
            pendingTradeTicketSymbol = selectedSymbol;
            statusLbl.setText("График еще загружается. Открою trade ticket для "
                    + (selectedSymbol.isBlank() ? "выбранной пары" : selectedSymbol)
                    + " сразу после анализа.");
            if (!analysisRunning && !selectedSymbol.isBlank()) {
                runAnalysis();
            }
            return;
        }
        app.ui.TradeTicketDialog.showDialog(this, current, selectedMarket(), longSide,
                () -> {
                    if (ExchangeAccountSettings.current().mode == ExchangeAccountSettings.Mode.PAPER) {
                        refreshPaperSnapshotFromCache();
                    } else {
                        refreshAccountSnapshot(true);
                        delayedAccountRefresh(1_500);
                        delayedAccountRefresh(4_000);
                    }
                },
                openedPlan -> {
                    chart.showExecutionPlan(current.symbol, openedPlan);
                    clearForecastOverlay();
                    statusLbl.setText("Сделка принята: " + current.symbol
                            + " · entry " + price(openedPlan.entry)
                            + " · TP1 " + price(openedPlan.tp1)
                            + " · TP2 " + price(openedPlan.tp2)
                            + " · TP3 " + price(openedPlan.tp3));
                });
    }

    private String selectedSymbol() {
        Object selected = symbolBox.getSelectedItem();
        String raw = selected == null ? "" : selected.toString();
        return raw
                .toUpperCase(Locale.ROOT)
                .replace("/", "")
                .replace("-", "")
                .trim();
    }

    private void delayedAccountRefresh(int delayMs) {
        Timer timer = new Timer(Math.max(250, delayMs), e -> refreshAccountSnapshot(true));
        timer.setRepeats(false);
        timer.start();
    }

    private void closeCurrentChartPosition() {
        List<OpenPositionSnapshot> positions = currentChartPositions();
        if (positions.isEmpty()) {
            AnalysisResult current = chart.getResult();
            String symbol = current == null ? "" : current.symbol;
            statusLbl.setText(symbol == null || symbol.isBlank()
                    ? "На графике нет активной пары для закрытия."
                    : "По " + symbol + " открытых позиций нет.");
            return;
        }
        OpenPositionSnapshot selected = positions.get(0);
        if (positions.size() > 1) {
            PositionChoice[] choices = new PositionChoice[positions.size()];
            for (int i = 0; i < positions.size(); i++) choices[i] = new PositionChoice(positions.get(i));
            PositionChoice choice = (PositionChoice) JOptionPane.showInputDialog(
                    this,
                    "Выбери позицию для закрытия",
                    "Закрыть позицию",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    choices,
                    choices[0]);
            if (choice == null) return;
            selected = choice.position;
        }
        closePositionFromPanel(selected.symbol, selected.longSide(), selected.entryPrice, selected.quantity);
    }

    private List<OpenPositionSnapshot> currentChartPositions() {
        AnalysisResult current = chart.getResult();
        if (current == null || current.symbol == null || accountSnapshot == null) return List.of();
        String symbol = current.symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) return List.of();
        List<OpenPositionSnapshot> matches = new ArrayList<>();
        for (OpenPositionSnapshot position : accountSnapshot.openPositions) {
            if (symbol.equalsIgnoreCase(position.symbol) && position.quantity > 0.0) {
                matches.add(position);
            }
        }
        return matches;
    }

    private void openPositionChart(String symbol, double entryPrice) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) return;
        chart.highlightPosition(normalized, entryPrice);
        suppressControlEvents = true;
        try {
            symbolBox.setSelectedItem(normalized);
            marketBox.setSelectedItem(BinanceClient.Market.FUTURES.label);
        } finally {
            suppressControlEvents = false;
        }
        statusLbl.setText("Открываю график позиции " + normalized
                + (entryPrice > 0.0 ? " · entry " + price(entryPrice) : ""));
        runAnalysis();
    }

    private void closePositionFromPanel(String symbol, boolean longSide, double entryPrice, double quantity) {
        String normalized = normalizeSymbol(symbol);
        OpenPositionSnapshot position = findPosition(normalized, longSide, entryPrice);
        double closeQty = quantity > 0.0 ? quantity : position == null ? 0.0 : position.quantity;
        if (normalized.isBlank() || closeQty <= 0.0) {
            statusLbl.setText("Не смог определить позицию для закрытия.");
            return;
        }

        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        if (settings.exchange == ExchangeAccountSettings.Exchange.BINANCE
                && settings.mode == ExchangeAccountSettings.Mode.LIVE
                && !settings.liveTradingAllowed()) {
            statusLbl.setText("LIVE Binance blocked. Open Account and switch LIVE orders: ON.");
            return;
        }
        String side = longSide ? "LONG" : "SHORT";
        String text = "Закрыть " + side + " " + normalized + " qty " + qty(closeQty)
                + (settings.mode == ExchangeAccountSettings.Mode.LIVE ? " в LIVE?" : "?");
        int ok = JOptionPane.showConfirmDialog(this, text, "Закрытие позиции",
                JOptionPane.YES_NO_OPTION,
                settings.mode == ExchangeAccountSettings.Mode.LIVE ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        statusLbl.setText("Закрываю " + side + " " + normalized + "...");
        OpenPositionSnapshot positionSnapshot = position;
        AnalysisResult analysisContext = analysisForSymbol(normalized);
        new SwingWorker<String, Void>() {
            private String journalWarning = "";
            private boolean closeAccepted = false;

            @Override
            protected String doInBackground() throws Exception {
                ClosePositionResult result = closePosition(settings, normalized, longSide, entryPrice, closeQty);
                closeAccepted = result.success;
                if (result.success) {
                    try {
                        OpenPositionSnapshot snapshot = positionSnapshot == null
                                ? new OpenPositionSnapshot(normalized,
                                longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL,
                                closeQty, entryPrice, result.closePrice, Math.abs(closeQty * result.closePrice),
                                0.0, 0.0, System.currentTimeMillis())
                                : positionSnapshot;
                        ClosedTradeJournal.defaultJournal().record(ClosedTradeJournal.Record.from(
                                analysisContext,
                                snapshot,
                                settings,
                                result.closePrice,
                                result.closedQuantity,
                                result.venue,
                                result.reason));
                    } catch (Exception ex) {
                        journalWarning = " · журнал закрытых сделок не обновлен: "
                                + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    }
                }
                return result.message + journalWarning;
            }

            @Override
            protected void done() {
                try {
                    statusLbl.setText(get());
                    if (closeAccepted) {
                        clearClosedPositionFromChart(normalized);
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLbl.setText("Ошибка закрытия: " + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()));
                }
                clearForecastOverlay();
                refreshAccountSnapshot(true);
            }
        }.execute();
    }

    private void clearClosedPositionFromChart(String symbol) {
        AnalysisResult current = chart.getResult();
        if (current == null || current.symbol == null || !current.symbol.equalsIgnoreCase(symbol)) return;
        chart.clearExecutionOverlay();
        if (accountSnapshot != null && accountSnapshot.openPositions != null) {
            List<OpenPositionSnapshot> remaining = new ArrayList<>();
            for (OpenPositionSnapshot position : accountSnapshot.openPositions) {
                if (!symbol.equalsIgnoreCase(position.symbol)) remaining.add(position);
            }
            accountSnapshot = new AccountSnapshot(
                    accountSnapshot.venue,
                    accountSnapshot.currency,
                    accountSnapshot.walletBalance,
                    accountSnapshot.availableBalance,
                    accountSnapshot.equity,
                    accountSnapshot.unrealizedPnl,
                    accountSnapshot.openOrders,
                    remaining,
                    accountSnapshot.assets,
                    System.currentTimeMillis());
        }
        updateAccountButton();
    }

    private void cancelOrderFromPanel(String clientOrderId, String symbol) {
        if (clientOrderId == null || clientOrderId.isBlank()) return;
        int ok = JOptionPane.showConfirmDialog(this,
                "Отменить заявку " + clientOrderId + "?",
                "Отмена заявки", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        if (settings.exchange == ExchangeAccountSettings.Exchange.BINANCE
                && settings.mode == ExchangeAccountSettings.Mode.LIVE
                && !settings.liveTradingAllowed()) {
            statusLbl.setText("LIVE Binance blocked. Open Account and switch LIVE orders: ON.");
            return;
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                ExecutionJournal journal = ExecutionJournal.defaultJournal();
                if (settings.mode == ExchangeAccountSettings.Mode.PAPER) {
                    ExecutionReport cancelled = new ExecutionReport(clientOrderId, "",
                            ExecutionReport.Status.CANCELLED, 0.0, 0.0, "Paper manual cancel");
                    journal.recordExecutionReport(cancelled, "PAPER-" + settings.exchange.label);
                    return "Paper-заявка отменена: " + clientOrderId;
                }
                ExecutionGateway gateway = ExchangeGatewayFactory.create(settings);
                ExecutionReport report = gateway.cancel(clientOrderId);
                journal.recordExecutionReport(report, gateway.venue());
                return "Отмена заявки: " + report.status + " · " + report.message;
            }

            @Override
            protected void done() {
                try {
                    statusLbl.setText(get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLbl.setText("Ошибка отмены: " + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()));
                }
                refreshAccountSnapshot(true);
            }
        }.execute();
    }

    private ClosePositionResult closePosition(ExchangeAccountSettings settings,
                                              String symbol,
                                              boolean longSide,
                                              double entryPrice,
                                              double quantity) throws Exception {
        double mark = paperMarkPrices.getOrDefault(symbol, 0.0);
        if (mark <= 0.0) {
            try {
                mark = BinanceClient.fetchLastPrice(symbol, BinanceClient.Market.FUTURES);
                paperMarkPrices.put(symbol, mark);
            } catch (Exception ignored) {
                mark = entryPrice;
            }
        }

        ExecutionJournal journal = ExecutionJournal.defaultJournal();
        if (settings.mode == ExchangeAccountSettings.Mode.PAPER) {
            journal.recordManualPositionClose(symbol, longSide, entryPrice, quantity, mark, "paper manual close from positions tab");
            return new ClosePositionResult(true,
                    "Paper-позиция закрыта: " + symbol + " " + (longSide ? "LONG" : "SHORT")
                            + " qty " + qty(quantity),
                    symbol,
                    longSide,
                    entryPrice,
                    quantity,
                    mark,
                    "PAPER-" + settings.exchange.label,
                    "paper manual close from positions tab");
        }

        ExecutionGateway gateway = ExchangeGatewayFactory.create(settings);
        ExecutionOrder order = new ExecutionOrder(
                "SMC-CLOSE-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18),
                symbol,
                longSide ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY,
                ExecutionOrder.Type.MARKET,
                ExecutionOrder.TimeInForce.IOC,
                quantity,
                mark,
                0.0,
                "protect=manual-close source=positions-tab");
        OrderNormalization normalization = gateway.normalizeOrder(order);
        ExecutionOrder normalized = normalization == null || normalization.order == null ? order : normalization.order;
        journal.recordOrderRegistered(normalized, null, gateway.venue());
        ExecutionReport report;
        try {
            report = gateway.send(normalized);
        } catch (Exception ex) {
            report = new ExecutionReport(normalized.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        journal.recordExecutionReport(report, gateway.venue());
        if (report.status == ExecutionReport.Status.FILLED
                || report.status == ExecutionReport.Status.PARTIALLY_FILLED
                || report.status == ExecutionReport.Status.ACCEPTED
                || report.filledQuantity > 0.0) {
            double closed = report.filledQuantity > 0.0 ? report.filledQuantity : quantity;
            double closePrice = report.avgPrice > 0.0 ? report.avgPrice : mark;
            journal.recordManualPositionClose(symbol, longSide, entryPrice, closed,
                    closePrice,
                    "exchange close " + report.status);
            return new ClosePositionResult(true,
                    "Close order: " + report.status + " · " + report.message,
                    symbol,
                    longSide,
                    entryPrice,
                    closed,
                    closePrice,
                    gateway.venue(),
                    "exchange close " + report.status);
        }
        return new ClosePositionResult(false,
                "Close order: " + report.status + " · " + report.message,
                symbol,
                longSide,
                entryPrice,
                0.0,
                mark,
                gateway.venue(),
                "exchange close not filled");
    }

    private OpenPositionSnapshot findPosition(String symbol, boolean longSide, double entryPrice) {
        if (accountSnapshot == null || accountSnapshot.openPositions.isEmpty()) return null;
        for (OpenPositionSnapshot position : accountSnapshot.openPositions) {
            if (!symbol.equalsIgnoreCase(position.symbol) || position.longSide() != longSide) continue;
            if (entryPrice <= 0.0 || Math.abs(position.entryPrice - entryPrice) <= Math.max(0.00000001, position.entryPrice * 0.000001)) {
                return position;
            }
        }
        return null;
    }

    private AnalysisResult analysisForSymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        AnalysisResult current = chart.getResult();
        if (current != null && normalized.equalsIgnoreCase(current.symbol)) return current;
        for (PairTab tab : openPairTabs) {
            if (tab != null && tab.result != null && normalized.equalsIgnoreCase(tab.symbol)) {
                return tab.result;
            }
        }
        return null;
    }

    private void updateAccountButton() {
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        accountBtn.setText(accountButtonText(settings));
        accountBtn.setToolTipText(accountTooltip(settings));
        accountStatusLbl.setText(accountStatusText(settings));
        accountStatusLbl.setForeground(accountSnapshotError.isBlank()
                ? settings.configured() ? BULL : MUTED
                : BEAR);
        chart.setAccountSnapshot(accountSnapshot);
        side.updatePositions(settings, accountSnapshot, accountSnapshotError, accountSnapshotRunning);
        updateTradePlanButtonState(chart.getResult());
    }

    private AccountSnapshot paperAccountSnapshot(ExchangeAccountSettings settings) {
        String venue = "PAPER-" + settings.exchange.label;
        ExecutionJournal journal = ExecutionJournal.defaultJournal();
        Map<String, Double> marks = new HashMap<>(paperMarkPrices);
        AccountSnapshot seed = journal.replayedAccountSnapshot(venue, marks, 10_000.0);
        for (OpenPositionSnapshot position : seed.openPositions) {
            String symbol = position.symbol == null ? "" : position.symbol.toUpperCase(Locale.ROOT);
            if (symbol.isBlank() || marks.containsKey(symbol)) continue;
            try {
                double price = BinanceClient.fetchLastPrice(symbol, BinanceClient.Market.FUTURES);
                if (Double.isFinite(price) && price > 0.0) {
                    marks.put(symbol, price);
                    paperMarkPrices.put(symbol, price);
                }
            } catch (Exception ignored) {
                // Paper status should still render even if a public ticker is temporarily unavailable.
            }
        }
        return journal.replayedAccountSnapshot(venue, marks, 10_000.0);
    }

    private void refreshPaperSnapshotFromCache() {
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        if (settings.mode != ExchangeAccountSettings.Mode.PAPER || accountSnapshotRunning) return;
        accountSnapshot = ExecutionJournal.defaultJournal().replayedAccountSnapshot(
                "PAPER-" + settings.exchange.label,
                new HashMap<>(paperMarkPrices),
                10_000.0);
        accountSnapshotError = "";
        updateAccountButton();
    }

    private String accountButtonText(ExchangeAccountSettings settings) {
        String profile = accountProfile(settings);
        if (!settings.configured()) return I18n.english() ? "Account: not configured" : "Аккаунт: не настроен";
        if (accountSnapshotRunning) return compactProfile(profile) + " · " + compactVenue(settings) + " · "
                + (I18n.english() ? "updating" : "обновляю");
        if (!accountSnapshotError.isBlank()) return compactProfile(profile) + " · " + (I18n.english() ? "error" : "ошибка");
        if (accountSnapshot == null) return compactAccountLabel(settings, profile);
        String balance = accountSnapshot.hasBalance()
                ? walletText(accountSnapshot)
                : (settings.mode == ExchangeAccountSettings.Mode.PAPER ? "Paper local" : I18n.english() ? "balance ?" : "баланс ?");
        return compactProfile(profile) + " · " + compactVenue(settings) + " · " + balance
                + " · " + (I18n.english() ? "pos " : "поз ") + accountSnapshot.openPositions.size()
                + " · " + (I18n.english() ? "ord " : "орд ") + accountSnapshot.openOrders.size();
    }

    private String accountStatusText(ExchangeAccountSettings settings) {
        if (!settings.configured()) return (I18n.english() ? "Account: " : "Аккаунт: ") + settings.summary();
        if (!accountSnapshotError.isBlank()) return (I18n.english() ? "Account: update error · " : "Аккаунт: ошибка обновления · ") + accountSnapshotError;
        if (accountSnapshot == null) {
            return compactAccountLabel(settings, accountProfile(settings))
                    + (accountSnapshotRunning ? (I18n.english() ? " · updating..." : " · обновляю...") : "");
        }
        return (I18n.english() ? "Acct: " : "Акк: ") + compactProfile(accountProfile(settings))
                + " · " + compactVenue(settings)
                + " · " + (I18n.english() ? "wallet " : "кош ") + (accountSnapshot.hasBalance()
                ? walletText(accountSnapshot)
                : (settings.mode == ExchangeAccountSettings.Mode.PAPER ? "Paper local" : "?"))
                + (Double.isFinite(accountSnapshot.availableBalance)
                ? " · " + (I18n.english() ? "free " : "своб ") + money(accountSnapshot.availableBalance, accountSnapshot.currency) : "")
                + " · " + (I18n.english() ? "pos " : "поз ") + accountSnapshot.openPositions.size()
                + " L" + accountSnapshot.longPositions() + "/S" + accountSnapshot.shortPositions()
                + " · " + (I18n.english() ? "ord " : "орд ") + accountSnapshot.openOrders.size();
    }

    private String accountTooltip(ExchangeAccountSettings settings) {
        StringBuilder sb = new StringBuilder("<html><b>");
        sb.append(html(accountProfile(settings))).append("</b><br>");
        sb.append(html(settings.summary()));
        if (!accountSnapshotError.isBlank()) {
            sb.append("<br><span style='color:#ff5252'>Ошибка: ")
                    .append(html(accountSnapshotError)).append("</span>");
            return sb.append("</html>").toString();
        }
        if (accountSnapshot == null) {
            if (accountSnapshotRunning) sb.append("<br>Обновление баланса и заявок...");
            return sb.append("</html>").toString();
        }
        sb.append("<br>Баланс кошелька: ")
                .append(accountSnapshot.hasBalance()
                        ? html(walletText(accountSnapshot))
                        : "нет реального баланса")
                .append("<br>Свободно: ")
                .append(Double.isFinite(accountSnapshot.availableBalance)
                        ? html(money(accountSnapshot.availableBalance, accountSnapshot.currency))
                        : "?")
                .append("<br>Equity: ")
                .append(Double.isFinite(accountSnapshot.equity)
                        ? html(money(accountSnapshot.equity, accountSnapshot.currency))
                        : "?")
                .append(" · uPnL: ")
                .append(Double.isFinite(accountSnapshot.unrealizedPnl)
                        ? html(money(accountSnapshot.unrealizedPnl, accountSnapshot.currency))
                        : "?");
        sb.append("<br>Позиции: ").append(accountSnapshot.openPositions.size())
                .append(" · LONG ").append(accountSnapshot.longPositions())
                .append(" / SHORT ").append(accountSnapshot.shortPositions());
        appendPositionsTooltip(sb, accountSnapshot.openPositions);
        sb.append("<br>Заявки: ").append(accountSnapshot.openOrders.size())
                .append(" · BUY ").append(accountSnapshot.buyOrders())
                .append(" / SELL ").append(accountSnapshot.sellOrders())
                .append(" · LIMIT ").append(accountSnapshot.limitOrders())
                .append(" · STOP ").append(accountSnapshot.stopOrders());
        appendOrdersTooltip(sb, accountSnapshot.openOrders);
        return sb.append("</html>").toString();
    }

    private void appendPositionsTooltip(StringBuilder sb, List<OpenPositionSnapshot> positions) {
        int limit = Math.min(4, positions.size());
        for (int i = 0; i < limit; i++) {
            OpenPositionSnapshot p = positions.get(i);
            sb.append("<br>&nbsp;&nbsp;")
                    .append(html(p.symbol)).append(" ")
                    .append(p.sideLabel())
                    .append(" qty ").append(qty(p.quantity))
                    .append(" @ ").append(price(p.entryPrice))
                    .append(" pnl ").append(price(p.unrealizedPnl));
        }
        if (positions.size() > limit) sb.append("<br>&nbsp;&nbsp;... еще ").append(positions.size() - limit);
    }

    private void appendOrdersTooltip(StringBuilder sb, List<OpenOrderSnapshot> orders) {
        int limit = Math.min(5, orders.size());
        for (int i = 0; i < limit; i++) {
            OpenOrderSnapshot o = orders.get(i);
            sb.append("<br>&nbsp;&nbsp;")
                    .append(html(o.symbol)).append(" ")
                    .append(o.side.name()).append(" ")
                    .append(o.type.name())
                    .append(" rem ").append(qty(o.remainingQuantity));
            if (o.price > 0.0) sb.append(" @ ").append(price(o.price));
            if (o.stopPrice > 0.0) sb.append(" stop ").append(price(o.stopPrice));
        }
        if (orders.size() > limit) sb.append("<br>&nbsp;&nbsp;... еще ").append(orders.size() - limit);
    }

    private String accountProfile(ExchangeAccountSettings settings) {
        if (settings.profileName != null && !settings.profileName.isBlank()) return settings.profileName;
        return settings.mode == ExchangeAccountSettings.Mode.PAPER ? "Paper local" : settings.exchange.label;
    }

    private String compactAccountLabel(ExchangeAccountSettings settings, String profile) {
        return compactProfile(profile) + " · " + compactVenue(settings);
    }

    private String compactProfile(String profile) {
        String text = profile == null || profile.isBlank() ? "Аккаунт" : profile.trim();
        if (text.length() <= 14) return text;
        return text.substring(0, 6) + "..." + text.substring(text.length() - 4);
    }

    private String compactVenue(ExchangeAccountSettings settings) {
        if (settings == null) return "аккаунт";
        String exchange = switch (settings.exchange) {
            case BINANCE -> "Binance";
            case BYBIT -> "Bybit";
            case BINGX -> "BingX";
        };
        String mode = switch (settings.mode) {
            case PAPER -> "Paper";
            case TESTNET -> "Testnet";
            case LIVE -> "Live";
        };
        return exchange + " " + mode;
    }

    private String walletText(AccountSnapshot snapshot) {
        if (snapshot == null) return "?";
        if (Double.isFinite(snapshot.walletBalance)) return money(snapshot.walletBalance, snapshot.currency);
        if (Double.isFinite(snapshot.equity)) return money(snapshot.equity, snapshot.currency);
        if (Double.isFinite(snapshot.availableBalance)) return money(snapshot.availableBalance, snapshot.currency);
        return "?";
    }

    private void recordRadarDecision(RadarWindow.AlertItem signal, String action) {
        if (signal == null) return;
        try {
            Path dir = ProjectPaths.root().resolve("decision-journal");
            Files.createDirectories(dir);
            String line = "{"
                    + "\"time\":\"" + json(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)) + "\","
                    + "\"source\":\"radar\","
                    + "\"action\":\"" + json(action) + "\","
                    + "\"symbol\":\"" + json(signal.symbol) + "\","
                    + "\"market\":\"" + json(signal.market == null ? "" : signal.market.label) + "\","
                    + "\"htf\":\"" + json(signal.htf) + "\","
                    + "\"ltf\":\"" + json(signal.ltf) + "\","
                    + "\"direction\":\"" + json(signal.direction) + "\","
                    + "\"score\":" + signal.score + ","
                    + "\"entry\":" + num(signal.entry) + ","
                    + "\"price\":" + num(signal.currentPrice) + ","
                    + "\"filter\":\"" + json(signal.filterSummary) + "\""
                    + "}\n";
            Files.writeString(dir.resolve("signals.jsonl"), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private String json(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String num(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.8f", value) : "0";
    }

    private boolean sameAccount(ExchangeAccountSettings a, ExchangeAccountSettings b) {
        return a.exchange == b.exchange
                && a.mode == b.mode
                && safe(a.profileName).equals(safe(b.profileName))
                && safe(a.apiKey).equals(safe(b.apiKey));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).replace("/", "").replace("-", "").trim();
    }

    private static String money(double value, String currency) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.2f %s", value, currency == null || currency.isBlank() ? "USDT" : currency);
    }

    private static String qty(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.6f", Math.abs(value)).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String price(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.4f", value);
    }

    private static String html(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private boolean hasEntryPoint(AnalysisResult result) {
        return hasEntryPoint(effectiveTradePlan(result));
    }

    private boolean hasEntryPoint(TradePlan plan) {
        return plan != null
                && Double.isFinite(plan.entry)
                && plan.entry > 0.0;
    }

    private boolean canShowForecast(AnalysisResult result) {
        return hasEntryPoint(effectiveTradePlan(result));
    }

    private TradePlan effectiveTradePlan(AnalysisResult result) {
        if (result == null) return null;
        if (hasRawEntryPoint(result.plan)) return result.plan;
        if (forecastState.stickyMatches(result.symbol)
                && hasRawEntryPoint(forecastState.plan())) {
            return forecastState.plan();
        }
        if (activePairTab != null
                && activePairTab.forecastPlan != null
                && result.symbol != null
                && activePairTab.symbol != null
                && activePairTab.symbol.equalsIgnoreCase(result.symbol)
                && hasRawEntryPoint(activePairTab.forecastPlan)) {
            return activePairTab.forecastPlan;
        }
        return null;
    }

    private boolean hasRawEntryPoint(TradePlan plan) {
        return plan != null
                && Double.isFinite(plan.entry)
                && plan.entry > 0.0;
    }

    private AnalysisResult withEffectiveTradePlan(AnalysisResult result) {
        TradePlan plan = effectiveTradePlan(result);
        if (result == null || plan == null || plan == result.plan) return result;
        AnalysisResult copy = new AnalysisResult(
                result.symbol, result.ltfInterval, result.candles,
                result.currentPrice, result.htfBullish, result.zones,
                plan, result.equilibrium, result.structHigh, result.structLow,
                result.liquidityMap, result.htfStructure, result.ltfStructure,
                result.htfDisplacementEvents, result.displacementEvents,
                result.structureEvents, result.sweeps, result.inducements,
                result.sessionContext, result.marketRegime, result.deviationContext,
                result.imbalanceContext, result.eventRiskContext, result.orderFlowContext,
                result.derivativesContext, result.orderBookContext, result.adaptiveWeights,
                Math.max(result.smcScore, plan.score), result.scoreFactors,
                result.warnings, result.narrative);
        copy.currentPrice = result.currentPrice;
        return copy;
    }

    private void maybeOpenPendingTradeTicket(AnalysisResult result) {
        if (!pendingTradeTicket || result == null || result.symbol == null) return;
        String expected = pendingTradeTicketSymbol == null ? "" : pendingTradeTicketSymbol.trim();
        if (!expected.isBlank() && !expected.equalsIgnoreCase(result.symbol)) return;
        Boolean side = pendingTradeTicketLongSide;
        pendingTradeTicket = false;
        pendingTradeTicketLongSide = null;
        pendingTradeTicketSymbol = "";
        SwingUtilities.invokeLater(() -> openTradeTicket(side));
    }

    private void updateTradePlanButtonState(AnalysisResult result) {
        boolean show = hasEntryPoint(result);
        boolean hasChart = result != null;
        int chartPositions = currentChartPositions().size();
        chartLongBtn.setEnabled(hasChart);
        chartShortBtn.setEnabled(hasChart);
        chartLongBtn.setToolTipText("Открыть LONG ticket");
        chartShortBtn.setToolTipText("Открыть SHORT ticket");
        chartCloseBtn.setEnabled(chartPositions > 0);
        chartCloseBtn.setText(chartPositions > 1 ? "Закрыть (" + chartPositions + ")" : "Закрыть позицию");
        chartCloseBtn.setToolTipText(chartPositions > 0
                ? "Закрыть открытую позицию по паре на текущем графике"
                : "Активируется, когда по паре на графике есть открытая позиция");
        tradePlanBtn.setVisible(show);
        tradePlanBtn.setEnabled(show);
        tradePlanBtn.setToolTipText(show
                ? "Открыть поэтапный план торговли по точке входа на графике"
                : "Появится, когда на графике есть точка входа");
        if (tradePlanBtn.getParent() != null) {
            tradePlanBtn.getParent().revalidate();
            tradePlanBtn.getParent().repaint();
        }
    }

    private void runBacktest() {
        String symbol = selectedSymbol();
        if (symbol.isEmpty()) return;

        String htfInterval = (String) htfBox.getSelectedItem();
        String ltfInterval = (String) ltfBox.getSelectedItem();
        String trendMethod = (String) trendMethodBox.getSelectedItem();
        BinanceClient.Market market = selectedMarket();
        BacktestAssumptions assumptions = selectedBacktestAssumptions();
        int historyDepth = selectedBacktestDepth();

        backtestBtn.setEnabled(false);
        beginProgress(assumptions.useTickData
                ? "Тиковый бэктест: " + historyDepth + " свечей, точнее, но может идти долго..."
                : "Быстрый бэктест: " + historyDepth + " свечей, OHLC-модель...");

        new SwingWorker<app.logic.Backtester.Report, Void>() {
            @Override
            protected app.logic.Backtester.Report doInBackground() throws Exception {
                updateProgress(5, "Бэктест: загрузка LTF истории " + historyDepth + " свечей...");
                List<Candle> ltfHistory = BinanceClient.fetchCandlesHistory(symbol, ltfInterval, historyDepth, market);
                int htfHistoryDepth = BinanceClient.matchingHtfHistoryLimit(htfInterval, ltfHistory);
                updateProgress(18, "Бэктест: загрузка HTF контекста " + htfHistoryDepth + " свечей...");
                List<Candle> htfHistory = BinanceClient.fetchCandlesHistory(symbol, htfInterval, htfHistoryDepth, market);
                updateProgress(30, "Бэктест: запуск симуляции...");

                return app.logic.Backtester.run(symbol, htfHistory, ltfHistory, trendMethod, market, assumptions,
                        (percent, message) -> updateProgress(percent, message));
            }

            @Override
            protected void done() {
                backtestBtn.setEnabled(true);

                try {
                    app.logic.Backtester.Report report = get();
                    finishProgress("Бэктест завершен");
                    // ВЫЗЫВАЕМ НАШЕ КАСТОМНОЕ ОКНО
                    app.ui.BacktestReportDialog.showReport(
                            SmcChartApp.this,
                            symbol,
                            htfInterval,
                            ltfInterval,
                            report
                    );
                } catch (Exception ex) {
                    progressPanel.hideAnimated();
                    statusLbl.setText("Ошибка бэктеста");
                    JOptionPane.showMessageDialog(SmcChartApp.this,
                            "Ошибка бэктеста: " + ex.getMessage(),
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    private void tickPrice() {
        if (priceRefreshRunning) return;
        AnalysisResult r = chart.getResult();
        if (r == null) return;
        if (System.currentTimeMillis() - lastWebSocketPriceAt < 3500L) {
            return;
        }
        priceRefreshRunning = true;
        BinanceClient.Market market = selectedMarket();
        String symbol = r.symbol;
        new SwingWorker<Double, Void>() {
            @Override protected Double doInBackground() throws Exception {
                return BinanceClient.fetchLastPrice(symbol, market);
            }
            @Override protected void done() {
                try {
                    double newPrice = get();
                    if (r != chart.getResult() || !symbol.equals(r.symbol)) return;
                    queueLivePrice(symbol, market, newPrice, "● live");
                } catch (Exception ex) { liveTickLbl.setText("● offline"); liveTickLbl.setForeground(MUTED); }
                finally { priceRefreshRunning = false; }
            }
        }.execute();
    }

    private void runAnalysis() {
        String symbol = selectedSymbol();
        if (symbol.isEmpty()) return;
        rememberActiveTabView();

        String htfInterval = (String) htfBox.getSelectedItem();
        String ltfInterval = (String) ltfBox.getSelectedItem();
        String trendMethod = (String) trendMethodBox.getSelectedItem();
        BinanceClient.Market market = selectedMarket();
        int generation = ++analysisGeneration;
        forecastState.clearIfDifferentSymbol(symbol);
        if (!forecastState.sticky()) {
            chart.clearRadarForecastPlan();
            forecastBtn.setSelected(false);
            chart.setShowForecast(false);
        }

        // Don't tear down the live WebSocket here anymore -- this runs on every re-analysis,
        // including the automatic one fired on every closed candle (roughly once a minute for a
        // 1m chart), and used to unconditionally kill+rebuild the whole live connection each
        // time even when the symbol/interval/market weren't changing at all. startLiveMarketData()
        // (called below once the new AnalysisResult is ready) now decides on its own whether the
        // existing socket can just keep streaming; it only stops/rebuilds when something actually
        // changed (e.g. the user picked a different symbol). See startLiveMarketData() for why
        // the constant rebuild churn mattered on Java 17 (HttpClient has no explicit close()).
        analysisRunning = true;
        analyzeBtn.setEnabled(false);
        forecastBtn.setEnabled(canShowForecast(chart.getResult()));
        updateTradePlanButtonState(chart.getResult());
        strategySummaryLbl.setText("Итог стратегий: пересчет...");
        strategySummaryLbl.setForeground(MUTED);
        beginProgress("Загрузка " + symbol + " ...");

        new SwingWorker<AnalysisResult, Void>() {
            @Override protected AnalysisResult doInBackground() throws Exception {
                updateProgress(8, "Анализ: загрузка HTF свечей...");
                List<Candle> htf = BinanceClient.fetchCandles(symbol, htfInterval, HTF_LIMIT, market);
                updateProgress(32, "Анализ: загрузка LTF свечей...");
                List<Candle> ltf = BinanceClient.fetchCandles(symbol, ltfInterval, LTF_LIMIT, market);
                updateProgress(58, "Анализ: liquidity / structure / zones...");
                AnalysisResult result = SMCAnalyzer.analyze(symbol, ltfInterval, htf, ltf, trendMethod, market);
                updateProgress(86, "Анализ: обновление live price...");
                result.currentPrice = BinanceClient.fetchLastPrice(symbol, market);
                paperMarkPrices.put(symbol.toUpperCase(Locale.ROOT), result.currentPrice);
                updateProgress(96, "Анализ: подготовка графика...");
                return result;
            }
            @Override protected void done() {
                if (generation != analysisGeneration) return;
                analysisRunning = false;
                analyzeBtn.setEnabled(true);
                try {
                    AnalysisResult r = get();
                    if (generation != analysisGeneration) return;
                    chart.setData(r); side.update(r);
                    applyCurrentChartInsight(r, htfInterval, ltfInterval, market);
                    refreshPaperSnapshotFromCache();
                    startLiveMarketData(r, market);
                    addOrSelectPairTab(r, htfInterval, trendMethod, market);
                    forecastBtn.setEnabled(canShowForecast(r));
                    boolean radarForecastRequested = forecastState.consumePendingFor(r.symbol);
                    if (radarForecastRequested) {
                        forecastState.stickIfEmpty(r.symbol, r.plan);
                    }
                    if (forecastState.stickyMatches(r.symbol) && r.plan != null && !forecastState.hasPlan()) {
                        forecastState.stick(r.symbol, r.plan);
                    }
                    if (forecastState.stickyMatches(r.symbol) && forecastState.hasPlan()) {
                        chart.showRadarForecastPlan(r.symbol, forecastState.plan());
                    } else {
                        chart.clearRadarForecastPlan();
                    }
                    boolean keepForecast = forecastState.stickyMatches(r.symbol);
                    forecastBtn.setSelected(keepForecast);
                    chart.setShowForecast(keepForecast);
                    if (!canShowForecast(r)) {
                        forecastBtn.setSelected(false);
                        chart.setShowForecast(false);
                    }
                    updateTradePlanButtonState(r);
                    StrategySignal strategy = refreshSelectedStrategy(r);
                    updateStrategySummary(r);
                    int sweeps = r.sweeps == null ? 0 : r.sweeps.size();
                    String strategySuffix = strategy == null ? "" : " · стратегия " + strategy.statusText();
                    statusLbl.setText("Готово · " + r.symbol + " · " + r.ltfInterval
                            + " · свечей: " + r.candles.size()
                            + " · зон: " + r.zones.size()
                            + " · sweep: " + sweeps
                            + " · SMC " + r.smcScore + "/100"
                            + strategySuffix);
                    finishProgress(statusLbl.getText());
                    maybeOpenPendingTradeTicket(r);
                } catch (Exception ex) {
                    if (generation != analysisGeneration) return;
                    progressPanel.hideAnimated();
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    LOG.warn("Analysis failed for {} {}: {}", selectedSymbol(), ltfBox.getSelectedItem(), c.toString());
                    statusLbl.setText("Ошибка: " + c.getMessage());
                    JOptionPane.showMessageDialog(SmcChartApp.this, "Не удалось получить данные:\n" + c.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static class ClosePositionResult {
        final boolean success;
        final String message;
        final String symbol;
        final boolean longSide;
        final double entryPrice;
        final double closedQuantity;
        final double closePrice;
        final String venue;
        final String reason;

        ClosePositionResult(boolean success,
                            String message,
                            String symbol,
                            boolean longSide,
                            double entryPrice,
                            double closedQuantity,
                            double closePrice,
                            String venue,
                            String reason) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.symbol = symbol == null ? "" : symbol;
            this.longSide = longSide;
            this.entryPrice = entryPrice;
            this.closedQuantity = closedQuantity;
            this.closePrice = closePrice;
            this.venue = venue == null ? "" : venue;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static class PositionChoice {
        final OpenPositionSnapshot position;

        PositionChoice(OpenPositionSnapshot position) {
            this.position = position;
        }

        @Override
        public String toString() {
            if (position == null) return "";
            return position.symbol + " · " + position.sideLabel()
                    + " · qty " + qty(position.quantity)
                    + " · entry " + price(position.entryPrice)
                    + " · pnl " + price(position.unrealizedPnl);
        }
    }

    private static class PairTab {
        final String symbol;
        final String htf;
        final String ltf;
        final String trendMethod;
        final BinanceClient.Market market;
        JPanel component;
        JLabel label;
        AnalysisResult result;
        TradePlan forecastPlan;
        ChartPanel.ViewState viewState;
        boolean correlationBlocked;
        String blockReason = "";

        PairTab(String symbol, String htf, String ltf,
                String trendMethod, BinanceClient.Market market) {
            this.symbol = symbol;
            this.htf = htf;
            this.ltf = ltf;
            this.trendMethod = trendMethod;
            this.market = market;
        }

        String key() {
            return market.label + "|" + symbol + "|" + htf + "|" + ltf + "|" + trendMethod;
        }
    }
}
