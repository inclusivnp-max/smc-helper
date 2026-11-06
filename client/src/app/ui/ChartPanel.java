package app.ui;

import app.model.AnalysisResult;
import app.model.Candle;
import app.model.ChartInsight;
import app.model.TradePlan;
import app.model.Zone;
import app.backtest.BacktestEvidenceStore;
import app.execution.fix.AccountSnapshot;
import app.execution.fix.OpenPositionSnapshot;
import app.logic.BinanceClient;
import app.displacement.DisplacementEvent;
import app.liquidity.InducementEvent;
import app.liquidity.LiquidityLevel;
import app.liquidity.LiquiditySweep;
import app.orderflow.FootprintCandle;
import app.orderflow.FootprintLevel;
import app.strategy.StrategySignal;
import app.ui.animation.FadePulse;
import app.ui.animation.LiveValueSmoother;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static app.Colors.*;

public class ChartPanel extends JPanel {
    private static final DateTimeFormatter TIME_AXIS_SAME_DAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_AXIS_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Color FVG_BULL = new Color(45, 170, 255);
    private static final Color FVG_BEAR = new Color(255, 152, 67);
    private static final Color OTE_COLOR = new Color(126, 87, 194);
    private static final Color ENTRY_COLOR = new Color(255, 64, 129);
    private static final Color ZONE_LABEL_BG = new Color(8, 12, 18);
    private static final int OVERLAY_EYE_SIZE = 20;
    private static final long STRATEGY_STORY_MS = 11000L;
    private static final int RIGHT_LABEL_GAP = 8;
    private static final int LIQUIDITY_LABEL_STEP = 20;

    private enum OverlayCard {
        FORECAST_SUMMARY("Прогноз"),
        PLAN_HEALTH("План"),
        CHART_INSIGHT("Анализ"),
        STRATEGY("Стратегия");

        final String label;

        OverlayCard(String label) {
            this.label = label;
        }
    }

    private AnalysisResult result;
    private ChartInsight chartInsight;
    private Candle liveCandleOverride;
    private int liveCandleIndex = -1;
    private int firstVisible = 0;
    private int visibleCount = 120;
    private final int paddingTop = 24;
    private final int paddingBottom = 58;
    private final int paddingLeft = 10;
    private final int paddingRight = 250;

    private Point mouse = null;
    private Point dragStart = null;
    private int dragStartFirst = 0;
    private int dragStartY = 0;
    private double dragStartPriceCenter = Double.NaN;

    private boolean showForecast = false;
    private boolean priceBarRatioLocked = false;
    private double manualPriceCenter = Double.NaN;
    private double lockedPricePerBar = Double.NaN;
    private final Font axisFont = UIUtils.monoFont(11.2f, Font.PLAIN);
    private final Font priceFont = UIUtils.monoFont(11.4f, Font.BOLD);
    private final Font labelFont = UIUtils.labelFont(10.8f);
    private final Font titleFont = UIUtils.titleFont(13.5f);
    private final Font textFont = UIUtils.bodyFont(12.2f);
    private final Timer candleCountdownTimer = new Timer(1000, e -> {
        if (result != null && isShowing()) repaint();
    });
    private final Timer liveAnimationTimer = new Timer(33, e -> onLiveAnimationFrame());
    private final LiveValueSmoother priceLineSmoother = new LiveValueSmoother(72.0);
    private Candle animatedLiveCandle;
    private Candle liveCandleAnimationTarget;
    private int animatedLiveCandleIndex = -1;
    private long liveCandleFadeStartedAt = Long.MIN_VALUE;
    private boolean newLiveCandleFadeActive = false;
    private final Map<String, String> levelVisualStates = new HashMap<>();
    private final Map<String, FadePulse> levelPulses = new HashMap<>();
    // Tracks which TradePlan levelVisualStates was last built from, see refreshLevelPulses().
    private TradePlan levelVisualStatesPlan;
    private int fvgOpacity = 34;
    private int obOpacity = 24;
    private int oteOpacity = 18;
    private int liquidityOpacity = 72;
    private int markerOpacity = 88;
    private int zoneLabelOpacity = 82;
    private Color bullCandleColor = BULL;
    private Color bearCandleColor = BEAR;
    private Color neutralCandleColor = FG;
    private Color wickCandleColor = MUTED;
    private boolean candleWicksFollowBody = true;
    private Color fvgBullColor = FVG_BULL;
    private Color fvgBearColor = FVG_BEAR;
    private Color obBullColor = BULL;
    private Color obBearColor = BEAR;
    private Color oteZoneColor = OTE_COLOR;
    private Color entryLevelColor = ENTRY_COLOR;
    private Color stopLevelColor = BEAR;
    private Color targetLevelColor = BULL;
    private Color currentPriceColor = YELLOW;
    private Color bslColor = BEAR;
    private Color sslColor = BULL;
    private Color displacementBullColor = BULL;
    private Color displacementBearColor = BEAR;
    private Color sweepMarkerColor = YELLOW;
    private Color idmMarkerColor = PURPLE;
    private List<OpenPositionSnapshot> accountPositions = List.of();
    private boolean accountSnapshotKnown;
    private String highlightedPositionSymbol = "";
    private double highlightedPositionEntry = 0.0;
    private TradePlan executionOverlayPlan;
    private String executionOverlaySymbol = "";
    private TradePlan radarForecastOverlayPlan;
    private String radarForecastOverlaySymbol = "";
    private long radarForecastShownAt = Long.MIN_VALUE;
    private StrategySignal strategySignal;
    private String strategyAnimationKey = "";
    private long strategyAnimationStartedAt = Long.MIN_VALUE;
    private boolean strategyCinematicMode = false;
    private float strategyCinematicAlpha = 0.0f;
    private final EnumSet<OverlayCard> hiddenOverlayCards = EnumSet.noneOf(OverlayCard.class);
    private final Map<OverlayCard, Rectangle> overlayToggleBounds = new EnumMap<>(OverlayCard.class);
    private final Map<OverlayCard, Rectangle> overlayRestoreBounds = new EnumMap<>(OverlayCard.class);
    /** Stored drag positions for user-repositioned panels (survives the session). */
    private final Map<OverlayCard, Point> panelPositions = new EnumMap<>(OverlayCard.class);
    /** Full bounds of each draggable panel, refreshed every paint pass. */
    private final Map<OverlayCard, Rectangle> panelDragBounds = new EnumMap<>(OverlayCard.class);
    private OverlayCard draggingPanel = null;
    private Point panelDragOffset = null;
    private final List<Candle> visibleCandleBuffer = new ArrayList<>(180);
    private final List<Zone> zoneBuffer = new ArrayList<>(96);
    private final List<LiquidityLabel> liquidityLabelBuffer = new ArrayList<>(96);
    private final List<OpenPositionSnapshot> positionBuffer = new ArrayList<>(8);

    // Коллбэк для связи с боковой панелью
    private BiConsumer<Zone, Double> onZoneDoubleClicked;

    public ChartPanel() {
        setBackground(BG);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, GRID));
        candleCountdownTimer.setCoalesce(true);
        liveAnimationTimer.setCoalesce(true);

        addMouseWheelListener(this::onWheel);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // Panel drag intercept: if the click lands inside a draggable panel
                // (but NOT on its eye-button), start a panel drag and skip chart drag.
                for (Map.Entry<OverlayCard, Rectangle> entry : panelDragBounds.entrySet()) {
                    if (entry.getValue().contains(e.getPoint())) {
                        Rectangle eye = overlayToggleBounds.get(entry.getKey());
                        if (eye != null && eye.contains(e.getPoint())) break; // let eye click through
                        draggingPanel  = entry.getKey();
                        Rectangle r    = entry.getValue();
                        panelDragOffset = new Point(e.getX() - r.x, e.getY() - r.y);
                        return; // do NOT set dragStart — prevents chart scroll
                    }
                }
                // Block chart pan while cinematic animation is running so the camera
                // stays locked on the strategy area and anchorX never drifts.
                if (strategyAnimationActive(System.currentTimeMillis())) return;
                dragStart = e.getPoint();
                dragStartFirst = firstVisible;
                dragStartY = e.getY();
                if (priceBarRatioLocked) {
                    ensureLockedScaleInitialized();
                    dragStartPriceCenter = manualPriceCenter;
                } else {
                    dragStartPriceCenter = Double.NaN;
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                draggingPanel   = null;
                panelDragOffset = null;
                dragStart = null;
                dragStartPriceCenter = Double.NaN;
            }
            @Override public void mouseExited(MouseEvent e) {
                mouse = null;
                setCursor(Cursor.getDefaultCursor());
                repaint();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (handleOverlayToggleClick(e.getPoint())) return;
                if (e.getClickCount() == 2 && result != null && onZoneDoubleClicked != null) {
                    Zone z = findZoneAt(e.getPoint());
                    if (z != null) onZoneDoubleClicked.accept(z, result.currentPrice);
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                mouse = e.getPoint();
                updateOverlayCursor(e.getPoint());
                repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                mouse = e.getPoint();
                if (draggingPanel != null && panelDragOffset != null) {
                    int nx = e.getX() - panelDragOffset.x;
                    int ny = e.getY() - panelDragOffset.y;
                    // Clamp to component bounds so panels can't fly off-screen
                    Rectangle cur = panelDragBounds.get(draggingPanel);
                    int pw = cur != null ? cur.width  : FORECAST_PANEL_BOX_W;
                    int ph = cur != null ? cur.height : FORECAST_PANEL_BOX_H;
                    nx = clamp(nx, 0, Math.max(0, getWidth()  - pw));
                    ny = clamp(ny, 0, Math.max(0, getHeight() - ph));
                    panelPositions.put(draggingPanel, new Point(nx, ny));
                    repaint();
                    return;
                }
                if (dragStart != null && result != null) {
                    int dx = e.getX() - dragStart.x;
                    double cw = candleWidth();
                    int shift = (int) (dx / cw);
                    int n = candleCount();
                    firstVisible = clamp(
                            dragStartFirst - shift,
                            0, maxFirstVisible(n));
                    if (priceBarRatioLocked) {
                        ensureLockedScaleInitialized();
                        double range = lockedPriceRange();
                        double pricePerPixel = range / Math.max(1, chartHeight());
                        double baseCenter = Double.isFinite(dragStartPriceCenter)
                                ? dragStartPriceCenter
                                : manualPriceCenter;
                        manualPriceCenter = baseCenter + (e.getY() - dragStartY) * pricePerPixel;
                    }
                }
                repaint();
            }
        });
    }

    public static class ViewState {
        public final int firstVisible;
        public final int visibleCount;
        public final boolean priceBarRatioLocked;
        public final double manualPriceCenter;
        public final double lockedPricePerBar;

        private ViewState(int firstVisible,
                          int visibleCount,
                          boolean priceBarRatioLocked,
                          double manualPriceCenter,
                          double lockedPricePerBar) {
            this.firstVisible = firstVisible;
            this.visibleCount = visibleCount;
            this.priceBarRatioLocked = priceBarRatioLocked;
            this.manualPriceCenter = manualPriceCenter;
            this.lockedPricePerBar = lockedPricePerBar;
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        candleCountdownTimer.start();
        liveAnimationTimer.start();
    }

    @Override
    public void removeNotify() {
        liveAnimationTimer.stop();
        candleCountdownTimer.stop();
        super.removeNotify();
    }

    public void setOnZoneDoubleClicked(BiConsumer<Zone, Double> listener) {
        this.onZoneDoubleClicked = listener;
    }

    public AnalysisResult getResult() { return result; }

    public ViewState captureViewState() {
        return new ViewState(firstVisible, visibleCount, priceBarRatioLocked, manualPriceCenter, lockedPricePerBar);
    }

    public void restoreViewState(ViewState state) {
        if (state == null) return;
        int n = candleCount();
        if (n > 0) {
            int minVisible = Math.min(30, n);
            visibleCount = clamp(state.visibleCount, minVisible, n);
            firstVisible = clamp(state.firstVisible, 0, maxFirstVisible(n));
        } else {
            visibleCount = Math.max(1, state.visibleCount);
            firstVisible = Math.max(0, state.firstVisible);
        }
        priceBarRatioLocked = state.priceBarRatioLocked;
        if (priceBarRatioLocked) {
            manualPriceCenter = state.manualPriceCenter;
            lockedPricePerBar = state.lockedPricePerBar;
            ensureLockedScaleInitialized();
        } else {
            resetLockedScale();
        }
        repaint();
    }

    public void setChartInsight(ChartInsight insight) {
        chartInsight = insight;
        repaint();
    }

    public void setStrategySignal(StrategySignal signal) {
        strategySignal = signal;
        if (strategySignal == null || !strategyMatchesCurrentChart()) {
            resetStrategyAnimation();
        } else {
            armStrategyAnimation(strategySignal);
        }
        repaint();
    }

    public void setData(AnalysisResult r) {
        String oldSymbol = result == null ? "" : result.symbol;
        String oldInterval = result == null ? "" : result.ltfInterval;
        boolean sameChart = r != null
                && oldSymbol != null
                && oldSymbol.equalsIgnoreCase(r.symbol)
                && oldInterval != null
                && oldInterval.equalsIgnoreCase(r.ltfInterval);
        this.result = r;
        if (r == null || chartInsight == null || !chartInsight.matches(r.symbol, r.ltfInterval)) {
            chartInsight = null;
        }
        if (r == null || strategySignal == null || !strategySignal.matches(r)) {
            strategySignal = null;
            resetStrategyAnimation();
        }
        this.liveCandleOverride = null;
        this.liveCandleIndex = -1;
        if (!sameChart) {
            resetLiveAnimationState(r == null ? Double.NaN : r.currentPrice);
        } else {
            priceLineSmoother.target(r == null ? Double.NaN : r.currentPrice);
        }
        if (!sameChart || r == null || r.symbol == null || !r.symbol.equalsIgnoreCase(executionOverlaySymbol)) {
            clearExecutionOverlay();
        }
        if (r == null || r.candles == null || r.candles.isEmpty()) {
            visibleCount = 120;
            firstVisible = 0;
            repaint();
            return;
        }
        syncLiveCandle(r, r == null ? Double.NaN : r.currentPrice, false);
        snapLiveCandleAnimation();
        refreshLevelPulses();
        int n = candleCount();
        if (priceBarRatioLocked && sameChart) {
            int minVisible = Math.min(30, n);
            visibleCount = clamp(visibleCount, minVisible, n);
            firstVisible = clamp(firstVisible, 0, maxFirstVisible(n));
        } else {
            visibleCount = Math.min(150, n);
            firstVisible = maxFirstVisible(n);
            if (priceBarRatioLocked) resetLockedScale();
        }
        repaint();
    }

    public void applyLivePrice(double price) {
        if (result == null || !Double.isFinite(price) || price <= 0.0) return;
        Candle previousVisual = currentLiveVisualCandle();
        int previousIndex = liveCandleIndex;
        result.currentPrice = price;
        priceLineSmoother.target(price);
        syncLiveCandle(result, price, true);
        prepareLiveCandleAnimation(previousIndex, previousVisual);
        refreshLevelPulses();
        repaint();
    }

    public void setShowForecast(boolean b) {
        showForecast = b;
        repaint();
    }

    public void setPriceBarRatioLocked(boolean locked) {
        if (priceBarRatioLocked == locked) return;
        priceBarRatioLocked = locked;
        if (locked) {
            resetLockedScale();
            ensureLockedScaleInitialized();
        } else {
            resetLockedScale();
        }
        repaint();
    }

    public void setFvgOpacity(int value) {
        fvgOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setObOpacity(int value) {
        obOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setOteOpacity(int value) {
        oteOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setLiquidityOpacity(int value) {
        liquidityOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setMarkerOpacity(int value) {
        markerOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setZoneLabelOpacity(int value) {
        zoneLabelOpacity = clamp(value, 0, 100);
        repaint();
    }

    public void setCandleColors(Color bull, Color bear, Color neutral, Color wick, boolean wicksFollowBody) {
        bullCandleColor = bull == null ? BULL : bull;
        bearCandleColor = bear == null ? BEAR : bear;
        neutralCandleColor = neutral == null ? FG : neutral;
        wickCandleColor = wick == null ? MUTED : wick;
        candleWicksFollowBody = wicksFollowBody;
        repaint();
    }

    public void setChartLayerColors(Color fvgBull, Color fvgBear,
                                    Color obBull, Color obBear,
                                    Color ote,
                                    Color entry, Color stop, Color target, Color currentPrice,
                                    Color bsl, Color ssl,
                                    Color displacementBull, Color displacementBear,
                                    Color sweep, Color idm) {
        fvgBullColor = fvgBull == null ? FVG_BULL : fvgBull;
        fvgBearColor = fvgBear == null ? FVG_BEAR : fvgBear;
        obBullColor = obBull == null ? BULL : obBull;
        obBearColor = obBear == null ? BEAR : obBear;
        oteZoneColor = ote == null ? OTE_COLOR : ote;
        entryLevelColor = entry == null ? ENTRY_COLOR : entry;
        stopLevelColor = stop == null ? BEAR : stop;
        targetLevelColor = target == null ? BULL : target;
        currentPriceColor = currentPrice == null ? YELLOW : currentPrice;
        bslColor = bsl == null ? BEAR : bsl;
        sslColor = ssl == null ? BULL : ssl;
        displacementBullColor = displacementBull == null ? BULL : displacementBull;
        displacementBearColor = displacementBear == null ? BEAR : displacementBear;
        sweepMarkerColor = sweep == null ? YELLOW : sweep;
        idmMarkerColor = idm == null ? PURPLE : idm;
        repaint();
    }

    public void setAccountSnapshot(AccountSnapshot snapshot) {
        accountSnapshotKnown = snapshot != null;
        accountPositions = snapshot == null ? List.of() : snapshot.openPositions;
        repaint();
    }

    public void highlightPosition(String symbol, double entryPrice) {
        highlightedPositionSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        highlightedPositionEntry = entryPrice;
        repaint();
    }

    public void showExecutionPlan(String symbol, TradePlan plan) {
        executionOverlaySymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        executionOverlayPlan = plan;
        repaint();
    }

    public void showRadarForecastPlan(String symbol, TradePlan plan) {
        String newSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        // Only restart the fade-in when the plan actually changes.
        // Repeated calls with the same plan (e.g. after a timeframe refresh that
        // keeps the same sticky forecast) must NOT reset the fade — the panels
        // are already visible and restarting would create a jarring re-flash.
        boolean planChanged = !newSymbol.equals(radarForecastOverlaySymbol)
                || plan != radarForecastOverlayPlan;
        radarForecastOverlaySymbol = newSymbol;
        radarForecastOverlayPlan = plan;
        if (planChanged) {
            radarForecastShownAt = System.currentTimeMillis();
        }
        repaint();
    }

    public void clearRadarForecastPlan() {
        radarForecastOverlayPlan = null;
        radarForecastOverlaySymbol = "";
        radarForecastShownAt = Long.MIN_VALUE;
        repaint();
    }

    public void clearExecutionOverlay() {
        executionOverlayPlan = null;
        executionOverlaySymbol = "";
        repaint();
    }

    private void onWheel(MouseWheelEvent e) {
        if (result == null) return;
        int n = candleCount();
        if (n == 0) return;
        int delta = e.getWheelRotation();
        double anchorPrice = Double.NaN;
        double anchorRatio = 0.5;
        if (priceBarRatioLocked) {
            ensureLockedScaleInitialized();
            anchorRatio = verticalRatioForY(e.getY());
            anchorPrice = lockedPriceAtY(e.getY());
            if (e.isShiftDown()) {
                double factor = Math.pow(1.12, delta);
                lockedPricePerBar = clampDouble(lockedPricePerBar * factor, minPricePerBar(), maxPricePerBar());
                preserveVerticalAnchor(anchorPrice, anchorRatio);
                repaint();
                return;
            }
        }
        int oldCount = Math.max(1, visibleCount);
        int minVisible = Math.min(30, n);
        visibleCount = clamp(
                visibleCount + delta * 10, minVisible, n);
        double cw = plotWidth() / (double) oldCount;
        int idxUnderMouse = firstVisible + (int) ((e.getX() - paddingLeft) / cw);
        firstVisible = clamp(idxUnderMouse - visibleCount / 2,
                0, maxFirstVisible(n));
        if (priceBarRatioLocked) {
            preserveVerticalAnchor(anchorPrice, anchorRatio);
        }
        repaint();
    }

    private double candleWidth() {
        return plotWidth() / (double) Math.max(1, visibleCount);
    }

    private int plotWidth() {
        return Math.max(180, getWidth() - paddingLeft - paddingRight);
    }

    private int futureBars() {
        return Math.max(0, Math.min(visibleCount / 2, Math.max(0, visibleCount - 1)));
    }

    private int maxFirstVisible(int candleCount) {
        if (candleCount <= 0 || visibleCount <= 0) return 0;
        return Math.max(0, candleCount - visibleCount + futureBars());
    }

    private int candleCount() {
        int baseCount = result == null || result.candles == null ? 0 : result.candles.size();
        if (liveCandleOverride == null || liveCandleIndex < 0) return baseCount;
        return Math.max(baseCount, liveCandleIndex + 1);
    }

    private Candle candleAt(int index) {
        if (animatedLiveCandle != null && index == animatedLiveCandleIndex) {
            return animatedLiveCandle;
        }
        if (liveCandleOverride != null && index == liveCandleIndex) {
            return liveCandleOverride;
        }
        return result.candles.get(index);
    }

    private List<Candle> visibleCandles(int start, int end) {
        visibleCandleBuffer.clear();
        for (int i = start; i < end; i++) {
            visibleCandleBuffer.add(candleAt(i));
        }
        return visibleCandleBuffer;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        UIUtils.applyTextRendering(g);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
        overlayToggleBounds.clear();
        overlayRestoreBounds.clear();
        panelDragBounds.clear();

        if (result == null) {
            drawCenterText(g, "Выберите монету и нажмите «Обновить»");
            g.dispose();
            return;
        }

        int w = getWidth(), h = getHeight();
        int chartX = paddingLeft;
        int chartY = paddingTop;
        int chartW = plotWidth();
        int chartH = h - paddingTop - paddingBottom;

        int totalCandles = candleCount();
        if (totalCandles <= 0) {
            drawCenterText(g, "Нет свечей для отрисовки");
            g.dispose();
            return;
        }
        CinematicViewport cinematic = applyStrategyCinematicViewport(totalCandles);
        firstVisible = clamp(firstVisible, 0, maxFirstVisible(totalCandles));
        int end = Math.min(totalCandles, firstVisible + visibleCount);
        List<Candle> view = visibleCandles(firstVisible, end);

        PriceRange autoRange = computeAutoPriceRange(view);
        PriceRange displayRange = displayPriceRange(autoRange);

        PriceMapper mapper = new PriceMapper(chartY, chartH, displayRange.min, displayRange.max);
        double cw = chartW / (double) visibleCount;

        drawGrid(g, chartX, chartY, chartW, chartH, displayRange.min, displayRange.max);
        if (strategyCinematicMode) {
            drawCinematicBackdrop(g, chartX, chartY, chartW, chartH);
        }

        if (!strategyCinematicMode) {
            drawOTE(g, mapper, chartX, chartW);
            drawDeviation(g, mapper, chartX, chartW);
            drawZones(g, mapper, cw, chartX);
        }
        drawOrderFlowFootprint(g, view, mapper, cw, chartX, chartY, chartH);
        drawCandles(g, view, mapper, cw, chartX);
        if (!strategyCinematicMode) {
            drawCvdOverlay(g, view, chartX, chartY, chartW, chartH, cw);
            drawLiquidityMap(g, mapper, chartX, chartW);
            drawInstitutionalMarkers(g, mapper, cw, chartX);
        }

        if (!strategyCinematicMode && shouldDrawTradePlanOverlay()) {
            if (showForecast && visibleTradePlan() != null) {
                drawForecast(g, mapper, chartX, chartW);
            } else {
                drawPlan(g, mapper, chartX, chartW);
            }
        }

        if (!strategyCinematicMode) {
            drawChartInsightLevels(g, mapper, chartX, chartW, chartY, chartH);
        }
        drawStrategyOverlay(g, mapper, chartX, chartW, chartY, chartH);
        if (!strategyCinematicMode) {
            drawAccountPositions(g, mapper, chartX, chartW);
        }
        drawCurrentPriceLine(g, mapper, chartX, chartW);
        if (!strategyCinematicMode) {
            drawCrosshair(g, view, mapper, cw, chartX, chartY, chartW, chartH);
        }
        drawTitleBar(g, chartX, chartY);
        if (!strategyCinematicMode) {
            drawLegendBox(g, chartX, chartY, chartW);
            drawChartInsightBox(g, chartX, chartY, chartW);
            drawOverlayRestoreBar(g, chartX, chartY, chartW);
        }
        drawTimeAxis(g, view, chartX, chartY + chartH, chartW, cw);
        restoreCinematicViewport(cinematic);

        g.dispose();
    }

    private CinematicViewport applyStrategyCinematicViewport(int totalCandles) {
        CinematicViewport state = new CinematicViewport(
                firstVisible,
                visibleCount,
                priceBarRatioLocked,
                manualPriceCenter,
                lockedPricePerBar,
                strategyCinematicMode,
                strategyCinematicAlpha);
        strategyCinematicMode = false;
        strategyCinematicAlpha = 0.0f;

        TradePlan plan = visibleTradePlan();
        if (!strategyMatchesCurrentChart() || !strategySignal.suitable || plan == null || totalCandles <= 0) {
            return state;
        }
        double zoom = strategyZoomAmount();
        if (zoom <= 0.015) {
            return state;
        }

        int savedFirst = clamp(state.firstVisible, 0, maxFirstVisibleFor(totalCandles, state.visibleCount));
        int savedCount = clamp(state.visibleCount, Math.min(20, totalCandles), totalCandles);
        int targetCount = strategyTargetVisibleCount(savedCount, totalCandles);
        int focusIndex = strategyFocusIndex(plan, totalCandles);
        int targetFirst = clamp(focusIndex - targetCount * 2 / 3,
                0, maxFirstVisibleFor(totalCandles, targetCount));

        visibleCount = clamp((int) Math.round(lerp(savedCount, targetCount, zoom)),
                Math.min(20, totalCandles), totalCandles);
        firstVisible = clamp((int) Math.round(lerp(savedFirst, targetFirst, zoom)),
                0, maxFirstVisibleFor(totalCandles, visibleCount));

        PriceRange baseRange = baseCinematicPriceRange(state, totalCandles);
        PriceRange staticFocusRange = strategyFocusPriceRange(plan, baseRange);

        // Dynamic camera: once fully zoomed in, shift center to follow the current arrow tip.
        // We keep the zoom RANGE constant (±1.8R or the static range, whichever is tighter),
        // but move the center price along the drawing sequence.
        double animProgress = strategyAnimationProgress();
        double cameraPrice  = strategyCameraPrice(animProgress, plan);
        double r1 = (Double.isFinite(plan.entry) && Double.isFinite(plan.stop))
                ? Math.abs(plan.entry - plan.stop) : 0.0;
        double minRange = Math.max(r1 * 1.6,
                Math.max(plan.entry * 0.0028, priceAnimationTolerance() * 60.0));
        double tightHalf = Math.min(staticFocusRange.range() / 2.0, minRange);

        PriceRange focusRange;
        if (Double.isFinite(cameraPrice) && cameraPrice > 0.0 && animProgress > 0.14) {
            // Camera-following mode: tight half-range so the tip fills most of the screen
            double half = Math.max(tightHalf, staticFocusRange.range() * 0.14);
            focusRange = new PriceRange(cameraPrice - half, cameraPrice + half);
        } else {
            focusRange = staticFocusRange;
        }

        double targetRange  = lerp(baseRange.range(), focusRange.range(), zoom);
        double targetCenter = lerp(baseRange.center(), focusRange.center(), zoom);
        priceBarRatioLocked = true;
        manualPriceCenter = targetCenter;
        lockedPricePerBar = Math.max(minPricePerBar(), targetRange / Math.max(1, visibleCount));

        strategyCinematicAlpha = (float) clampDouble(zoom, 0.0, 1.0);
        strategyCinematicMode = strategyCinematicAlpha > 0.12f;
        return state;
    }

    private void restoreCinematicViewport(CinematicViewport state) {
        if (state == null) return;
        firstVisible = state.firstVisible;
        visibleCount = state.visibleCount;
        priceBarRatioLocked = state.priceBarRatioLocked;
        manualPriceCenter = state.manualPriceCenter;
        lockedPricePerBar = state.lockedPricePerBar;
        strategyCinematicMode = state.previousCinematicMode;
        strategyCinematicAlpha = state.previousCinematicAlpha;
    }

    private int strategyTargetVisibleCount(int currentCount, int totalCandles) {
        int minimum = Math.min(totalCandles, 34);
        int target = (int) Math.round(currentCount * 0.42);
        return clamp(target, minimum, Math.max(minimum, currentCount));
    }

    private int strategyFocusIndex(TradePlan plan, int totalCandles) {
        int last = Math.max(0, totalCandles - 1);
        int anchor = last;
        if (plan != null && plan.sweepIndex >= 0) {
            anchor = Math.max(anchor - 12, plan.sweepIndex);
        }
        if (plan != null && plan.chochIndex >= 0) {
            anchor = Math.max(anchor - 10, plan.chochIndex);
        }
        if (plan != null && plan.zone != null && plan.zone.creationIndex >= 0) {
            anchor = Math.max(anchor - 18, plan.zone.creationIndex);
        }
        return clamp(anchor, 0, last);
    }

    /** Converts an absolute candle index to a screen X pixel at the center of that bar. */
    private int indexToX(int index, int chartX, int chartW) {
        int local = index - firstVisible;
        double cw = chartW / (double) Math.max(1, visibleCount);
        return (int) Math.round(chartX + local * cw + cw / 2.0);
    }

    /**
     * Returns the screen X that should anchor all strategy overlay elements.
     * Tries (in order): sweep index → CHoCH index → zone creation index → w/4 fallback.
     * Result is clamped to ensure TP arrows have room to the right.
     */
    private int computeStrategyAnchorX(TradePlan plan, int chartX, int chartW) {
        int fallback = chartX + Math.min(chartW - 340, Math.max(160, chartW / 4));
        if (plan == null) return fallback;
        if (plan.sweepIndex >= 0) {
            int sx = indexToX(plan.sweepIndex, chartX, chartW);
            if (sx > chartX + 40 && sx < chartX + chartW - 160) return sx;
        }
        if (plan.chochIndex >= 0) {
            int cx = indexToX(plan.chochIndex, chartX, chartW);
            if (cx > chartX + 40 && cx < chartX + chartW - 160) return cx;
        }
        if (plan.zone != null && plan.zone.creationIndex >= 0) {
            int zx = indexToX(plan.zone.creationIndex, chartX, chartW);
            if (zx > chartX + 40 && zx < chartX + chartW - 160) return zx;
        }
        return fallback;
    }

    private PriceRange baseCinematicPriceRange(CinematicViewport state, int totalCandles) {
        int safeCount = clamp(state.visibleCount, Math.min(20, totalCandles), totalCandles);
        int safeFirst = clamp(state.firstVisible, 0, maxFirstVisibleFor(totalCandles, safeCount));
        if (state.priceBarRatioLocked
                && Double.isFinite(state.manualPriceCenter)
                && Double.isFinite(state.lockedPricePerBar)
                && state.lockedPricePerBar > 0.0) {
            double range = state.lockedPricePerBar * Math.max(1, safeCount);
            return new PriceRange(state.manualPriceCenter - range / 2.0,
                    state.manualPriceCenter + range / 2.0);
        }
        int end = Math.min(totalCandles, safeFirst + safeCount);
        return computeAutoPriceRange(visibleCandles(safeFirst, end));
    }

    private PriceRange strategyFocusPriceRange(TradePlan plan, PriceRange fallback) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double[] prices = {
                result == null ? Double.NaN : result.currentPrice,
                plan == null ? Double.NaN : plan.entry,
                plan == null ? Double.NaN : plan.stop,
                plan == null ? Double.NaN : plan.tp1,
                plan == null ? Double.NaN : plan.tp2,
                plan == null ? Double.NaN : plan.tp3,
                plan == null ? Double.NaN : plan.liquidityTarget
        };
        for (double price : prices) {
            if (Double.isFinite(price) && price > 0.0) {
                min = Math.min(min, price);
                max = Math.max(max, price);
            }
        }
        Zone zone = strategyFocusZone(plan);
        if (zone != null) {
            min = Math.min(min, zone.bottom);
            max = Math.max(max, zone.top);
        }
        LiquiditySweep sweep = strategySweep(plan);
        if (sweep != null && sweep.level != null && Double.isFinite(sweep.level.price)) {
            min = Math.min(min, sweep.level.price);
            max = Math.max(max, sweep.level.price);
        }
        if (strategySignal != null) {
            for (StrategySignal.Level level : strategySignal.levels) {
                if (level == null || !Double.isFinite(level.price) || level.price <= 0.0) continue;
                if (level.type == StrategySignal.LevelType.TARGET
                        || level.type == StrategySignal.LevelType.ENTRY
                        || level.type == StrategySignal.LevelType.STOP
                        || level.type == StrategySignal.LevelType.INVALIDATION
                        || level.type == StrategySignal.LevelType.ZONE) {
                    min = Math.min(min, level.price);
                    max = Math.max(max, level.price);
                }
            }
        }
        if (min == Double.MAX_VALUE || max == -Double.MAX_VALUE || max <= min) {
            return fallback;
        }
        double reference = Math.max(Math.abs((min + max) / 2.0), 0.00000001);
        double minimumRange = Math.max(reference * 0.0035, priceAnimationTolerance() * 80.0);
        double range = Math.max(max - min, minimumRange);
        double padding = Math.max(range * 0.24, minimumRange * 0.35);
        return new PriceRange(min - padding, max + padding);
    }

    private int maxFirstVisibleFor(int candleCount, int count) {
        if (candleCount <= 0 || count <= 0) return 0;
        int future = Math.max(0, Math.min(count / 2, Math.max(0, count - 1)));
        return Math.max(0, candleCount - count + future);
    }

    private double strategyZoomAmount() {
        if (strategyAnimationStartedAt <= 0L) return 0.0;
        double progress = strategyAnimationProgress();
        // Zoom in quickly, hold through the whole arrow sequence, exit only at the very end
        double zoomIn  = smoothStep(0.02, 0.16, progress);
        double zoomOut = 1.0 - smoothStep(0.88, 1.0, progress);
        return clampDouble(zoomIn * zoomOut, 0.0, 1.0);
    }

    /**
     * Returns the price the cinematic camera should center on at a given animation progress.
     * The camera follows the tip of each arrow as it draws:
     *   0.00-0.20  → zone mid  (zone glow appears)
     *   0.18-0.40  → sweep price → entry  (arrow-1 "wait entry" drawing)
     *   0.40-0.50  → hold at entry  (entry node pulses)
     *   0.48-0.76  → entry → tp1  (arrow-2 "bounce" drawing)
     *   0.66-0.90  → tp1 → target  (arrow-3/5 "TP path / liquidity draw")
     *   0.90-1.00  → hold at target
     */
    private double strategyCameraPrice(double progress, TradePlan plan) {
        if (plan == null || !Double.isFinite(plan.entry)) return Double.NaN;
        double zonePrice = plan.zone != null ? plan.zone.mid() : plan.entry;
        LiquiditySweep sw = strategySweep(plan);
        double sweepPrice = sw != null && sw.level != null && Double.isFinite(sw.level.price)
                ? sw.level.price : zonePrice;
        StrategySignal.Level tgt = bestStrategyLevel(StrategySignal.LevelType.TARGET);
        double targetPrice = tgt != null && Double.isFinite(tgt.price) && tgt.price > 0.0
                ? tgt.price : plan.tp3;
        if (!Double.isFinite(targetPrice) || targetPrice <= 0.0) targetPrice = plan.tp2;
        if (!Double.isFinite(targetPrice) || targetPrice <= 0.0) targetPrice = plan.tp1;

        if (progress <= 0.20) {
            return lerp(sweepPrice, zonePrice, smoothStep(0.0, 0.20, progress));
        }
        if (progress <= 0.40) {
            return lerp(zonePrice, plan.entry, smoothStep(0.18, 0.40, progress));
        }
        if (progress <= 0.50) {
            return plan.entry;
        }
        if (progress <= 0.76) {
            return lerp(plan.entry, plan.tp1, smoothStep(0.48, 0.76, progress));
        }
        if (progress <= 0.92) {
            return lerp(plan.tp1, targetPrice, smoothStep(0.66, 0.92, progress));
        }
        return targetPrice;
    }

    private double smoothStep(double start, double end, double value) {
        double t = clampDouble((value - start) / Math.max(0.0001, end - start), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private void drawCinematicBackdrop(Graphics2D g, int x, int y, int w, int h) {
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        float alpha = (float) clampDouble(strategyCinematicAlpha, 0.0, 1.0);

        // Dark overlay
        g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.28f));
        g.setColor(Color.BLACK);
        g.fillRect(x, y, w, h);

        // Subtle scanlines (every 4px) for cinematic depth
        g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.04f));
        g.setColor(Color.BLACK);
        for (int lineY = y; lineY < y + h; lineY += 4) {
            g.fillRect(x, lineY, w, 1);
        }

        // Edge gradient vignette — two semi-transparent gradient rectangles
        java.awt.GradientPaint leftVig = new java.awt.GradientPaint(
                x, y, new Color(0, 0, 0, (int)(60 * alpha)),
                x + Math.min(w / 5, 80), y, new Color(0, 0, 0, 0));
        java.awt.GradientPaint rightVig = new java.awt.GradientPaint(
                x + w - Math.min(w / 5, 80), y, new Color(0, 0, 0, 0),
                x + w, y, new Color(0, 0, 0, (int)(60 * alpha)));
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(leftVig);
        g.fillRect(x, y, Math.min(w / 5, 80), h);
        g.setPaint(rightVig);
        g.fillRect(x + w - Math.min(w / 5, 80), y, Math.min(w / 5, 80), h);
        g.setPaint(null);

        // Cyan border frame
        g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.72f));
        g.setStroke(new BasicStroke(1.25f));
        g.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 190));
        g.drawRoundRect(x + 8, y + 8, w - 16, h - 16, 18, 18);

        drawStrategyChip(g, "focus replay · лишний шум скрыт", x + 18, y + 14, CYAN, Math.max(0.35f, alpha));
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);
    }

    private void drawCenterText(Graphics2D g, String t) {
        g.setColor(MUTED);
        g.setFont(UIUtils.titleFont(16f));
        FontMetrics fm = g.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(t)) / 2;
        int y = getHeight() / 2;
        g.drawString(t, x, y);
    }

    private void drawGrid(Graphics2D g, int x, int y, int w, int h, double min, double max) {
        int rows = 8;
        for (int i = 0; i <= rows; i++) {
            int yy = y + i * h / rows;
            g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 160));
            g.drawLine(x, yy, x + w, yy);
            double price = max - (max - min) * i / rows;
            g.setColor(MUTED);
            g.setFont(axisFont);
            g.drawString(String.format("%.4f", price), x + w + 6, yy + 4);
        }
    }

    private void drawTimeAxis(Graphics2D g, List<Candle> view, int x, int yBaseline, int w, double cw) {
        int axisBottom = getHeight();
        g.setColor(BG);
        g.fillRect(x, yBaseline + 1, w, Math.max(1, axisBottom - yBaseline - 1));
        g.setColor(GRID);
        g.drawLine(x, yBaseline, x + w, yBaseline);
        g.setColor(MUTED);
        g.setFont(axisFont);
        if (view.isEmpty()) return;

        FontMetrics fm = g.getFontMetrics();
        boolean sameDay = sameDay(view.get(0), view.get(view.size() - 1));
        DateTimeFormatter fmt = sameDay ? TIME_AXIS_SAME_DAY : TIME_AXIS_DATE_TIME;
        int sampleWidth = fm.stringWidth(sameDay ? "22:22" : "22.22 22:22");
        int minGap = sampleWidth + 28;
        int step = Math.max(1, (int) Math.ceil(minGap / Math.max(cw, 1.0)));
        int lastRight = x - minGap;

        for (int i = 0; i < view.size(); i += step) {
            int cx = (int) (x + i * cw + cw / 2);
            LocalDateTime t = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(view.get(i).time),
                    ZoneId.systemDefault());
            String text = fmt.format(t);
            int tw = fm.stringWidth(text);
            int lx = clamp(cx - tw / 2, x + 2, x + w - tw - 2);
            if (lx <= lastRight + 12) continue;
            g.drawLine(cx, yBaseline, cx, yBaseline + 4);
            g.drawString(text, lx, yBaseline + 20);
            lastRight = lx + tw;
        }
        drawLastTimeLabel(g, view, x, yBaseline, w, cw, fmt, lastRight);
    }

    private void drawLastTimeLabel(Graphics2D g, List<Candle> view, int x, int yBaseline, int w,
                                   double cw, DateTimeFormatter fmt, int lastRight) {
        if (view.isEmpty()) return;
        int i = view.size() - 1;
        int cx = (int) (x + i * cw + cw / 2);
        LocalDateTime t = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(view.get(i).time),
                ZoneId.systemDefault());
        String text = fmt.format(t);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int lx = clamp(cx - tw / 2, x + 2, x + w - tw - 2);
        if (lx <= lastRight + 12) return;
        g.drawLine(cx, yBaseline, cx, yBaseline + 4);
        g.drawString(text, lx, yBaseline + 20);
    }

    private boolean sameDay(Candle a, Candle b) {
        LocalDateTime first = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.time), ZoneId.systemDefault());
        LocalDateTime last = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.time), ZoneId.systemDefault());
        return first.toLocalDate().equals(last.toLocalDate());
    }

    private PriceRange computeAutoPriceRange(List<Candle> view) {
        PriceRange candleRange = computeVisibleCandlePriceRange(view);
        double min = candleRange.min;
        double max = candleRange.max;
        TradePlan visiblePlan = visibleTradePlan();
        if (visiblePlan != null) {
            double[] lines = {visiblePlan.entry,
                    visiblePlan.stop, visiblePlan.tp1,
                    visiblePlan.tp2, visiblePlan.tp3};
            for (double v : lines) {
                PriceRange expanded = expandRangeIfHealthy(new PriceRange(min, max), v);
                min = expanded.min;
                max = expanded.max;
            }
        }
        if (result != null && Double.isFinite(result.currentPrice)) {
            min = Math.min(min, result.currentPrice);
            max = Math.max(max, result.currentPrice);
        }
        if (result != null && result.deviationContext != null && result.deviationContext.available) {
            PriceRange expanded = expandRangeIfHealthy(new PriceRange(min, max), result.deviationContext.rangeLow);
            expanded = expandRangeIfHealthy(expanded, result.deviationContext.rangeHigh);
            expanded = expandRangeIfHealthy(expanded, result.deviationContext.deviationPrice);
            min = expanded.min;
            max = expanded.max;
        }
        for (OpenPositionSnapshot position : positionsForCurrentSymbol()) {
            if (position.entryPrice > 0.0) {
                PriceRange expanded = expandRangeIfHealthy(new PriceRange(min, max), position.entryPrice);
                min = expanded.min;
                max = expanded.max;
            }
            if (position.markPrice > 0.0) {
                PriceRange expanded = expandRangeIfHealthy(new PriceRange(min, max), position.markPrice);
                min = expanded.min;
                max = expanded.max;
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || min == Double.MAX_VALUE || max == -Double.MAX_VALUE) {
            double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                    ? 1.0
                    : result.currentPrice;
            min = ref * 0.995;
            max = ref * 1.005;
        }
        if (Math.abs(max - min) < 0.00000001) {
            double center = max;
            double padFlat = Math.max(0.0001, Math.abs(center) * 0.0005);
            min = center - padFlat;
            max = center + padFlat;
        }
        double pad = (max - min) * 0.1;
        return new PriceRange(min - pad, max + pad);
    }

    private PriceRange computeVisibleCandlePriceRange(List<Candle> view) {
        double wickMin = Double.MAX_VALUE;
        double wickMax = -Double.MAX_VALUE;
        double bodyMin = Double.MAX_VALUE;
        double bodyMax = -Double.MAX_VALUE;
        if (view != null) {
            for (Candle c : view) {
                if (c == null) continue;
                if (Double.isFinite(c.low)) wickMin = Math.min(wickMin, c.low);
                if (Double.isFinite(c.high)) wickMax = Math.max(wickMax, c.high);
                if (Double.isFinite(c.open)) {
                    bodyMin = Math.min(bodyMin, c.open);
                    bodyMax = Math.max(bodyMax, c.open);
                }
                if (Double.isFinite(c.close)) {
                    bodyMin = Math.min(bodyMin, c.close);
                    bodyMax = Math.max(bodyMax, c.close);
                }
            }
        }
        if (result != null && Double.isFinite(result.currentPrice) && result.currentPrice > 0.0) {
            bodyMin = Math.min(bodyMin, result.currentPrice);
            bodyMax = Math.max(bodyMax, result.currentPrice);
            wickMin = Math.min(wickMin, result.currentPrice);
            wickMax = Math.max(wickMax, result.currentPrice);
        }
        if (!Double.isFinite(wickMin) || !Double.isFinite(wickMax)
                || wickMin == Double.MAX_VALUE || wickMax == -Double.MAX_VALUE) {
            double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                    ? 1.0
                    : result.currentPrice;
            return new PriceRange(ref * 0.995, ref * 1.005);
        }
        if (!Double.isFinite(bodyMin) || !Double.isFinite(bodyMax)
                || bodyMin == Double.MAX_VALUE || bodyMax == -Double.MAX_VALUE) {
            return new PriceRange(wickMin, wickMax);
        }

        double bodyRange = Math.max(0.00000001, bodyMax - bodyMin);
        double wickRange = Math.max(0.00000001, wickMax - wickMin);
        double ref = Math.max(Math.abs((bodyMin + bodyMax) / 2.0), 0.00000001);
        boolean suspiciousOutlier = wickRange > bodyRange * 8.0 && wickRange > ref * 0.025;
        if (!suspiciousOutlier) {
            return new PriceRange(wickMin, wickMax);
        }

        double center = (bodyMin + bodyMax) / 2.0;
        double allowedHalfRange = Math.max(bodyRange * 3.0, ref * 0.006);
        double min = Math.max(wickMin, Math.min(bodyMin, center - allowedHalfRange));
        double max = Math.min(wickMax, Math.max(bodyMax, center + allowedHalfRange));
        if (max <= min) return new PriceRange(wickMin, wickMax);
        return new PriceRange(min, max);
    }

    private PriceRange expandRangeIfHealthy(PriceRange base, double level) {
        if (base == null || !Double.isFinite(level) || level <= 0.0) return base;
        double min = Math.min(base.min, level);
        double max = Math.max(base.max, level);
        double newRange = Math.max(0.00000001, max - min);
        double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                ? Math.max(Math.abs(base.center()), 1.0)
                : Math.abs(result.currentPrice);
        double maxHealthyRange = Math.max(base.range() * 2.8, ref * 0.018);
        if (newRange > maxHealthyRange) return base;
        return new PriceRange(min, max);
    }

    private PriceRange displayPriceRange(PriceRange autoRange) {
        if (!priceBarRatioLocked) return autoRange;
        ensureLockedScaleInitialized(autoRange);
        if (!lockedScaleValid()) return autoRange;
        double range = lockedPriceRange();
        return new PriceRange(manualPriceCenter - range / 2.0, manualPriceCenter + range / 2.0);
    }

    private void ensureLockedScaleInitialized() {
        ensureLockedScaleInitialized(computeAutoPriceRange(currentVisibleCandlesForScale()));
    }

    private void ensureLockedScaleInitialized(PriceRange autoRange) {
        if (!priceBarRatioLocked || lockedScaleValid()) return;
        PriceRange range = autoRange == null ? computeAutoPriceRange(currentVisibleCandlesForScale()) : autoRange;
        manualPriceCenter = range.center();
        lockedPricePerBar = clampDouble(range.range() / Math.max(1, visibleCount),
                minPricePerBar(), maxPricePerBar());
    }

    private List<Candle> currentVisibleCandlesForScale() {
        int totalCandles = candleCount();
        if (totalCandles <= 0) return List.of();
        firstVisible = clamp(firstVisible, 0, maxFirstVisible(totalCandles));
        int end = Math.min(totalCandles, firstVisible + visibleCount);
        return visibleCandles(firstVisible, end);
    }

    private boolean lockedScaleValid() {
        return Double.isFinite(manualPriceCenter)
                && Double.isFinite(lockedPricePerBar)
                && lockedPricePerBar > 0.0;
    }

    private void resetLockedScale() {
        manualPriceCenter = Double.NaN;
        lockedPricePerBar = Double.NaN;
    }

    private double lockedPriceRange() {
        if (!lockedScaleValid()) return 0.0;
        return Math.max(minPricePerBar(), lockedPricePerBar) * Math.max(1, visibleCount);
    }

    private double verticalRatioForY(int y) {
        int h = Math.max(1, chartHeight());
        return clampDouble((paddingTop + h - y) / (double) h, 0.0, 1.0);
    }

    private double lockedPriceAtY(int y) {
        if (!lockedScaleValid()) return Double.NaN;
        double range = lockedPriceRange();
        double min = manualPriceCenter - range / 2.0;
        return min + verticalRatioForY(y) * range;
    }

    private void preserveVerticalAnchor(double anchorPrice, double anchorRatio) {
        if (!Double.isFinite(anchorPrice) || !lockedScaleValid()) return;
        double range = lockedPriceRange();
        manualPriceCenter = anchorPrice + (0.5 - anchorRatio) * range;
    }

    private double minPricePerBar() {
        double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                ? 1.0
                : Math.abs(result.currentPrice);
        return Math.max(0.00000001, ref * 0.00000001);
    }

    private double maxPricePerBar() {
        double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                ? 1.0
                : Math.abs(result.currentPrice);
        return Math.max(minPricePerBar() * 10.0, ref * 0.10);
    }

    private int chartHeight() {
        return Math.max(1, getHeight() - paddingTop - paddingBottom);
    }

    private void syncLiveCandle(AnalysisResult data, double price, boolean allowAppend) {
        if (data == null || data.candles == null || data.candles.isEmpty()
                || !Double.isFinite(price) || price <= 0.0) {
            liveCandleOverride = null;
            liveCandleIndex = -1;
            return;
        }
        List<Candle> candles = data.candles;
        int baseCount = candles.size();
        int lastIndex = baseCount - 1;
        Candle last = candles.get(lastIndex);
        if (liveCandleOverride != null && liveCandleIndex >= lastIndex) {
            last = liveCandleOverride;
            lastIndex = liveCandleIndex;
        }
        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(data.ltfInterval));
        long now = System.currentTimeMillis();
        boolean pinnedRight = firstVisible >= maxFirstVisible(candleCount()) - 1;

        Candle live;
        int targetIndex;
        if (allowAppend && now >= last.time + intervalMs) {
            long steps = Math.max(1L, (now - last.time) / intervalMs);
            long nextOpen = last.time + steps * intervalMs;
            double open = last.close > 0.0 ? last.close : price;
            live = new Candle(nextOpen, open, Math.max(open, price), Math.min(open, price), price, 0.0);
            targetIndex = baseCount;
            try {
                if (lastIndex < baseCount) {
                    candles.add(live);
                    targetIndex = candles.size() - 1;
                }
            } catch (UnsupportedOperationException ignored) {
                // Immutable lists are rendered through liveCandleOverride below.
            }
        } else {
            live = new Candle(
                    last.time,
                    last.open,
                    Math.max(last.high, price),
                    Math.min(last.low, price),
                    price,
                    last.volume);
            targetIndex = Math.min(lastIndex, baseCount - 1);
            try {
                if (targetIndex >= 0 && targetIndex < baseCount) {
                    candles.set(targetIndex, live);
                }
            } catch (UnsupportedOperationException ignored) {
                // Immutable lists are rendered through liveCandleOverride below.
            }
        }

        liveCandleOverride = live;
        liveCandleIndex = targetIndex;
        if (pinnedRight) firstVisible = maxFirstVisible(candleCount());
    }

    private void resetLiveAnimationState(double price) {
        priceLineSmoother.reset(price);
        animatedLiveCandle = null;
        liveCandleAnimationTarget = null;
        animatedLiveCandleIndex = -1;
        liveCandleFadeStartedAt = Long.MIN_VALUE;
        newLiveCandleFadeActive = false;
        levelVisualStates.clear();
        levelVisualStatesPlan = null;
        levelPulses.clear();
    }

    private Candle currentLiveVisualCandle() {
        if (animatedLiveCandle != null) return animatedLiveCandle;
        if (liveCandleOverride != null) return liveCandleOverride;
        if (result == null || result.candles == null || result.candles.isEmpty()) return null;
        return result.candles.get(result.candles.size() - 1);
    }

    private void prepareLiveCandleAnimation(int previousIndex, Candle previousVisual) {
        if (liveCandleOverride == null || liveCandleIndex < 0) {
            animatedLiveCandle = null;
            liveCandleAnimationTarget = null;
            animatedLiveCandleIndex = -1;
            return;
        }
        liveCandleAnimationTarget = liveCandleOverride;
        boolean newCandle = previousIndex >= 0 && liveCandleIndex != previousIndex;
        if (animatedLiveCandle == null || animatedLiveCandleIndex != liveCandleIndex || newCandle) {
            animatedLiveCandleIndex = liveCandleIndex;
            animatedLiveCandle = newCandleStart(liveCandleOverride, previousVisual, previousIndex == liveCandleIndex);
        }
        if (newCandle) {
            liveCandleFadeStartedAt = System.currentTimeMillis();
            newLiveCandleFadeActive = true;
        }
    }

    private void snapLiveCandleAnimation() {
        liveCandleAnimationTarget = liveCandleOverride;
        animatedLiveCandle = liveCandleOverride;
        animatedLiveCandleIndex = liveCandleIndex;
        newLiveCandleFadeActive = false;
        liveCandleFadeStartedAt = Long.MIN_VALUE;
    }

    private Candle newCandleStart(Candle target, Candle previousVisual, boolean sameIndex) {
        if (sameIndex && previousVisual != null) return previousVisual;
        double open = Double.isFinite(target.open) && target.open > 0.0 ? target.open : target.close;
        return new Candle(target.time, open, open, open, open, target.volume);
    }

    private void onLiveAnimationFrame() {
        if (result == null || !isShowing()) return;
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        boolean changed = priceLineSmoother.tick(nowNanos, priceAnimationTolerance());
        changed |= tickLiveCandleAnimation();
        changed |= retireLevelPulses(nowMillis);
        changed |= strategyAnimationActive(nowMillis);
        // Drive the radar forecast panel fade-in (900ms smoothstep).
        // Without this the panels would only repaint at 1fps via candleCountdownTimer.
        changed |= (radarForecastShownAt != Long.MIN_VALUE
                && nowMillis - radarForecastShownAt < 920L);
        if (changed) repaint();
    }

    private boolean tickLiveCandleAnimation() {
        if (liveCandleAnimationTarget == null || animatedLiveCandle == null) {
            return false;
        }
        Candle before = animatedLiveCandle;
        animatedLiveCandle = blendCandle(animatedLiveCandle, liveCandleAnimationTarget, 0.30);
        if (sameCandleVisual(animatedLiveCandle, liveCandleAnimationTarget, priceAnimationTolerance())) {
            animatedLiveCandle = liveCandleAnimationTarget;
        }
        boolean fadeChanged = false;
        if (newLiveCandleFadeActive) {
            long age = System.currentTimeMillis() - liveCandleFadeStartedAt;
            if (age >= 520L) {
                newLiveCandleFadeActive = false;
            }
            fadeChanged = true;
        }
        return fadeChanged || !sameCandleVisual(before, animatedLiveCandle, priceAnimationTolerance());
    }

    private Candle blendCandle(Candle from, Candle to, double amount) {
        double k = clampDouble(amount, 0.0, 1.0);
        double open = lerp(from.open, to.open, k);
        double close = lerp(from.close, to.close, k);
        double high = Math.max(Math.max(open, close), lerp(from.high, to.high, k));
        double low = Math.min(Math.min(open, close), lerp(from.low, to.low, k));
        double volume = lerp(from.volume, to.volume, k);
        return new Candle(to.time, open, high, low, close, volume);
    }

    private double lerp(double from, double to, double amount) {
        if (!Double.isFinite(from)) return to;
        if (!Double.isFinite(to)) return from;
        return from + (to - from) * amount;
    }

    private boolean sameCandleVisual(Candle a, Candle b, double tolerance) {
        if (a == null || b == null) return a == b;
        return a.time == b.time
                && Math.abs(a.open - b.open) <= tolerance
                && Math.abs(a.high - b.high) <= tolerance
                && Math.abs(a.low - b.low) <= tolerance
                && Math.abs(a.close - b.close) <= tolerance;
    }

    private double displayCurrentPrice() {
        return result == null ? Double.NaN : priceLineSmoother.valueOr(result.currentPrice);
    }

    private double priceAnimationTolerance() {
        double ref = result == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0
                ? 1.0
                : Math.abs(result.currentPrice);
        return Math.max(0.00000001, ref * 0.000001);
    }

    private float liveCandleAlpha(int globalIndex) {
        if (!newLiveCandleFadeActive || globalIndex != animatedLiveCandleIndex) return 1.0f;
        long age = Math.max(0L, System.currentTimeMillis() - liveCandleFadeStartedAt);
        if (age >= 520L) return 1.0f;
        double t = age / 520.0;
        double eased = t * t * (3.0 - 2.0 * t);
        return (float) Math.max(0.18, Math.min(1.0, eased));
    }

    private void refreshLevelPulses() {
        TradePlan plan = visibleTradePlan();
        if (result == null || plan == null || !Double.isFinite(result.currentPrice)) return;
        if (plan != levelVisualStatesPlan) {
            // A new TradePlan means new SL/TP price levels, so levelKey() (which embeds
            // plan.entry) starts minting brand new map keys. Without clearing here, the old
            // plan's entries are never looked up again but also never removed -- levelVisualStates
            // grew by ~4 entries every re-analysis (every closed candle) for as long as a symbol
            // stayed open, unbounded for the life of the session.
            levelVisualStates.clear();
            levelVisualStatesPlan = plan;
        }
        rememberLevelState(plan, "SL", plan.stop);
        rememberLevelState(plan, "TP1", plan.tp1);
        rememberLevelState(plan, "TP2", plan.tp2);
        rememberLevelState(plan, "TP3", plan.tp3);
    }

    private void rememberLevelState(TradePlan plan, String name, double level) {
        if (plan == null || !Double.isFinite(level)) return;
        LevelProjection projection = projectLevel(plan, level);
        String state = projection == null ? "idle"
                : projection.reached ? "hit"
                : projection.exitAlert ? "late"
                : "open";
        String key = levelKey(plan, name, level);
        String previous = levelVisualStates.put(key, state);
        if (previous != null && !previous.equals(state) && ("hit".equals(state) || "late".equals(state))) {
            levelPulses.computeIfAbsent(key, ignored -> new FadePulse(1800L))
                    .trigger(System.currentTimeMillis());
        }
    }

    private boolean retireLevelPulses(long nowMillis) {
        boolean hadActive = false;
        Iterator<Map.Entry<String, FadePulse>> it = levelPulses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FadePulse> entry = it.next();
            if (entry.getValue().active(nowMillis)) {
                hadActive = true;
            } else {
                it.remove();
            }
        }
        return hadActive;
    }

    private float levelPulseAlpha(TradePlan plan, double level) {
        String name = evidenceLevelName(plan, level);
        if (name == null) return 0.0f;
        FadePulse pulse = levelPulses.get(levelKey(plan, name, level));
        return pulse == null ? 0.0f : pulse.alpha(System.currentTimeMillis());
    }

    private String levelKey(TradePlan plan, String name, double level) {
        if (plan == null) return name + ":" + level;
        long bucket = Math.round(level * 1_000_000.0);
        long entryBucket = Math.round(plan.entry * 1_000_000.0);
        return (result == null ? "" : result.symbol) + "|" + plan.longSide + "|" + entryBucket + "|" + name + "|" + bucket;
    }

    private void drawOTE(Graphics2D g, PriceMapper m, int chartX, int chartW) {
        if (result == null) return;
        double h = result.structHigh;
        double l = result.structLow;
        double range = h - l;

        double oteTop = result.htfBullish ? (l + range * 0.786) : (h - range * 0.618);
        double oteBot = result.htfBullish ? (l + range * 0.618) : (h - range * 0.786);

        int yTop = m.y(oteTop);
        int yBot = m.y(oteBot);

        int oteFillAlpha = alpha(oteOpacity, 0.75);
        int oteStrokeAlpha = strokeAlpha(oteOpacity, 0.92, 58, 185);
        g.setColor(new Color(oteZoneColor.getRed(), oteZoneColor.getGreen(), oteZoneColor.getBlue(), oteFillAlpha));
        g.fillRect(chartX, Math.min(yTop, yBot), chartW, Math.abs(yBot - yTop));

        g.setColor(new Color(oteZoneColor.getRed(), oteZoneColor.getGreen(), oteZoneColor.getBlue(), oteStrokeAlpha));
        g.drawRect(chartX, Math.min(yTop, yBot), chartW, Math.abs(yBot - yTop));

        g.setFont(labelFont);
        drawZoneLabel(g, "OTE", chartX, Math.min(yTop, yBot), Math.max(yTop, yBot), false, oteZoneColor, oteStrokeAlpha);
    }

    private void drawDeviation(Graphics2D g, PriceMapper m, int chartX, int chartW) {
        if (result == null || result.deviationContext == null || !result.deviationContext.available) return;
        var d = result.deviationContext;
        if (!Double.isFinite(d.rangeHigh) || !Double.isFinite(d.rangeLow)) return;
        int yHigh = m.y(d.rangeHigh);
        int yLow = m.y(d.rangeLow);
        int yDev = m.y(d.deviationPrice);
        Color color = d.reversalDirection.bullish() ? displacementBullColor : displacementBearColor;
        int fillAlpha = clamp(10 + d.score / 4, 12, 38);
        int lineAlpha = clamp(70 + d.score, 90, 190);

        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), fillAlpha));
        g.fillRect(chartX, Math.min(yHigh, yLow), chartW, Math.max(1, Math.abs(yLow - yHigh)));

        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                1f, new float[]{5f, 5f}, 0f));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), lineAlpha));
        g.drawLine(chartX, yHigh, chartX + chartW, yHigh);
        g.drawLine(chartX, yLow, chartX + chartW, yLow);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(80, lineAlpha - 20)));
        g.drawLine(chartX, yDev, chartX + chartW, yDev);
        g.setStroke(old);

        g.setFont(labelFont);
        String label = "DEV " + d.score + " · " + (d.reversalDirection.bullish() ? "LONG" : "SHORT");
        int labelY = Math.max(18, Math.min(yHigh, yLow) + 18);
        drawInlineBadge(g, label, chartX + 7, labelY, color);
    }

    private void drawInlineBadge(Graphics2D g, String text, int x, int baselineY, Color color) {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 12;
        int h = 17;
        int y = baselineY - 13;
        g.setColor(glassColor(185));
        g.fillRoundRect(x, y, w, h, 5, 5);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
        g.drawRoundRect(x, y, w, h, 5, 5);
        Color bright = brighten(color, 45);
        g.setColor(new Color(bright.getRed(), bright.getGreen(), bright.getBlue(), 235));
        g.drawString(text, x + 6, baselineY);
    }

    private void drawCandles(Graphics2D g, List<Candle> view, PriceMapper m, double cw, int x) {
        double bodyW = Math.max(1, cw * 0.7);
        for (int i = 0; i < view.size(); i++) {
            Candle c = view.get(i);
            int globalIndex = firstVisible + i;
            float candleAlpha = liveCandleAlpha(globalIndex);
            Composite oldComposite = null;
            if (candleAlpha < 0.999f) {
                oldComposite = g.getComposite();
                g.setComposite(AlphaComposite.SrcOver.derive(candleAlpha));
            }
            double cx = x + i * cw + cw / 2;
            int yHigh = m.y(c.high);
            int yLow  = m.y(c.low);
            int yOpen = m.y(c.open);
            int yClose= m.y(c.close);

            Color col = c.bullish() ? bullCandleColor : c.bearish() ? bearCandleColor : neutralCandleColor;
            g.setColor(candleWicksFollowBody ? col : wickCandleColor);
            g.drawLine((int) cx, yHigh, (int) cx, yLow);
            int top = Math.min(yOpen, yClose);
            int bottom = Math.max(yOpen, yClose);
            int hBody = Math.max(1, bottom - top);
            g.setColor(col);
            g.fillRect((int) (cx - bodyW / 2), top, (int) bodyW, hBody);
            if (oldComposite != null) g.setComposite(oldComposite);
        }
    }

    private void drawOrderFlowFootprint(Graphics2D g, List<Candle> view, PriceMapper m,
                                        double cw, int x, int chartY, int chartH) {
        if (result == null || result.orderFlowContext == null || cw < 8.0) return;
        for (int i = 0; i < view.size(); i++) {
            int globalIndex = firstVisible + i;
            FootprintCandle fp = result.orderFlowContext.at(globalIndex);
            if (fp == null) continue;
            Candle c = view.get(i);
            double cx = x + i * cw + cw / 2;
            int yHigh = m.y(c.high);
            int yLow = m.y(c.low);
            int top = Math.min(yHigh, yLow);
            int height = Math.max(1, Math.abs(yLow - yHigh));
            Color deltaColor = fp.delta >= 0.0 ? BULL : BEAR;
            int alpha = Math.min(72, 16 + (int) Math.round(fp.deltaPercent * 85.0));
            int w = Math.max(2, (int) Math.round(Math.min(cw * 0.86, 20) * Math.max(0.20, fp.deltaPercent)));
            g.setColor(new Color(deltaColor.getRed(), deltaColor.getGreen(), deltaColor.getBlue(), alpha));
            if (fp.delta >= 0.0) {
                g.fillRect((int) cx, top, w, height);
            } else {
                g.fillRect((int) cx - w, top, w, height);
            }

            if (cw >= 18.0 && fp.levels.size() >= 2) {
                drawFootprintLevels(g, fp, m, cx, cw);
            }
        }
    }

    private void drawFootprintLevels(Graphics2D g, FootprintCandle fp, PriceMapper m, double cx, double cw) {
        double maxVolume = 0.0;
        for (FootprintLevel level : fp.levels) {
            maxVolume = Math.max(maxVolume, Math.max(level.buyVolume, level.sellVolume));
        }
        if (maxVolume <= 0.0) return;
        int maxBar = Math.max(2, (int) Math.min(8, cw * 0.22));
        int every = Math.max(1, fp.levels.size() / 8);
        for (int i = 0; i < fp.levels.size(); i += every) {
            FootprintLevel level = fp.levels.get(i);
            int y = m.y(level.price);
            int buyW = (int) Math.round(maxBar * level.buyVolume / maxVolume);
            int sellW = (int) Math.round(maxBar * level.sellVolume / maxVolume);
            int aBuy = level.buyImbalance() ? 185 : 110;
            int aSell = level.sellImbalance() ? 185 : 110;
            g.setColor(new Color(BEAR.getRed(), BEAR.getGreen(), BEAR.getBlue(), aSell));
            g.fillRect((int) cx - sellW - 1, y - 1, Math.max(1, sellW), 2);
            g.setColor(new Color(BULL.getRed(), BULL.getGreen(), BULL.getBlue(), aBuy));
            g.fillRect((int) cx + 1, y - 1, Math.max(1, buyW), 2);
        }
    }

    private void drawCvdOverlay(Graphics2D g, List<Candle> view, int chartX, int chartY,
                                int chartW, int chartH, double cw) {
        if (result == null || result.orderFlowContext == null || view.isEmpty()) return;
        int panelH = Math.min(54, Math.max(34, chartH / 8));
        int py = chartY + chartH - panelH - 8;
        int px = chartX + 6;
        int pw = chartW - 12;
        g.setColor(glassColor(118));
        g.fillRoundRect(px, py, pw, panelH, 8, 8);
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 160));
        g.drawRoundRect(px, py, pw, panelH, 8, 8);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < view.size(); i++) {
            FootprintCandle fp = result.orderFlowContext.at(firstVisible + i);
            if (fp == null) continue;
            min = Math.min(min, fp.cumulativeDelta);
            max = Math.max(max, fp.cumulativeDelta);
        }
        if (min == Double.MAX_VALUE || Math.abs(max - min) < 0.00000001) return;

        int prevX = -1;
        int prevY = -1;
        for (int i = 0; i < view.size(); i++) {
            FootprintCandle fp = result.orderFlowContext.at(firstVisible + i);
            if (fp == null) continue;
            int cx = (int) (chartX + i * cw + cw / 2);
            int cy = py + panelH - 8 - (int) Math.round((fp.cumulativeDelta - min) / (max - min) * (panelH - 16));
            g.setColor(fp.delta >= 0.0 ? new Color(BULL.getRed(), BULL.getGreen(), BULL.getBlue(), 185)
                    : new Color(BEAR.getRed(), BEAR.getGreen(), BEAR.getBlue(), 185));
            if (prevX >= 0) g.drawLine(prevX, prevY, cx, cy);
            prevX = cx;
            prevY = cy;
        }
        g.setFont(labelFont);
        g.setColor(CYAN);
        String source = result.orderFlowContext.tickBased ? "CVD tick" : "CVD proxy";
        g.drawString(source, px + 8, py + 14);
    }

    private void drawZones(Graphics2D g, PriceMapper m, double cw, int chartX) {
        if (result == null) return;
        zoneBuffer.clear();
        zoneBuffer.addAll(result.zones);
        zoneBuffer.sort(Comparator.comparingInt(this::zoneLayer));
        for (Zone z : zoneBuffer) {
            int createIdx = z.creationIndex - firstVisible;
            int endIdx;
            if (z.mitigated) {
                endIdx = z.mitigatedIndex - firstVisible;
            } else {
                endIdx = visibleCount + 8;
            }
            if (endIdx < 0 || createIdx >= visibleCount) continue;
            createIdx = Math.max(0, createIdx);
            endIdx = Math.min(visibleCount + 8, endIdx);

            int x1 = (int) (chartX + createIdx * cw);
            int x2 = (int) (chartX + endIdx * cw);
            int yTop = m.y(z.top);
            int yBot = m.y(z.bottom);

            boolean fvgZone = "FVG".equals(z.type) || "IFVG".equals(z.type);
            int zoneOpacity = fvgZone ? fvgOpacity : obOpacity;
            int zoneAlpha = alpha(zoneOpacity, fvgZone ? 0.58 : 0.72);
            int zoneStrokeAlpha = strokeAlpha(zoneOpacity, fvgZone ? 1.02 : 0.92, 62, fvgZone ? 230 : 215);
            Color base = zoneBaseColor(z);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), zoneAlpha);
            if (z.mitigated) {
                int mutedAlpha = Math.max(fvgZone ? 22 : 18, zoneAlpha / 2);
                fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), mutedAlpha);
            }

            int width = Math.max(2, x2 - x1);
            int height = Math.max(1, yBot - yTop);
            g.setColor(fill);
            g.fillRect(x1, yTop, width, height);

            Color stroke = z.mitigated
                    ? new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(48, (int) Math.round(zoneStrokeAlpha * 0.62)))
                    : new Color(base.getRed(), base.getGreen(), base.getBlue(), zoneStrokeAlpha);
            if (fvgZone) drawFvgHatch(g, x1, yTop, width, height, stroke, z.mitigated);
            g.setColor(stroke);
            g.setStroke(zoneStroke(fvgZone, z.entryConfirmed, z.mitigated, z.score));
            g.drawRect(x1, yTop, width, height);
            g.setStroke(new BasicStroke(1f));

            g.setFont(labelFont);
            String tag = zoneTag(z);
            if (z.sweepConfirmed) tag += " · sweep";
            if (z.entryConfirmed) tag += " · вход";
            drawZoneLabel(g, tag, x1, yTop, yBot, fvgZone, base, zoneStrokeAlpha);
        }
    }

    private void drawZoneLabel(Graphics2D g, String tag, int x, int yTop, int yBot,
                               boolean fvgZone, Color base, int zoneStrokeAlpha) {
        if (zoneLabelOpacity <= 0) return;
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(tag) + 8;
        int labelY = zoneLabelY(fvgZone, yTop, yBot);
        int bgAlpha = clamp(24 + (int) Math.round(zoneLabelOpacity * 0.78), 24, 112);
        int borderAlpha = clamp(70 + (int) Math.round(zoneLabelOpacity * 1.2), 80, 210);
        int textAlpha = clamp(135 + (int) Math.round(zoneLabelOpacity * 1.1), 135, 245);
        g.setColor(new Color(ZONE_LABEL_BG.getRed(), ZONE_LABEL_BG.getGreen(), ZONE_LABEL_BG.getBlue(), bgAlpha));
        g.fillRoundRect(x + 3, labelY, tw, 16, 5, 5);
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.max(borderAlpha, Math.min(235, zoneStrokeAlpha + 18))));
        g.drawRoundRect(x + 3, labelY, tw, 16, 5, 5);
        Color text = brighten(base, 42);
        g.setColor(new Color(text.getRed(), text.getGreen(), text.getBlue(), textAlpha));
        g.drawString(tag, x + 7, labelY + 12);
    }

    private int zoneLayer(Zone zone) {
        int layer = zone.mitigated ? 0 : 10;
        layer += ("FVG".equals(zone.type) || "IFVG".equals(zone.type)) ? 2 : 1;
        if (zone.entryConfirmed) layer += 5;
        // Высококачественные зоны рендерятся поверх слабых (выше в стеке = рисуется последней = ближе к юзеру)
        if (zone.score >= 80) layer += 4;
        else if (zone.score >= 65) layer += 2;
        return layer;
    }

    private Color zoneBaseColor(Zone zone) {
        if ("FVG".equals(zone.type) || "IFVG".equals(zone.type)) return zone.bullish ? fvgBullColor : fvgBearColor;
        return zone.bullish ? obBullColor : obBearColor;
    }

    private Stroke zoneStroke(boolean fvgZone, boolean entryConfirmed, boolean mitigated, int score) {
        // Толщина обводки отражает качество зоны: score ≥80 → A-grade, 65–79 → B, ниже → C
        float width;
        if (entryConfirmed) {
            width = score >= 80 ? 2.2f : score >= 65 ? 1.8f : 1.5f;
        } else if (mitigated) {
            width = 0.9f;
        } else {
            width = score >= 80 ? 1.6f : score >= 65 ? 1.35f : 1.1f;
        }
        if (!fvgZone) return new BasicStroke(width);
        return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{8f, 5f}, 0f);
    }

    private void drawFvgHatch(Graphics2D g, int x, int y, int width, int height, Color stroke, boolean mitigated) {
        if (width < 8 || height < 5) return;
        Shape oldClip = g.getClip();
        Stroke oldStroke = g.getStroke();
        g.setClip(x, y, width, height);
        int alpha = clamp((int) Math.round(stroke.getAlpha() * (mitigated ? 0.20 : 0.34)), 18, 82);
        g.setColor(new Color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), alpha));
        g.setStroke(new BasicStroke(1f));
        int step = 12;
        for (int dx = -height; dx < width + height; dx += step) {
            g.drawLine(x + dx, y + height, x + dx + height, y);
        }
        g.setStroke(oldStroke);
        g.setClip(oldClip);
    }

    private int zoneLabelY(boolean fvgZone, int yTop, int yBot) {
        int height = Math.max(1, yBot - yTop);
        if (fvgZone && height > 24) return yBot - 19;
        return yTop + 3;
    }

    private String zoneTag(Zone zone) {
        // Префикс тира качества: [A] для топ-зон, [B] для хороших — помогает быстро оценить зону на графике
        String tier = zone.score >= 80 ? "[A] " : zone.score >= 65 ? "[B] " : "";
        String side = zone.bullish ? "L" : "S";
        String mechanic = zone.mechanicShort();
        if (mechanic != null && !mechanic.isBlank()) return tier + zone.type + " " + side + " " + mechanic + " - " + zone.score;
        return tier + zone.type + " " + side + " · " + zone.score;
    }

    private void drawLiquidityMap(Graphics2D g, PriceMapper m, int chartX, int chartW) {
        if (result == null || result.liquidityMap == null) return;
        liquidityLabelBuffer.clear();
        drawLiquidityLevels(g, m, chartX, chartW, result.liquidityMap.buySide, liquidityLabelBuffer);
        drawLiquidityLevels(g, m, chartX, chartW, result.liquidityMap.sellSide, liquidityLabelBuffer);

        int minY = paddingTop + 10;
        int maxY = getHeight() - paddingBottom - 10;
        List<LiquidityLabel> visibleLabels = selectLiquidityLabels(liquidityLabelBuffer, minY, maxY);
        visibleLabels.sort(Comparator.comparingInt(l -> l.y));
        int last = minY - LIQUIDITY_LABEL_STEP;
        for (LiquidityLabel item : visibleLabels) {
            item.drawY = Math.max(item.y, last + LIQUIDITY_LABEL_STEP);
            if (item.drawY > maxY) item.drawY = maxY;
            last = item.drawY;
        }
        for (int i = visibleLabels.size() - 2; i >= 0; i--) {
            LiquidityLabel current = visibleLabels.get(i);
            LiquidityLabel next = visibleLabels.get(i + 1);
            if (current.drawY + LIQUIDITY_LABEL_STEP > next.drawY) {
                current.drawY = Math.max(minY, next.drawY - LIQUIDITY_LABEL_STEP);
            }
        }

        for (LiquidityLabel item : visibleLabels) {
            g.setFont(labelFont);
            FontMetrics fm = g.getFontMetrics();
            int labelX = rightLabelLaneX(chartX, chartW);
            int laneW = rightLabelLaneWidth(labelX);
            int tw = Math.min(fm.stringWidth(item.text) + 12, laneW);
            if (Math.abs(item.drawY - item.y) > 2) {
                g.setColor(new Color(item.color.getRed(), item.color.getGreen(), item.color.getBlue(), 95));
                g.drawLine(labelX - 8, item.drawY, labelX - 2, item.y);
            }
            g.setColor(glassColor(210));
            g.fillRoundRect(labelX, item.drawY - 8, tw, 16, 5, 5);
            g.setColor(new Color(item.color.getRed(), item.color.getGreen(), item.color.getBlue(), 230));
            g.drawRoundRect(labelX, item.drawY - 8, tw, 16, 5, 5);
            g.drawString(trimToWidth(g, item.text, tw - 9), labelX + 5, item.drawY + 4);
        }
    }

    private List<LiquidityLabel> selectLiquidityLabels(List<LiquidityLabel> labels, int minY, int maxY) {
        if (labels.isEmpty()) return labels;
        int maxLabels = Math.max(8, Math.min(18, Math.max(1, (maxY - minY) / LIQUIDITY_LABEL_STEP)));
        if (labels.size() <= maxLabels) return new ArrayList<>(labels);

        List<LiquidityLabel> ranked = new ArrayList<>(labels);
        ranked.sort(Comparator
                .comparingDouble((LiquidityLabel label) -> liquidityLabelPriority(label)).reversed()
                .thenComparingInt(label -> Math.abs(label.y - (paddingTop + (getHeight() - paddingTop - paddingBottom) / 2))));

        List<LiquidityLabel> selected = new ArrayList<>(ranked.subList(0, maxLabels));
        selected.sort(Comparator.comparingInt(label -> label.y));
        return selected;
    }

    private double liquidityLabelPriority(LiquidityLabel label) {
        LiquidityLevel level = label.level;
        double ref = displayCurrentPrice();
        if (!Double.isFinite(ref) || ref <= 0.0) ref = result != null ? result.currentPrice : level.price;
        double bpsAway = Double.isFinite(ref) && ref > 0.0
                ? Math.abs(level.price - ref) / ref * 10_000.0
                : 0.0;
        double scopeBonus = "external".equals(level.scope) ? 14.0 : "session".equals(level.scope) ? 7.0 : 0.0;
        double sweptPenalty = level.swept ? 36.0 : 0.0;
        return level.strength * 2.0 + scopeBonus - sweptPenalty - Math.min(90.0, bpsAway * 0.45);
    }

    private int rightLabelLaneX(int chartX, int chartW) {
        return chartX + chartW + RIGHT_LABEL_GAP;
    }

    private int rightLabelLaneWidth(int labelX) {
        return Math.max(108, getWidth() - labelX - 10);
    }

    private void drawLiquidityLevels(Graphics2D g, PriceMapper m, int chartX, int chartW,
                                     List<LiquidityLevel> levels, List<LiquidityLabel> labels) {
        if (levels == null || levels.isEmpty()) return;
        for (LiquidityLevel level : levels) {
            int y = m.y(level.price);
            if (y < paddingTop - 20 || y > getHeight() - paddingBottom + 20) continue;
            Color base = level.buySide() ? bslColor : sslColor;
            int a = Math.min(185, alpha(liquidityOpacity, 0.55) + level.strength / 2);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), a));
            float width = level.strength >= 75 ? 1.8f : 1.0f;
            float[] dash = level.swept ? new float[]{6f, 5f} : new float[]{2f, 4f};
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, dash, 0f));
            g.drawLine(chartX, y, chartX + chartW, y);
            g.setStroke(new BasicStroke(1f));

            String label = compactLiquidityLabel(level);
            labels.add(new LiquidityLabel(level, label, base, y));
        }
    }

    private String compactLiquidityLabel(LiquidityLevel level) {
        StringBuilder label = new StringBuilder(level.side)
                .append(' ')
                .append(fmt(level.price));
        if (level.strength >= 72) label.append(" H");
        else if (level.strength >= 48) label.append(" M");
        if ("external".equals(level.scope)) label.append(" ext");
        else if ("session".equals(level.scope)) label.append(" ses");
        if (level.swept) label.append(" swept");
        return label.toString();
    }

    private void drawInstitutionalMarkers(Graphics2D g, PriceMapper m, double cw, int chartX) {
        if (result == null) return;
        if (result.displacementEvents != null) {
            for (DisplacementEvent event : result.displacementEvents) {
                int local = event.index - firstVisible;
                if (local < 0 || local >= visibleCount) continue;
                Candle c = result.candles.get(event.index);
                int x = (int) (chartX + local * cw + cw / 2);
                int y = m.y(event.direction.bullish() ? c.low : c.high);
                Color color = event.direction.bullish() ? displacementBullColor : displacementBearColor;
                int markerA = alpha(markerOpacity, 0.9);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), markerA));
                int dy = event.direction.bullish() ? -18 : 18;
                g.setStroke(new BasicStroke(event.strong() ? 2.2f : 1.4f));
                g.drawLine(x, y, x, y + dy);
                int tip = y + dy;
                int[] xs = {x - 4, x + 4, x};
                int[] ys = event.direction.bullish()
                        ? new int[]{tip + 6, tip + 6, tip}
                        : new int[]{tip - 6, tip - 6, tip};
                g.fillPolygon(xs, ys, 3);
            }
            g.setStroke(new BasicStroke(1f));
        }

        if (result.sweeps != null) {
            for (LiquiditySweep sweep : result.sweeps) {
                int local = sweep.index - firstVisible;
                if (local < 0 || local >= visibleCount) continue;
                Candle c = result.candles.get(sweep.index);
                int x = (int) (chartX + local * cw + cw / 2);
                int y = m.y(sweep.reversalDirection.bullish() ? c.low : c.high);
                int markerA = alpha(markerOpacity, 0.95);
                g.setColor(new Color(sweepMarkerColor.getRed(), sweepMarkerColor.getGreen(), sweepMarkerColor.getBlue(), markerA));
                int r = sweep.score >= 70 ? 6 : 4;
                g.fillOval(x - r, y - r, r * 2, r * 2);
                g.setColor(Color.BLACK);
                g.drawOval(x - r, y - r, r * 2, r * 2);
                g.setColor(new Color(sweepMarkerColor.getRed(), sweepMarkerColor.getGreen(), sweepMarkerColor.getBlue(), markerA));
                g.setFont(labelFont);
                g.drawString("снятие", x + 7, y - 5);
            }
        }

        if (result.inducements != null) {
            for (InducementEvent event : result.inducements) {
                int local = event.index - firstVisible;
                if (local < 0 || local >= visibleCount) continue;
                int x = (int) (chartX + local * cw + cw / 2);
                int y = m.y(event.price);
                int markerA = alpha(markerOpacity, 0.9);
                g.setColor(new Color(idmMarkerColor.getRed(), idmMarkerColor.getGreen(), idmMarkerColor.getBlue(), markerA));
                int[] xs = {x, x + 5, x, x - 5};
                int[] ys = {y - 5, y, y + 5, y};
                g.fillPolygon(xs, ys, 4);
                g.setFont(labelFont);
                g.drawString("IDM", x + 7, y + 4);
            }
        }
    }

    private void drawPlan(Graphics2D g, PriceMapper m, int x, int w) {
        TradePlan p = visibleTradePlan();
        if (p == null) return;

        // Полупрозрачная заливка зон R:R — визуально показывает риск и потенциал сделки
        int yEntry = m.y(p.entry);
        int yStop  = m.y(p.stop);
        int yTp3   = m.y(p.tp3);
        // Зона риска (stop → entry): красный, очень прозрачно
        int riskTop = Math.min(yEntry, yStop);
        int riskH   = Math.max(1, Math.abs(yEntry - yStop));
        g.setColor(new Color(stopLevelColor.getRed(), stopLevelColor.getGreen(), stopLevelColor.getBlue(), 24));
        g.fillRect(x, riskTop, w, riskH);
        // Зона профита (entry → tp3): зелёный, ещё более прозрачно
        int profTop = Math.min(yEntry, yTp3);
        int profH   = Math.max(1, Math.abs(yEntry - yTp3));
        g.setColor(new Color(targetLevelColor.getRed(), targetLevelColor.getGreen(), targetLevelColor.getBlue(), 16));
        g.fillRect(x, profTop, w, profH);

        String ratingLabel = "ВХОД " + fmt(p.entry) + " [" + p.qualityRating() + "]";
        drawLevel(g, m, x, w, p.entry, entryLevelColor, ratingLabel);
        drawLevel(g, m, x, w, p.stop, stopLevelColor, "СТОП " + fmt(p.stop));
        drawLevel(g, m, x, w, p.tp1, targetLevelColor, "TP1 " + fmt(p.tp1));
        drawLevel(g, m, x, w, p.tp2, targetLevelColor, "TP2 " + fmt(p.tp2));
        drawLevel(g, m, x, w, p.tp3, targetLevelColor, "TP3 " + fmt(p.tp3));
    }

    private void drawChartInsightLevels(Graphics2D g, PriceMapper m, int x, int w, int chartY, int chartH) {
        if (!insightMatchesCurrentChart()) return;
        boolean tradePlanAlreadyVisible = shouldDrawTradePlanOverlay();
        int drawn = 0;
        for (ChartInsight.LevelMarker marker : chartInsight.levels) {
            if (marker == null || marker.type == ChartInsight.LevelType.CURRENT) continue;
            if (tradePlanAlreadyVisible && tradePlanLevel(marker.type)) continue;
            if (drawn >= 9) break;
            int y = m.y(marker.price);
            if (y < chartY + 4 || y > chartY + chartH - 4) continue;

            Color color = insightLevelColor(marker.type);
            Stroke old = g.getStroke();
            g.setStroke(marker.dashed
                    ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{8f, 6f}, 0f)
                    : new BasicStroke(1.35f));
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                    marker.type == ChartInsight.LevelType.STRUCTURE ? 86 : 132));
            g.drawLine(x, y, x + w, y);
            g.setStroke(old);

            String label = "AI " + marker.label + " " + fmt(marker.price);
            drawRightLabel(g, x, w, y + drawn % 3 * 2, color, label);
            drawn++;
        }
    }

    private void drawChartInsightBox(Graphics2D g, int x, int y, int chartW) {
        if (!insightMatchesCurrentChart() || chartW < 430) return;
        if (isOverlayHidden(OverlayCard.CHART_INSIGHT)) return;
        int rows = Math.min((chartW < 760 || !chartInsight.risks.isEmpty()) ? 1 : 2, chartInsight.metrics.size());
        int boxW = Math.min(320, Math.max(248, chartW / 4));
        int boxH = rows <= 1 ? 96 : 108;
        int bx = x + 14;
        int by = y + ((showForecast && visibleTradePlan() != null) ? 136 : 34);
        by = Math.min(by, Math.max(y + 34, getHeight() - paddingBottom - boxH - 10));
        Color accent = insightToneColor(chartInsight.tone);

        g.setColor(glassColor(164));
        g.fillRoundRect(bx, by, boxW, boxH, 9, 9);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(bx, by, boxW, boxH, 9, 9);
        g.setStroke(new BasicStroke(1f));
        g.fillRect(bx, by, 4, boxH);

        g.setFont(UIUtils.labelFont(11.2f));
        g.setColor(accent);
        g.drawString(trimToWidth(g, "CHART CHECK · " + chartInsight.htfInterval + " -> " + chartInsight.ltfInterval,
                boxW - 96), bx + 12, by + 18);

        String score = chartInsight.score + "/100";
        FontMetrics sfm = g.getFontMetrics();
        int sw = sfm.stringWidth(score) + 12;
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210));
        g.fillRoundRect(bx + boxW - sw - 34, by + 7, sw, 18, 6, 6);
        g.setColor(Color.BLACK);
        g.drawString(score, bx + boxW - sw - 28, by + 20);

        g.setFont(UIUtils.labelFont(12.2f));
        g.setColor(FG);
        g.drawString(trimToWidth(g, chartInsight.action, boxW - 24), bx + 12, by + 39);

        g.setFont(UIUtils.captionFont(11.4f));
        g.setColor(new Color(FG.getRed(), FG.getGreen(), FG.getBlue(), 220));
        g.drawString(trimToWidth(g, chartInsight.summary, boxW - 24), bx + 12, by + 59);

        int lineY = by + 76;
        for (int i = 0; i < rows; i++) {
            ChartInsight.Metric metric = chartInsight.metrics.get(i);
            g.setColor(insightToneColor(metric.tone));
            g.drawString("• " + trimToWidth(g, metric.label + ": " + metric.value, boxW - 24), bx + 12, lineY);
            lineY += 17;
        }
        if (!chartInsight.risks.isEmpty()) {
            g.setColor(YELLOW);
            g.drawString("• " + trimToWidth(g, chartInsight.risks.get(0), boxW - 24), bx + 12, by + boxH - 10);
        }
        drawOverlayEyeButton(g, OverlayCard.CHART_INSIGHT, bx + boxW - 26, by + 6, accent);
    }

    private void drawStrategyOverlay(Graphics2D g, PriceMapper m, int x, int w, int chartY, int chartH) {
        if (!strategyMatchesCurrentChart() || w < 380) return;

        Color accent = strategyToneColor(strategySignal.tone);
        if (strategySignal.suitable) {
            // Story arrows/narration only visible during the animation window.
            // Once animation ends and user may have scrolled away, anchorX loses its
            // mapping and arrows appear at wrong positions — so we hide them.
            if (strategyAnimationActive(System.currentTimeMillis())) {
                drawStrategyStory(g, m, x, w, chartY, chartH, accent);
            }
            int drawn = 0;
            for (StrategySignal.Level level : strategySignal.levels) {
                if (level == null || !Double.isFinite(level.price) || level.price <= 0.0) continue;
                if (drawn >= 9) break;
                int y = m.y(level.price);
                if (y < chartY - 10 || y > chartY + chartH + 10) continue;
                drawStrategyLevel(g, m, x, w, level, strategyLevelColor(level.type, accent), drawn);
                drawn++;
            }
        }

        if (!strategyCinematicMode && !isOverlayHidden(OverlayCard.STRATEGY)) {
            drawStrategyBox(g, x, chartY, w, accent);
        }
    }

    private void armStrategyAnimation(StrategySignal signal) {
        String key = strategyAnimationKey(signal);
        long now = System.currentTimeMillis();
        boolean stillRunning = strategyAnimationStartedAt > 0L
                && now - strategyAnimationStartedAt <= STRATEGY_STORY_MS + 650L;
        if (key.equals(strategyAnimationKey) && stillRunning) return;
        strategyAnimationKey = key;
        strategyAnimationStartedAt = signal != null && signal.suitable
                ? now
                : Long.MIN_VALUE;
    }

    private void resetStrategyAnimation() {
        strategyAnimationKey = "";
        strategyAnimationStartedAt = Long.MIN_VALUE;
    }

    private String strategyAnimationKey(StrategySignal signal) {
        if (signal == null) return "";
        StringBuilder key = new StringBuilder();
        key.append(signal.id).append('|')
                .append(signal.symbol).append('|')
                .append(signal.interval).append('|')
                .append(signal.longSide).append('|')
                .append(signal.score);
        TradePlan p = visibleTradePlan();
        if (p != null) {
            // Round to 2dp ($0.01) — micro price differences from re-analysis of the
            // same data must not restart the 7-second animation unnecessarily.
            key.append('|').append(Math.round(p.entry * 100.0))
                    .append('|').append(Math.round(p.stop  * 100.0))
                    .append('|').append(Math.round(p.tp1   * 100.0));
        }
        int count = Math.min(4, signal.levels.size());
        for (int i = 0; i < count; i++) {
            StrategySignal.Level level = signal.levels.get(i);
            if (level == null) continue;
            key.append('|').append(level.type).append(':')
                    .append(level.label).append('@')
                    .append(Math.round(level.price * 100.0)); // 2dp precision
        }
        return key.toString();
    }

    private boolean strategyAnimationActive(long nowMillis) {
        return strategyMatchesCurrentChart()
                && strategySignal.suitable
                && strategyAnimationStartedAt > 0L
                && nowMillis - strategyAnimationStartedAt <= STRATEGY_STORY_MS + 650L;
    }

    private double strategyAnimationProgress() {
        if (strategyAnimationStartedAt <= 0L) return 1.0;
        double age = (System.currentTimeMillis() - strategyAnimationStartedAt) / (double) STRATEGY_STORY_MS;
        return clampDouble(age, 0.0, 1.0);
    }

    private float stageAlpha(double progress, double start, double end) {
        if (progress <= start) return 0.0f;
        if (progress >= end) return 1.0f;
        double t = clampDouble((progress - start) / Math.max(0.0001, end - start), 0.0, 1.0);
        double eased = t * t * (3.0 - 2.0 * t);
        return (float) eased;
    }

    private void drawStrategyStory(Graphics2D g,
                                   PriceMapper m,
                                   int x,
                                   int w,
                                   int chartY,
                                   int chartH,
                                   Color accent) {
        TradePlan p = visibleTradePlan();
        if (p == null) return;
        double progress = strategyAnimationProgress();
        // anchorX tracks the sweep/CHoCH/zone candle so the whole overlay scrolls with the chart
        int anchorX = computeStrategyAnchorX(p, x, w);
        Zone focus = strategyFocusZone(p);
        drawStrategyZoneFocus(g, m, x, w, chartY, chartH, focus, accent,
                stageAlpha(progress, 0.00, 0.24));
        drawStrategySweepFocus(g, m, x, w, chartY, chartH, p,
                stageAlpha(progress, 0.18, 0.42));
        drawStrategyLiquidityTargets(g, m, x, w, chartY, chartH, p, accent, progress, anchorX);
        drawStrategyPath(g, m, x, w, chartY, chartH, p, accent, progress, anchorX);
        drawStrategyStepRibbon(g, x, w, chartY, chartH, p, accent, progress);
        drawStrategyNarration(g, x, w, chartY, chartH, p, focus, accent, progress, anchorX);
    }

    private Zone strategyFocusZone(TradePlan plan) {
        if (plan != null && plan.zone != null) return plan.zone;
        if (result != null && result.plan != null && result.plan.zone != null) return result.plan.zone;
        if (result == null || result.zones == null || result.zones.isEmpty()) return null;
        double reference = plan != null && Double.isFinite(plan.entry) ? plan.entry : result.currentPrice;
        Zone best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Zone zone : result.zones) {
            if (zone == null) continue;
            double distance = Math.abs(zone.mid() - reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }
        return best;
    }

    private void drawStrategyZoneFocus(Graphics2D g,
                                       PriceMapper m,
                                       int x,
                                       int w,
                                       int chartY,
                                       int chartH,
                                       Zone zone,
                                       Color accent,
                                       float alpha) {
        if (zone == null || alpha <= 0.0f) return;
        int rawTop = m.y(zone.top);
        int rawBottom = m.y(zone.bottom);
        int top = clamp(Math.min(rawTop, rawBottom), chartY + 4, chartY + chartH - 4);
        int bottom = clamp(Math.max(rawTop, rawBottom), chartY + 4, chartY + chartH - 4);
        int height = Math.max(5, bottom - top);
        int zx = x + 10;
        int zw = Math.max(80, w - 22);
        // Slower period than sweep focus (340ms vs 200ms) so the two glows feel independent
        double pulse = 0.68 + Math.sin(System.currentTimeMillis() / 340.0) * 0.32;

        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        float fillAlpha = (float) clampDouble(alpha * (0.15 + 0.07 * pulse), 0.0, 0.32);
        g.setComposite(AlphaComposite.SrcOver.derive(fillAlpha));
        g.setColor(accent);
        g.fillRoundRect(zx, top, zw, height, 12, 12);

        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.80, 0.0, 1.0)));
        for (int glow = 3; glow >= 1; glow--) {
            int grow = glow * 5;
            int a = clamp((int) Math.round(58 * alpha / glow), 12, 80);
            g.setStroke(new BasicStroke(1.1f + glow * 0.55f));
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), a));
            g.drawRoundRect(zx - grow, top - grow, zw + grow * 2, height + grow * 2, 14, 14);
        }
        g.setStroke(new BasicStroke(1.45f));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), clamp((int) (210 * alpha), 80, 224)));
        g.drawRoundRect(zx, top, zw, height, 12, 12);

        // Shimmer: a bright band that slides across the zone fill (2.4 s cycle)
        if (height >= 6 && alpha > 0.25f) {
            double shimmerT = (System.currentTimeMillis() % 2400L) / 2400.0;
            int bandH = Math.max(8, height / 3);
            int bandY = top + (int) Math.round((height + bandH) * shimmerT) - bandH;
            int clipTop = Math.max(top, bandY);
            int clipBot = Math.min(bottom, bandY + bandH);
            if (clipBot > clipTop) {
                java.awt.Shape oldClip = g.getClip();
                g.setClip(zx, clipTop, zw, clipBot - clipTop);
                java.awt.GradientPaint shimmerPaint = new java.awt.GradientPaint(
                        zx, clipTop, new Color(255, 255, 255, 0),
                        zx, clipTop + Math.max(1, clipBot - clipTop) / 2, new Color(255, 255, 255, (int)(38 * alpha)),
                        true);
                g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.55, 0, 1)));
                g.setPaint(shimmerPaint);
                g.fillRoundRect(zx, top, zw, height, 12, 12);
                g.setPaint(null);
                g.setClip(oldClip);
            }
        }
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);

        // Chip lives INSIDE the zone fill at its bottom edge.
        // Floor at chartY+40 so it never climbs into the step-ribbon area (ribbon at chartY+14, h=19px).
        String label = "1 POI zone · " + strategyZoneLabel(zone);
        int chipY = clamp(bottom - 22, Math.max(top + 4, chartY + 40), chartY + chartH - 26);
        drawStrategyChip(g, label, zx + 12, chipY, accent, alpha);
    }

    private void drawStrategySweepFocus(Graphics2D g,
                                        PriceMapper m,
                                        int x,
                                        int w,
                                        int chartY,
                                        int chartH,
                                        TradePlan plan,
                                        float alpha) {
        if (alpha <= 0.0f || result == null || result.sweeps == null || result.sweeps.isEmpty()) return;
        LiquiditySweep sweep = strategySweep(plan);
        if (sweep == null) return;
        int local = sweep.index - firstVisible;
        if (local < -2 || local > visibleCount + 2) return;
        double cw = w / (double) Math.max(1, visibleCount);
        int sx = (int) Math.round(x + local * cw + cw / 2.0);
        double price = sweep.level == null ? Double.NaN : sweep.level.price;
        if (!Double.isFinite(price)) {
            Candle candle = candleByIndex(sweep.index);
            if (candle == null) return;
            price = plan != null && plan.longSide ? candle.low : candle.high;
        }
        int sy = clampChartY(m.y(price), chartY, chartH);
        Color color = sweep.closeReclaimed || sweep.displacementConfirmed ? YELLOW : PURPLE;
        long now = System.currentTimeMillis();
        // Phase offset by position so sweep doesn't pulse in sync with zone glow
        double phase = (now + sx * 37L + sy * 23L) / 200.0;
        double pulse  = 0.5 + Math.sin(phase) * 0.5;
        double pulse2 = 0.5 + Math.sin(phase * 0.7 + 1.1) * 0.5; // slower second ring
        int radius  = 14 + (int) Math.round(14 * pulse);
        int radius2 = 28 + (int) Math.round(18 * pulse2);

        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();

        // Outer halo ring (large, very transparent)
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.22 * pulse2, 0.0, 0.28)));
        g.setColor(color);
        g.setStroke(new BasicStroke(1.1f));
        g.drawOval(sx - radius2, sy - radius2, radius2 * 2, radius2 * 2);

        // Inner pulsing ring
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.75, 0.0, 1.0)));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        g.drawOval(sx - radius, sy - radius, radius * 2, radius * 2);

        // Filled core
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.55, 0.0, 1.0)));
        g.fillOval(sx - 5, sy - 5, 10, 10);

        // Crosshair
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.90, 0.0, 1.0)));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(sx - 10, sy, sx + 10, sy);
        g.drawLine(sx, sy - 10, sx, sy + 10);

        g.setComposite(oldComposite);
        g.setStroke(oldStroke);

        // Chip: position above the crosshair, offset right to avoid zone chip at left edge
        String label = "2 sweep / liquidity taken";
        int chipX = clamp(sx + 18, x + 12, x + w - 200);
        int chipY = clamp(sy - 52, chartY + 8, chartY + chartH - 24);
        drawStrategyChip(g, label, chipX, chipY, color, alpha);
    }

    private LiquiditySweep strategySweep(TradePlan plan) {
        if (result == null || result.sweeps == null || result.sweeps.isEmpty()) return null;
        if (plan != null && plan.sweepIndex >= 0) {
            for (LiquiditySweep sweep : result.sweeps) {
                if (sweep != null && sweep.index == plan.sweepIndex) return sweep;
            }
        }
        LiquiditySweep best = null;
        int bestScore = -1;
        for (LiquiditySweep sweep : result.sweeps) {
            if (sweep == null) continue;
            boolean directionOk = plan == null
                    || sweep.level == null
                    || (plan.longSide ? sweep.level.sellSide() : sweep.level.buySide());
            int score = sweep.score + (directionOk ? 25 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = sweep;
            }
        }
        return best;
    }

    private Candle candleByIndex(int index) {
        if (result == null || result.candles == null || index < 0 || index >= result.candles.size()) return null;
        return result.candles.get(index);
    }

    private void drawStrategyLiquidityTargets(Graphics2D g,
                                              PriceMapper m,
                                              int x,
                                              int w,
                                              int chartY,
                                              int chartH,
                                              TradePlan p,
                                              Color accent,
                                              double progress,
                                              int anchorX) {
        float targetAlpha = stageAlpha(progress, 0.58, 0.90);
        if (targetAlpha <= 0.0f) return;
        StrategySignal.Level target = bestStrategyLevel(StrategySignal.LevelType.TARGET);
        double targetPrice = target == null || !Double.isFinite(target.price) ? p.tp3 : target.price;
        if (!Double.isFinite(targetPrice) || targetPrice <= 0.0) return;
        int targetY = clampChartY(m.y(targetPrice), chartY, chartH);
        int entryY = clampChartY(m.y(p.entry), chartY, chartH);
        // Anchor entry to sweep/zone position, target always to the right
        int entryX = clamp(anchorX, x + 80, x + w - 280);
        int targetX = Math.min(x + w - 50, Math.max(entryX + 200, x + w - 240));

        drawAnimatedArrow(g, entryX + 78, entryY, targetX, targetY, accent, targetAlpha,
                "liquidity draw");
        drawStrategyLevelGlow(g, x + 20, x + w - 18, targetY, accent, targetAlpha,
                "target liquidity / TP");

        StrategySignal.Level invalidation = bestStrategyLevel(StrategySignal.LevelType.INVALIDATION);
        if (invalidation == null) invalidation = bestStrategyLevel(StrategySignal.LevelType.STOP);
        double invalidPrice = invalidation == null || !Double.isFinite(invalidation.price) ? p.stop : invalidation.price;
        if (Double.isFinite(invalidPrice) && invalidPrice > 0.0) {
            int invalidY = clampChartY(m.y(invalidPrice), chartY, chartH);
            drawStrategyLevelGlow(g, x + 20, x + w - 18, invalidY, BEAR,
                    stageAlpha(progress, 0.68, 0.92), "invalidation");
        }
    }

    private StrategySignal.Level bestStrategyLevel(StrategySignal.LevelType type) {
        if (strategySignal == null || strategySignal.levels == null) return null;
        StrategySignal.Level best = null;
        double bestDistance = Double.MAX_VALUE;
        double reference = result == null ? Double.NaN : result.currentPrice;
        for (StrategySignal.Level level : strategySignal.levels) {
            if (level == null || level.type != type || !Double.isFinite(level.price)) continue;
            double distance = Double.isFinite(reference) ? Math.abs(level.price - reference) : 0.0;
            if (best == null || distance < bestDistance) {
                bestDistance = distance;
                best = level;
            }
        }
        return best;
    }

    private void drawStrategyLevelGlow(Graphics2D g,
                                       int x1,
                                       int x2,
                                       int y,
                                       Color color,
                                       float alpha,
                                       String label) {
        if (alpha <= 0.0f) return;
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        double dashPhase = (System.currentTimeMillis() % 900L) / 900.0 * 18.0;
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.72, 0.0, 1.0)));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{10f, 8f}, (float) dashPhase));
        g.setColor(color);
        g.drawLine(x1, y, x2, y);
        // Wider halo (8px instead of 6) for more glow depth
        g.setStroke(new BasicStroke(8.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.10, 0.0, 0.18)));
        g.drawLine(x1, y, x2, y);
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);

        // Place chip at 1/3 of line length so TP and invalidation chips don't stack at x+32
        int chipX = x1 + (x2 - x1) / 3;
        int chipY = clamp(y - 24, paddingTop + 8, getHeight() - paddingBottom - 24);
        drawStrategyChip(g, label, chipX, chipY, color, Math.max(0.35f, alpha));
    }

    private String strategyZoneLabel(Zone zone) {
        if (zone == null) return "zone";
        String mechanic = zone.mechanicShort();
        String label = (zone.type == null || zone.type.isBlank()) ? "zone" : zone.type;
        if (mechanic != null && !mechanic.isBlank()) label += " " + mechanic;
        return label + " " + fmt(zone.bottom) + "-" + fmt(zone.top);
    }

    private void drawStrategyPath(Graphics2D g,
                                  PriceMapper m,
                                  int x,
                                  int w,
                                  int chartY,
                                  int chartH,
                                  TradePlan p,
                                  Color accent,
                                  double progress,
                                  int anchorX) {
        if (!Double.isFinite(p.entry) || !Double.isFinite(p.stop)) return;
        // entryX anchored to the real sweep/zone bar so the overlay tracks chart scrolling
        int entryX = clamp(anchorX, x + 80, x + w - 280);
        int left = clamp(anchorX - 110, x + 8, entryX - 50);
        int tp1X = Math.min(x + w - 260, entryX + 150);
        int tp2X = Math.min(x + w - 230, entryX + 240);
        int stopX = Math.min(x + w - 190, entryX + 200);

        int yPrice = clampChartY(m.y(Double.isFinite(displayCurrentPrice()) ? displayCurrentPrice() : result.currentPrice), chartY, chartH);
        int yEntry = clampChartY(m.y(p.entry), chartY, chartH);
        int yStop = clampChartY(m.y(p.stop), chartY, chartH);
        int yTp1 = clampChartY(m.y(p.tp1), chartY, chartH);
        int yTp2 = clampChartY(m.y(p.tp2), chartY, chartH);

        float waitAlpha = stageAlpha(progress, 0.18, 0.38);
        float entryAlpha = stageAlpha(progress, 0.34, 0.50);
        float tpAlpha = stageAlpha(progress, 0.48, 0.75);
        float invalidAlpha = stageAlpha(progress, 0.66, 0.88);

        drawAnimatedArrow(g, entryX, yEntry, left, yPrice, currentPriceColor, waitAlpha,
                "2 wait entry");
        drawPulsingNode(g, entryX, yEntry, entryLevelColor, entryAlpha, "ENTRY");
        drawAnimatedArrow(g, entryX, yEntry, tp1X, yTp1, targetLevelColor, tpAlpha,
                p.longSide ? "3 bounce / reclaim" : "3 rejection");
        drawAnimatedArrow(g, tp1X, yTp1, tp2X, yTp2, targetLevelColor, stageAlpha(progress, 0.62, 0.86),
                "TP path");
        drawAnimatedArrow(g, entryX + 36, yEntry, stopX, yStop, stopLevelColor, invalidAlpha,
                "4 invalidation");
    }

    private void drawStrategyStepRibbon(Graphics2D g,
                                        int x,
                                        int w,
                                        int chartY,
                                        int chartH,
                                        TradePlan p,
                                        Color accent,
                                        double progress) {
        if (w < 720) return;
        int bx = x + 18;
        int by = chartY + 14;
        String[] steps = {
                "1 zone selected",
                "2 wait entry",
                p.longSide ? "3 bullish reaction" : "3 bearish reaction",
                "4 TP / SL map"
        };
        double[][] ranges = {{0.00, 0.24}, {0.18, 0.38}, {0.48, 0.75}, {0.66, 0.88}};
        int cursor = bx;
        g.setFont(UIUtils.labelFont(10.8f));
        for (int i = 0; i < steps.length; i++) {
            float alpha = stageAlpha(progress, ranges[i][0], ranges[i][1]);
            if (progress >= ranges[i][1]) alpha = 1.0f;
            FontMetrics fm = g.getFontMetrics();
            int chipW = fm.stringWidth(steps[i]) + 22;
            if (cursor + chipW > x + w - 240) break;
            Color color = i == 0 ? accent : i == 1 ? entryLevelColor : i == 2 ? targetLevelColor : stopLevelColor;
            drawStrategyChip(g, steps[i], cursor, by, color, Math.max(0.25f, alpha));
            cursor += chipW + 8;
        }
    }

    private void drawStrategyNarration(Graphics2D g,
                                       int x,
                                       int w,
                                       int chartY,
                                       int chartH,
                                       TradePlan plan,
                                       Zone zone,
                                       Color accent,
                                       double progress,
                                       int anchorX) {
        if (w < 560) return;
        // Box fades in once at the very start and stays fully visible
        float boxAlpha = Math.max(0.0f, stageAlpha(progress, 0.04, 0.20));
        if (boxAlpha <= 0.0f) return;
        int stage = strategyStage(progress);
        int boxW = Math.min(456, Math.max(335, w / 3));
        int boxH = 136;
        // Position box to the right of anchorX (the actual sweep/zone bar), clamped to stay in chart
        int preferredX = clamp(anchorX - boxW / 3, x + 18, x + Math.max(18, w - boxW - 18));
        int bx = clamp(preferredX, x + 18, x + Math.max(18, w - boxW - 18));
        int by = chartY + chartH - boxH - 88;
        by = clamp(by, chartY + 50, chartY + chartH - boxH - 56);

        // Per-stage text fade: text fades in smoothly at the START of each stage
        // boundary progress values match strategyStage() thresholds
        double[] stageStarts = {0.04, 0.24, 0.48, 0.66};
        double stageFadeEnd  = stageStarts[stage] + 0.12; // 12% of total to fade text in
        float textAlpha = stageAlpha(progress, stageStarts[stage], stageFadeEnd);

        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();

        // ── Background glass ────────────────────────────────────────
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(boxAlpha * 0.94, 0.0, 1.0)));
        g.setColor(glassColor(210));
        g.fillRoundRect(bx, by, boxW, boxH, 10, 10);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 215));
        g.setStroke(new BasicStroke(1.35f));
        g.drawRoundRect(bx, by, boxW, boxH, 10, 10);
        g.fillRect(bx, by, 4, boxH);

        // ── Stage progress bar ───────────────────────────────────────
        drawStrategyStoryProgress(g, bx + 14, by + 12, boxW - 28, stage, progress, accent);

        // ── Quality rating badge (top-right corner of box) ───────────
        TradePlan activePlan = visibleTradePlan();
        if (activePlan != null && boxAlpha > 0.2f) {
            String rating = activePlan.qualityRating();
            Color ratingColor = "A+".equals(rating) ? BULL
                    : "A".equals(rating) ? BULL
                    : "B".equals(rating) ? YELLOW : MUTED;
            drawStrategyChip(g, rating, bx + boxW - 46, by + 5, ratingColor,
                    Math.min(boxAlpha, stageAlpha(progress, 0.04, 0.22)));
        }

        // ── Text: fades in per-stage ─────────────────────────────────
        float contentAlpha = Math.max(boxAlpha * 0.35f, textAlpha * boxAlpha);
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(contentAlpha, 0.0, 1.0)));

        g.setFont(UIUtils.titleFont(12.6f));
        g.setColor(accent);
        g.drawString(trimToWidth(g, strategyStageTitle(stage, plan), boxW - 56), bx + 14, by + 45);

        g.setFont(UIUtils.captionFont(11.4f));
        g.setColor(new Color(FG.getRed(), FG.getGreen(), FG.getBlue(), 235));
        String[] lines = strategyStageLines(stage, plan, zone);
        int lineY = by + 66;
        for (String line : lines) {
            g.drawString(trimToWidth(g, line, boxW - 28), bx + 14, lineY);
            lineY += 18;
        }

        String evidence = strategyStageEvidence(stage);
        if (!evidence.isBlank()) {
            g.setColor(stage == 3 ? YELLOW : CYAN);
            g.drawString(trimToWidth(g, evidence, boxW - 28), bx + 14, by + boxH - 10);
        }
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);
    }

    private void drawStrategyStoryProgress(Graphics2D g,
                                           int x,
                                           int y,
                                           int w,
                                           int stage,
                                           double progress,
                                           Color accent) {
        int gap = 5;
        int partW = (w - gap * 3) / 4;
        double[] starts = {0.00, 0.18, 0.48, 0.66};
        double[] ends = {0.24, 0.42, 0.75, 0.92};
        for (int i = 0; i < 4; i++) {
            int px = x + i * (partW + gap);
            float fill = stageAlpha(progress, starts[i], ends[i]);
            Color color = i == 0 ? accent : i == 1 ? YELLOW : i == 2 ? BULL : BEAR;
            g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 128));
            g.fillRoundRect(px, y, partW, 7, 5, 5);
            int fw = Math.max(i < stage ? partW : 0, Math.round(partW * fill));
            if (fw > 0) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), i == stage ? 235 : 190));
                g.fillRoundRect(px, y, fw, 7, 5, 5);
            }
        }
    }

    private int strategyStage(double progress) {
        if (progress < 0.24) return 0;
        if (progress < 0.48) return 1;
        if (progress < 0.66) return 2;
        return 3;
    }

    private String strategyStageTitle(int stage, TradePlan plan) {
        return switch (stage) {
            case 0 -> "1. Основа сетапа: откуда появилась идея";
            case 1 -> "2. Подтверждение: liquidity sweep / displacement";
            case 2 -> plan != null && plan.longSide
                    ? "3. Entry: ждем реакцию покупателя"
                    : "3. Entry: ждем реакцию продавца";
            default -> "4. Цель: куда цене логично тянуться";
        };
    }

    private String[] strategyStageLines(int stage, TradePlan plan, Zone zone) {
        String zoneText = zone == null ? "POI зона" : zone.mechanicLabel() + " " + fmt(zone.bottom) + "-" + fmt(zone.top);
        String entryText = plan == null ? "entry" : fmt(plan.entry);
        String stopText = plan == null ? "stop" : fmt(plan.stop);
        String targetText = plan == null ? "TP" : fmt(plan.tp3);
        return switch (stage) {
            case 0 -> new String[]{
                    "Модель опирается на " + zoneText + ".",
                    "Задача: не догонять цену, а ждать реакцию от POI."
            };
            case 1 -> new String[]{
                    strategySweepDescription(plan),
                    "После снятия важны reclaim, displacement или CHoCH."
            };
            case 2 -> new String[]{
                    "Рабочая цена входа: " + entryText + ".",
                    "Если цена ломает " + stopText + ", идея считается неверной."
            };
            default -> new String[]{
                    "Основной магнит цены: " + strategyTargetDescription(plan, targetText) + ".",
                    "TP читаются как маршрут, SL как точка отмены сценария."
            };
        };
    }

    private String strategyStageEvidence(int stage) {
        if (strategySignal == null) return "";
        if (stage < strategySignal.confirmations.size()) {
            return "Факт: " + strategySignal.confirmations.get(stage);
        }
        if (stage == 3 && !strategySignal.action.isBlank()) {
            return "Действие: " + strategySignal.action;
        }
        return "";
    }

    private String strategySweepDescription(TradePlan plan) {
        LiquiditySweep sweep = strategySweep(plan);
        if (sweep == null || sweep.level == null) {
            return "Ищем снятие ликвидности перед входом.";
        }
        return "Снята " + sweep.level.side + " ликвидность на " + fmt(sweep.level.price)
                + ", score " + sweep.score + "/100.";
    }

    private String strategyTargetDescription(TradePlan plan, String fallbackTarget) {
        if (plan != null && plan.liquidityTargetLabel != null && !plan.liquidityTargetLabel.isBlank()
                && Double.isFinite(plan.liquidityTarget)) {
            return plan.liquidityTargetLabel + " " + fmt(plan.liquidityTarget);
        }
        StrategySignal.Level target = bestStrategyLevel(StrategySignal.LevelType.TARGET);
        if (target != null && !target.label.isBlank()) {
            return target.label + " " + fmt(target.price);
        }
        return fallbackTarget;
    }

    private int clampChartY(int y, int chartY, int chartH) {
        return clamp(y, chartY + 6, chartY + chartH - 6);
    }

    private void drawAnimatedArrow(Graphics2D g,
                                   int x1,
                                   int y1,
                                   int x2,
                                   int y2,
                                   Color color,
                                   float alpha,
                                   String label) {
        if (alpha <= 0.0f) return;
        // reveal drives the draw-on progress (0→1 as alpha 0→1, stays 1 once done)
        float reveal = Math.min(1.0f, alpha);
        int tx = x1 + Math.round((x2 - x1) * reveal);
        int ty = y1 + Math.round((y2 - y1) * reveal);
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();

        // Glow halo behind the line
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.18, 0.0, 0.28)));
        g.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        g.drawLine(x1, y1, tx, ty);
        // Line + arrowhead
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.92, 0.0, 1.0)));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        g.drawLine(x1, y1, tx, ty);
        if (reveal > 0.12f) {
            drawArrowHead(g, x1, y1, tx, ty, color);
        }

        // Label appears at the midpoint only after the line is nearly complete,
        // preventing the text from flying along with the tip during animation.
        if (reveal >= 0.88f && label != null && !label.isBlank()) {
            float labelAlpha = (float) clampDouble((reveal - 0.88) / 0.12, 0.0, 1.0);
            g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(labelAlpha * alpha, 0.0, 1.0)));
            int midX = (x1 + x2) / 2;
            int midY = (y1 + y2) / 2;
            // Offset label perpendicular to the arrow so it doesn't sit on the line
            int dx = x2 - x1, dy = y2 - y1;
            double len = Math.max(1.0, Math.hypot(dx, dy));
            int perpX = (int) Math.round(-dy / len * 12);
            int perpY = (int) Math.round( dx / len * 12);
            g.setFont(labelFont);
            g.setColor(color);
            g.drawString(label, midX + perpX + 4, midY + perpY - 2);
        }

        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    private void drawArrowHead(Graphics2D g, int x1, int y1, int x2, int y2, Color color) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int size = 8;
        int xA = x2 - (int) Math.round(Math.cos(angle - Math.PI / 6.0) * size);
        int yA = y2 - (int) Math.round(Math.sin(angle - Math.PI / 6.0) * size);
        int xB = x2 - (int) Math.round(Math.cos(angle + Math.PI / 6.0) * size);
        int yB = y2 - (int) Math.round(Math.sin(angle + Math.PI / 6.0) * size);
        g.setColor(color);
        g.fillPolygon(new int[]{x2, xA, xB}, new int[]{y2, yA, yB}, 3);
    }

    private void drawPulsingNode(Graphics2D g, int x, int y, Color color, float alpha, String label) {
        if (alpha <= 0.0f) return;
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        // Position-based phase offset: each node at a different (x,y) gets its own pulse timing
        double phase = (System.currentTimeMillis() + x * 41L + y * 17L) / 220.0;
        double pulse  = 0.5 + Math.sin(phase) * 0.5;
        double pulse2 = 0.5 + Math.sin(phase * 0.6 + 2.3) * 0.5;
        double pulse3 = 0.5 + Math.sin(phase * 0.35 + 0.8) * 0.5;
        int innerR  = 5  + (int) Math.round(8  * pulse);
        int outerR  = 14 + (int) Math.round(10 * pulse2);
        int outerR2 = 26 + (int) Math.round(9  * pulse3);
        // Outermost diffuse halo (new third ring)
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.09 * pulse3, 0.0, 0.14)));
        g.setColor(color);
        g.setStroke(new BasicStroke(1.0f));
        g.drawOval(x - outerR2, y - outerR2, outerR2 * 2, outerR2 * 2);
        // Middle soft halo
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.20 * pulse2, 0.0, 0.26)));
        g.drawOval(x - outerR, y - outerR, outerR * 2, outerR * 2);
        // Inner ring
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.50, 0.0, 1.0)));
        g.setStroke(new BasicStroke(1.8f));
        g.drawOval(x - innerR, y - innerR, innerR * 2, innerR * 2);
        // Solid filled core with small white highlight
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha, 0.0, 1.0)));
        g.fillOval(x - 5, y - 5, 11, 11);
        g.setColor(new Color(255, 255, 255, (int)(120 * alpha)));
        g.fillOval(x - 2, y - 3, 4, 3);
        // Label at a fixed position right of the node
        if (label != null && !label.isBlank()) {
            g.setColor(color);
            g.setFont(labelFont);
            g.drawString(label, x + 14, y + 4);
        }
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);
    }

    private void drawStrategyChip(Graphics2D g, String text, int x, int y, Color color, float alpha) {
        if (alpha <= 0.0f || text == null || text.isBlank()) return;
        Composite oldComposite = g.getComposite();
        g.setFont(UIUtils.labelFont(10.8f));
        FontMetrics fm = g.getFontMetrics();
        int maxTextWidth = Math.max(80, getWidth() - paddingRight - x - 18);
        String visibleText = trimToWidth(g, text, maxTextWidth);
        int w = fm.stringWidth(visibleText) + 14;
        int h = 19;
        g.setComposite(AlphaComposite.SrcOver.derive((float) clampDouble(alpha * 0.86, 0.0, 1.0)));
        g.setColor(glassColor(210));
        g.fillRoundRect(x, y, w, h, 7, 7);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
        g.drawRoundRect(x, y, w, h, 7, 7);
        g.setColor(color);
        g.drawString(visibleText, x + 7, y + 13);
        g.setComposite(oldComposite);
    }

    private void drawStrategyLevel(Graphics2D g,
                                   PriceMapper m,
                                   int x,
                                   int w,
                                   StrategySignal.Level level,
                                   Color color,
                                   int lane) {
        int y = m.y(level.price);
        Stroke oldStroke = g.getStroke();
        g.setStroke(new BasicStroke(1.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{10f, 7f}, 0f));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 128));
        g.drawLine(x + 2, y, x + w, y);
        g.setStroke(oldStroke);

        String label = "STR " + level.label + " " + fmt(level.price);
        g.setFont(priceFont);
        FontMetrics fm = g.getFontMetrics();
        int tw = Math.min(fm.stringWidth(label) + 12, 190);
        int labelX = x + 15;
        int labelY = clamp(y + ((lane % 3) - 1) * 11, paddingTop + 14, getHeight() - paddingBottom - 14);

        g.setColor(glassColor(226));
        g.fillRoundRect(labelX, labelY - 9, tw, 18, 6, 6);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 220));
        g.drawRoundRect(labelX, labelY - 9, tw, 18, 6, 6);
        g.drawString(trimToWidth(g, label, tw - 8), labelX + 6, labelY + 4);
    }

    private void drawStrategyBox(Graphics2D g, int x, int y, int chartW, Color accent) {
        int boxW = Math.min(378, Math.max(292, chartW / 3));
        int boxH = strategySignal.suitable ? 106 : 96;
        int bx = x + chartW - boxW - 14;
        int by = Math.min(y + 58, Math.max(y + 28, getHeight() - paddingBottom - boxH - 10));

        g.setColor(glassColor(186));
        g.fillRoundRect(bx, by, boxW, boxH, 9, 9);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 218));
        g.setStroke(new BasicStroke(1.25f));
        g.drawRoundRect(bx, by, boxW, boxH, 9, 9);
        g.setStroke(new BasicStroke(1f));
        g.fillRect(bx, by, 4, boxH);

        g.setFont(UIUtils.labelFont(11.2f));
        g.setColor(accent);
        String heading = strategySignal.suitable ? "СТРАТЕГИЯ ПОДХОДИТ" : "СТРАТЕГИЯ НЕ ПОДХОДИТ";
        g.drawString(heading + " · " + strategySignal.score + "/100", bx + 12, by + 18);

        g.setFont(UIUtils.titleFont(12.4f));
        g.setColor(FG);
        g.drawString(trimToWidth(g, strategySignal.name, boxW - 24), bx + 12, by + 40);

        g.setFont(UIUtils.captionFont(11.4f));
        g.setColor(strategySignal.suitable ? new Color(FG.getRed(), FG.getGreen(), FG.getBlue(), 230) : YELLOW);
        String action = strategySignal.suitable ? strategySignal.action : firstStrategyBlocker();
        g.drawString(trimToWidth(g, action, boxW - 24), bx + 12, by + 60);

        int lineY = by + 80;
        if (strategySignal.suitable) {
            int rows = Math.min(2, strategySignal.confirmations.size());
            for (int i = 0; i < rows; i++) {
                g.setColor(BULL);
                g.drawString("• " + trimToWidth(g, strategySignal.confirmations.get(i), boxW - 26), bx + 12, lineY);
                lineY += 16;
            }
        } else if (strategySignal.blockers.size() > 1) {
            g.setColor(BEAR);
            g.drawString("• " + trimToWidth(g, strategySignal.blockers.get(1), boxW - 26), bx + 12, lineY);
        } else {
            g.setColor(MUTED);
            g.drawString("• Выбери другую модель или дождись нового входа", bx + 12, lineY);
        }
        drawOverlayEyeButton(g, OverlayCard.STRATEGY, bx + boxW - 26, by + 6, accent);
    }

    private String firstStrategyBlocker() {
        if (strategySignal == null || strategySignal.blockers.isEmpty()) {
            return "Текущий сетап не дает подтверждения стратегии";
        }
        return strategySignal.blockers.get(0);
    }

    private boolean strategyMatchesCurrentChart() {
        return result != null && strategySignal != null && strategySignal.matches(result);
    }

    private Color strategyToneColor(StrategySignal.Tone tone) {
        return switch (tone) {
            case LONG -> BULL;
            case SHORT, DANGER -> BEAR;
            case WARNING -> YELLOW;
            case NEUTRAL -> CYAN;
        };
    }

    private Color strategyLevelColor(StrategySignal.LevelType type, Color accent) {
        return switch (type) {
            case ENTRY -> entryLevelColor;
            case STOP, INVALIDATION -> stopLevelColor;
            case TARGET -> targetLevelColor;
            case TRIGGER -> currentPriceColor;
            case ZONE -> accent;
        };
    }

    private boolean insightMatchesCurrentChart() {
        return result != null && chartInsight != null && chartInsight.matches(result.symbol, result.ltfInterval);
    }

    private boolean tradePlanLevel(ChartInsight.LevelType type) {
        return type == ChartInsight.LevelType.ENTRY
                || type == ChartInsight.LevelType.STOP
                || type == ChartInsight.LevelType.TARGET;
    }

    private Color insightLevelColor(ChartInsight.LevelType type) {
        return switch (type) {
            case ENTRY -> entryLevelColor;
            case STOP, ALERT -> stopLevelColor;
            case TARGET -> targetLevelColor;
            case EQUILIBRIUM -> currentPriceColor;
            case ZONE -> CYAN;
            case STRUCTURE -> MUTED;
            case CURRENT -> currentPriceColor;
        };
    }

    private Color insightToneColor(ChartInsight.Tone tone) {
        return switch (tone) {
            case BULLISH -> BULL;
            case BEARISH, DANGER -> BEAR;
            case WARNING -> YELLOW;
            case NEUTRAL -> CYAN;
        };
    }

    private boolean shouldDrawTradePlanOverlay() {
        if (result == null || visibleTradePlan() == null) return false;
        if (executionOverlayPlan != null && overlayMatchesCurrentSymbol()) return true;
        if (showForecast) return true;
        if (!accountSnapshotKnown) return true;
        return !positionsForCurrentSymbol().isEmpty();
    }

    private TradePlan visibleTradePlan() {
        if (executionOverlayPlan != null && overlayMatchesCurrentSymbol()) return executionOverlayPlan;
        if (showForecast
                && radarForecastOverlayPlan != null
                && radarForecastOverlayMatchesCurrentSymbol()) {
            return radarForecastOverlayPlan;
        }
        return result == null ? null : result.plan;
    }

    private boolean overlayMatchesCurrentSymbol() {
        return result != null
                && result.symbol != null
                && executionOverlaySymbol != null
                && !executionOverlaySymbol.isBlank()
                && executionOverlaySymbol.equalsIgnoreCase(result.symbol);
    }

    private boolean radarForecastOverlayMatchesCurrentSymbol() {
        return result != null
                && result.symbol != null
                && radarForecastOverlaySymbol != null
                && !radarForecastOverlaySymbol.isBlank()
                && radarForecastOverlaySymbol.equalsIgnoreCase(result.symbol);
    }

    private void drawAccountPositions(Graphics2D g, PriceMapper m, int x, int w) {
        List<OpenPositionSnapshot> positions = positionsForCurrentSymbol();
        if (positions.isEmpty()) return;
        int lane = 0;
        for (OpenPositionSnapshot position : positions) {
            double entry = position.entryPrice;
            if (!Double.isFinite(entry) || entry <= 0.0) continue;
            Color color = position.longSide() ? BULL : BEAR;
            boolean highlighted = isHighlighted(position);
            int yEntry = m.y(entry);

            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), highlighted ? 255 : 210));
            g.setStroke(new BasicStroke(highlighted ? 2.2f : 1.35f,
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                    highlighted ? new float[]{9f, 4f} : new float[]{4f, 4f}, 0f));
            g.drawLine(x, yEntry, x + w, yEntry);
            g.setStroke(new BasicStroke(1f));

            String label = position.sideLabel();
            drawRightLabel(g, x, w, yEntry + lane * 18, color, label);
            lane = (lane + 1) % 3;
        }
    }

    private List<OpenPositionSnapshot> positionsForCurrentSymbol() {
        positionBuffer.clear();
        if (result == null || result.symbol == null || accountPositions == null || accountPositions.isEmpty()) {
            return positionBuffer;
        }
        String symbol = result.symbol.toUpperCase();
        for (OpenPositionSnapshot position : accountPositions) {
            if (symbol.equalsIgnoreCase(position.symbol)) positionBuffer.add(position);
        }
        return positionBuffer;
    }

    private boolean isHighlighted(OpenPositionSnapshot position) {
        if (position == null || highlightedPositionSymbol == null || highlightedPositionSymbol.isBlank()) return false;
        if (!highlightedPositionSymbol.equalsIgnoreCase(position.symbol)) return false;
        return highlightedPositionEntry <= 0.0
                || Math.abs(position.entryPrice - highlightedPositionEntry) <= Math.max(0.00000001, position.entryPrice * 0.000001);
    }

    private String enrichLevelLabel(String label, double level) {
        TradePlan plan = visibleTradePlan();
        LevelProjection projection = projectLevel(plan, level);
        if (projection == null) return label;
        if (projection.reached) return label + " | " + reachedLevelText(plan, level);
        String text = projection.timeMillis <= 0L ? label : label + " | ~" + durationText(projection.timeMillis);
        if (projection.exitAlert && isProfitTarget(plan, level)) text += " | лучше закрыть";
        return text;
    }

    private LevelProjection projectLevel(TradePlan plan, double level) {
        if (result == null || plan == null || result.candles == null || result.candles.size() < 8
                || !Double.isFinite(level) || level <= 0.0
                || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0) {
            return null;
        }
        double current = result.currentPrice;
        double tolerance = Math.max(current * 0.00008, minRecentMove() * 0.20);
        if (levelReachedOrTouched(plan, level, current, tolerance)) {
            return new LevelProjection(0L, true, false);
        }
        double distance = Math.abs(level - current);

        double avgMove = Math.max(minRecentMove(), current * 0.00005);
        double directionToLevel = level > current ? 1.0 : -1.0;
        double directionalSpeed = recentDirectionalSpeed() * directionToLevel;
        double effectiveMove = avgMove * (directionalSpeed > 0.0 ? 0.75 : 0.42) + Math.max(0.0, directionalSpeed);
        int bars = (int) Math.ceil(distance / Math.max(effectiveMove, avgMove * 0.20));
        bars = clamp(bars, 1, 999);

        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(result.ltfInterval));
        Long evidenceMillis = evidenceProjectionMillis(plan, level);
        long projectedMillis = evidenceMillis == null ? bars * intervalMs : evidenceMillis;
        boolean exitAlert = shouldSuggestClose(plan, level, avgMove, directionalSpeed);
        return new LevelProjection(projectedMillis, false, exitAlert);
    }

    private Long evidenceProjectionMillis(TradePlan plan, double level) {
        String levelName = evidenceLevelName(plan, level);
        if (levelName == null || result == null) return null;
        Long futures = BacktestEvidenceStore.estimateMillis(result.symbol, BinanceClient.Market.FUTURES,
                plan.longSide, result.ltfInterval, levelName);
        if (futures != null) return futures;
        return BacktestEvidenceStore.estimateMillis(result.symbol, BinanceClient.Market.SPOT,
                plan.longSide, result.ltfInterval, levelName);
    }

    private String evidenceLevelName(TradePlan plan, double level) {
        if (plan == null) return null;
        if (samePrice(level, plan.tp1)) return "TP1";
        if (samePrice(level, plan.tp2)) return "TP2";
        if (samePrice(level, plan.tp3)) return "TP3";
        if (samePrice(level, plan.stop)) return "SL";
        return null;
    }

    private boolean shouldSuggestClose(TradePlan plan, double level, double avgMove, double directionalSpeed) {
        if (!isProfitTarget(plan, level) || result == null || result.candles == null || result.candles.isEmpty()) {
            return false;
        }
        if (!hasManagedTradeContext()) return false;
        int sourceIndex = signalSourceIndex(plan);
        int elapsedBars = Math.max(0, result.candles.size() - 1 - sourceIndex);
        double targetDistance = Math.abs(level - plan.entry);
        int expectedBars = Math.max(4, (int) Math.ceil(targetDistance / Math.max(avgMove, 0.00000001)));
        double progress = targetProgress(plan, level);
        boolean takingTooLong = elapsedBars > expectedBars * 2 && progress < 0.45;
        boolean fading = elapsedBars > expectedBars && directionalSpeed < -avgMove * 0.18;
        boolean backThroughEntry = plan.longSide
                ? result.currentPrice < plan.entry && elapsedBars > expectedBars
                : result.currentPrice > plan.entry && elapsedBars > expectedBars;
        return takingTooLong || fading || backThroughEntry;
    }

    private boolean hasManagedTradeContext() {
        return (executionOverlayPlan != null && overlayMatchesCurrentSymbol())
                || !positionsForCurrentSymbol().isEmpty();
    }

    private boolean isProfitTarget(TradePlan plan, double level) {
        if (plan == null) return false;
        return samePrice(level, plan.tp1) || samePrice(level, plan.tp2) || samePrice(level, plan.tp3);
    }

    private String reachedLevelText(TradePlan plan, double level) {
        if (isProfitTarget(plan, level)) return "взято";
        if (plan != null && samePrice(level, plan.stop)) return "стоп";
        return "сейчас";
    }

    private boolean levelReached(TradePlan plan, double level, double current, double tolerance) {
        if (plan == null || !Double.isFinite(level) || !Double.isFinite(current)) return false;
        double t = Math.max(0.0, tolerance);
        if (isProfitTarget(plan, level)) {
            return plan.longSide ? current >= level - t : current <= level + t;
        }
        if (samePrice(level, plan.stop)) {
            return plan.longSide ? current <= level + t : current >= level - t;
        }
        return Math.abs(level - current) <= t;
    }

    private boolean levelReachedOrTouched(TradePlan plan, double level, double current, double tolerance) {
        if (levelReached(plan, level, current, tolerance)) return true;
        if (!hasManagedTradeContext()) return false;
        return levelTouchedSinceSignal(plan, level, tolerance);
    }

    private boolean levelTouchedSinceSignal(TradePlan plan, double level, double tolerance) {
        if (result == null || plan == null || result.candles == null || result.candles.isEmpty()
                || !Double.isFinite(level)) {
            return false;
        }
        int total = candleCount();
        if (total <= 0) return false;
        int source = signalSourceIndex(plan);
        int from = clamp(source, 0, Math.max(0, total - 1));
        int activatedAt = entryTouchIndex(plan, from, total, tolerance);
        if (activatedAt < 0) return false;
        for (int i = activatedAt; i < total; i++) {
            Candle candle = candleAt(i);
            if (candle != null && candleTouchesLevel(plan, level, candle, tolerance)) return true;
        }
        return false;
    }

    private int entryTouchIndex(TradePlan plan, int from, int total, double tolerance) {
        if (plan == null || !Double.isFinite(plan.entry) || plan.entry <= 0.0) return -1;
        double t = Math.max(0.0, tolerance);
        for (int i = Math.max(0, from); i < total; i++) {
            Candle candle = candleAt(i);
            if (candle == null) continue;
            if (candle.low <= plan.entry + t && candle.high >= plan.entry - t) return i;
        }
        return -1;
    }

    private boolean candleTouchesLevel(TradePlan plan, double level, Candle candle, double tolerance) {
        double t = Math.max(0.0, tolerance);
        if (isProfitTarget(plan, level)) {
            return plan.longSide ? candle.high >= level - t : candle.low <= level + t;
        }
        if (samePrice(level, plan.stop)) {
            return plan.longSide ? candle.low <= level + t : candle.high >= level - t;
        }
        return candle.low <= level + t && candle.high >= level - t;
    }

    private int reachedTargetRank(TradePlan plan, double current) {
        if (plan == null || !Double.isFinite(current) || current <= 0.0) return 0;
        double tolerance = Math.max(current * 0.00008, minRecentMove() * 0.20);
        if (levelReachedOrTouched(plan, plan.tp3, current, tolerance)) return 3;
        if (levelReachedOrTouched(plan, plan.tp2, current, tolerance)) return 2;
        if (levelReachedOrTouched(plan, plan.tp1, current, tolerance)) return 1;
        return 0;
    }

    private double targetProgress(TradePlan plan, double target) {
        double total = Math.abs(target - plan.entry);
        if (total <= 0.0 || !Double.isFinite(result.currentPrice)) return 0.0;
        double moved = plan.longSide ? result.currentPrice - plan.entry : plan.entry - result.currentPrice;
        return Math.max(0.0, Math.min(1.0, moved / total));
    }

    private int signalSourceIndex(TradePlan plan) {
        int index = Math.max(plan.chochIndex, plan.sweepIndex);
        if (plan.zone != null) index = Math.max(index, plan.zone.creationIndex);
        if (index < 0 || result == null || result.candles == null || result.candles.isEmpty()) {
            return Math.max(0, candleCount() - 1);
        }
        return clamp(index, 0, Math.max(0, result.candles.size() - 1));
    }

    private double minRecentMove() {
        if (result == null || result.candles == null || result.candles.size() < 2) return 0.00000001;
        int from = Math.max(1, result.candles.size() - 48);
        double total = 0.0;
        int count = 0;
        for (int i = from; i < result.candles.size(); i++) {
            Candle prev = result.candles.get(i - 1);
            Candle current = result.candles.get(i);
            total += Math.max(Math.abs(current.close - prev.close), (current.high - current.low) * 0.35);
            count++;
        }
        return count == 0 ? 0.00000001 : Math.max(0.00000001, total / count);
    }

    private double recentDirectionalSpeed() {
        if (result == null || result.candles == null || result.candles.size() < 2) return 0.0;
        int from = Math.max(0, result.candles.size() - 36);
        Candle first = result.candles.get(from);
        Candle last = result.candles.get(result.candles.size() - 1);
        int bars = Math.max(1, result.candles.size() - 1 - from);
        return (last.close - first.close) / bars;
    }

    private boolean samePrice(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b)
                && Math.abs(a - b) <= Math.max(0.00000001, Math.max(Math.abs(a), Math.abs(b)) * 0.0000001);
    }

    private String durationText(long millis) {
        long minutes = Math.max(1L, Math.round(millis / 60_000.0));
        if (minutes < 90L) return minutes + "m";
        long hours = Math.max(1L, Math.round(minutes / 60.0));
        if (hours < 36L) return hours + "h";
        return Math.max(1L, Math.round(hours / 24.0)) + "d";
    }

    private void drawLevel(Graphics2D g, PriceMapper m, int x, int w, double price, Color color, String label) {
        label = enrichLevelLabel(label, price);
        int y = m.y(price);
        TradePlan plan = visibleTradePlan();
        float pulse = levelPulseAlpha(plan, price);
        if (pulse > 0.001f) {
            Composite oldComposite = g.getComposite();
            Stroke oldStroke = g.getStroke();
            g.setComposite(AlphaComposite.SrcOver.derive(Math.min(0.62f, pulse * 0.62f)));
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
            g.setStroke(new BasicStroke(2.0f + 4.0f * pulse,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x, y, x + w, y);
            g.setComposite(oldComposite);
            g.setStroke(oldStroke);
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{5f, 4f}, 0f));
        g.drawLine(x, y, x + w, y);
        g.setStroke(new BasicStroke(1f));

        FontMetrics fm = g.getFontMetrics(priceFont);
        label = trimToWidth(g, label, Math.max(112, getWidth() - (x + w + 12)));
        int tw = fm.stringWidth(label) + 8;
        int labelX = Math.min(x + w + 4, getWidth() - tw - 4);
        g.setColor(color);
        g.fillRect(labelX, y - 7, tw, 14);
        g.setColor(Color.WHITE);
        g.setFont(priceFont);
        g.drawString(label, labelX + 4, y + 4);
    }

    private void drawRightLabel(Graphics2D g, int x, int w, int y, Color color, String label) {
        g.setFont(priceFont);
        FontMetrics fm = g.getFontMetrics();
        int labelX = rightLabelLaneX(x, w);
        int tw = Math.min(fm.stringWidth(label) + 10, rightLabelLaneWidth(labelX));
        int yy = clamp(y, paddingTop + 10, getHeight() - paddingBottom - 10);
        g.setColor(glassColor(220));
        g.fillRoundRect(labelX, yy - 8, tw, 16, 5, 5);
        g.setColor(color);
        g.drawRoundRect(labelX, yy - 8, tw, 16, 5, 5);
        g.drawString(trimToWidth(g, label, tw - 8), labelX + 5, yy + 4);
    }

    private void drawForecast(Graphics2D g, PriceMapper m, int x, int w) {
        TradePlan p = visibleTradePlan();
        if (p == null) return;
        int rightX = x + w - 10;
        int leftX  = x + 20;
        int yPrice = m.y(result.currentPrice);
        int yEntry = m.y(p.entry);
        int yStop  = m.y(p.stop);
        int yTp1   = m.y(p.tp1);
        int yTp2   = m.y(p.tp2);
        int yTp3   = m.y(p.tp3);

        int top = Math.min(yEntry, yStop);
        int bot = Math.max(yEntry, yStop);
        g.setPaint(new GradientPaint(leftX, top,
                new Color(stopLevelColor.getRed(), stopLevelColor.getGreen(), stopLevelColor.getBlue(), 80),
                rightX, top,
                new Color(stopLevelColor.getRed(), stopLevelColor.getGreen(), stopLevelColor.getBlue(), 15)));
        g.fillRect(leftX, top, rightX - leftX, Math.max(1, bot - top));

        int topP = Math.min(yEntry, yTp3);
        int botP = Math.max(yEntry, yTp3);
        g.setPaint(new GradientPaint(leftX, topP,
                new Color(targetLevelColor.getRed(), targetLevelColor.getGreen(), targetLevelColor.getBlue(), 90),
                rightX, topP,
                new Color(targetLevelColor.getRed(), targetLevelColor.getGreen(), targetLevelColor.getBlue(), 15)));
        g.fillRect(leftX, topP, rightX - leftX, Math.max(1, botP - topP));

        drawLevel(g, m, x, w, p.entry, entryLevelColor, "ВХОД " + fmt(p.entry));
        drawLevel(g, m, x, w, p.stop, stopLevelColor, "СТОП " + fmt(p.stop));
        drawLevel(g, m, x, w, p.tp1, targetLevelColor, "TP1 " + fmt(p.tp1));
        drawLevel(g, m, x, w, p.tp2, targetLevelColor, "TP2 " + fmt(p.tp2));
        drawLevel(g, m, x, w, p.tp3, targetLevelColor, "TP3 " + fmt(p.tp3));

        drawArrow(g, leftX + 60, yPrice, leftX + 60, yEntry, currentPriceColor, "цена → вход");

        int x1 = leftX + 130;
        drawArrow(g, x1, yEntry, x1, yTp1, targetLevelColor, "TP1");
        drawArrow(g, x1 + 60, yEntry, x1 + 60, yTp2, targetLevelColor, "TP2");
        drawArrow(g, x1 + 120, yEntry, x1 + 120, yTp3, targetLevelColor, "TP3");
        drawArrow(g, x1 + 200, yEntry, x1 + 200, yStop, stopLevelColor, "STOP");

        if (!isOverlayHidden(OverlayCard.FORECAST_SUMMARY)) {
            drawForecastSummary(g, x, w, p);
        }
        if (!isOverlayHidden(OverlayCard.PLAN_HEALTH)) {
            drawPlanHealth(g, x, w, p);
        }
    }

    private void drawArrow(Graphics2D g, int x1, int y1, int x2, int y2, Color color, String label) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(x1, y1, x2, y2);

        int dy = y2 > y1 ? -8 : 8;
        int[] xs = {x2 - 5, x2 + 5, x2};
        int[] ys = {y2 + dy, y2 + dy, y2};
        g.fillPolygon(xs, ys, 3);

        g.setFont(labelFont);
        g.drawString(label, x2 + 8, y2 + 4);
        g.setStroke(new BasicStroke(1f));
    }

    private static final int FORECAST_PANEL_BOX_W = 256;
    private static final int FORECAST_PANEL_BOX_H = 216;

    private float radarForecastFadeAlpha() {
        if (radarForecastShownAt == Long.MIN_VALUE) return 1.0f;
        long age = Math.max(0L, System.currentTimeMillis() - radarForecastShownAt);
        long fadeDuration = 900L;
        if (age >= fadeDuration) return 1.0f;
        double t = age / (double) fadeDuration;
        return (float) (t * t * (3.0 - 2.0 * t)); // smoothstep 0→1
    }

    private void drawForecastSummary(Graphics2D g, int x, int chartW, TradePlan p) {
        float fadeAlpha = radarForecastFadeAlpha();
        if (fadeAlpha < 0.01f) return;

        double risk = Math.abs(p.entry - p.stop);
        double rr   = risk > 0 ? Math.abs(p.tp3 - p.entry) / risk : 0;
        double profitPct = p.entry > 0 ? Math.abs(p.tp3 - p.entry) / p.entry * 100 : 0;
        double riskPct   = p.entry > 0 ? risk / p.entry * 100 : 0;
        int smcScore = (result != null) ? result.smcScore : 0;
        Color dirColor = p.longSide ? BULL : BEAR;
        String dirLabel = p.longSide ? "▲ ЛОНГ" : "▼ ШОРТ";

        // Zone factors (safe null checks)
        int dispScore  = (p.zone != null) ? p.zone.displacementScore : 0;
        int volScore   = (p.zone != null) ? p.zone.volumeScore : 0;
        int zoneScore  = (p.zone != null) ? p.zone.score : 0;
        boolean hqm    = p.zone != null && p.zone.highQualityModel;
        boolean l2l    = p.zone != null && p.zone.liquidityToLiquidity;
        boolean hasSweep = p.sweepIndex >= 0;
        boolean hasCHoCH = p.chochIndex >= 0;

        int boxW = FORECAST_PANEL_BOX_W;
        int boxH = FORECAST_PANEL_BOX_H;
        // Default: right side, below strategy box (~164px below paddingTop)
        int defaultBx = x + chartW - boxW - 14;
        int defaultBy = paddingTop + 164;
        Point stored = panelPositions.get(OverlayCard.FORECAST_SUMMARY);
        int bx = (stored != null) ? stored.x : defaultBx;
        int by = (stored != null) ? stored.y : defaultBy;

        Composite oldComp  = g.getComposite();
        Stroke    oldStroke = g.getStroke();
        g.setComposite(AlphaComposite.SrcOver.derive(fadeAlpha));

        // Background + border
        g.setColor(glassColor(218));
        g.fillRoundRect(bx, by, boxW, boxH, 10, 10);
        g.setColor(new Color(dirColor.getRed(), dirColor.getGreen(), dirColor.getBlue(), 185));
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(bx, by, boxW, boxH, 10, 10);
        // Left accent stripe
        g.fillRect(bx, by + 1, 3, boxH - 2);

        int cy = by + 20;

        // ── HEADER: title + direction badge ──────────────────────────
        g.setFont(UIUtils.labelFont(10.8f));
        g.setColor(MUTED);
        g.drawString("ПРОГНОЗ РАДАРА", bx + 11, cy);
        g.setFont(UIUtils.labelFont(11.8f));
        g.setColor(dirColor);
        FontMetrics fmDir = g.getFontMetrics();
        g.drawString(dirLabel, bx + boxW - fmDir.stringWidth(dirLabel) - 12, cy);

        // ── SMC SCORE GAUGE ──────────────────────────────────────────
        cy += 10;
        int gaugeW = boxW - 22;
        Color scoreColor = smcScore >= 75 ? BULL : smcScore >= 50 ? YELLOW : BEAR;
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 180));
        g.fillRoundRect(bx + 11, cy, gaugeW, 6, 3, 3);
        g.setColor(scoreColor);
        g.fillRoundRect(bx + 11, cy, Math.max(4, gaugeW * smcScore / 100), 6, 3, 3);
        g.setFont(UIUtils.captionFont(10.4f));
        g.setColor(scoreColor);
        g.drawString("SMC " + smcScore + "/100", bx + 11, cy + 17);
        cy += 26;

        // ── SEPARATOR ────────────────────────────────────────────────
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 140));
        g.drawLine(bx + 8, cy, bx + boxW - 8, cy);
        cy += 10;

        // ── R:R VISUAL BAR ──────────────────────────────────────────
        int rrBarW = boxW - 22;
        double totalParts = rr + 1.0;
        int greenW = (totalParts > 0) ? Math.max(4, (int) (rrBarW * rr / totalParts)) : rrBarW / 2;
        int redW   = rrBarW - greenW;
        g.setColor(new Color(BULL.getRed(), BULL.getGreen(), BULL.getBlue(), 155));
        g.fillRoundRect(bx + 11, cy, greenW, 7, 2, 2);
        g.setColor(new Color(BEAR.getRed(), BEAR.getGreen(), BEAR.getBlue(), 155));
        g.fillRoundRect(bx + 11 + greenW, cy, redW, 7, 2, 2);
        // Labels under bar
        g.setFont(UIUtils.labelFont(10.4f));
        g.setColor(BULL);
        g.drawString(String.format("+%.2f%%", profitPct), bx + 11, cy + 18);
        String rrStr = String.format("R:R 1:%.1f", rr);
        FontMetrics fmRR = g.getFontMetrics();
        g.setColor(FG);
        g.drawString(rrStr, bx + (boxW - fmRR.stringWidth(rrStr)) / 2, cy + 18);
        g.setColor(BEAR);
        String riskStr = String.format("-%.2f%%", riskPct);
        FontMetrics fmRisk = g.getFontMetrics();
        g.drawString(riskStr, bx + boxW - fmRisk.stringWidth(riskStr) - 12, cy + 18);
        cy += 28;

        // ── SEPARATOR ────────────────────────────────────────────────
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 140));
        g.drawLine(bx + 8, cy, bx + boxW - 8, cy);
        cy += 11;

        // ── FACTOR CHECKLIST ─────────────────────────────────────────
        g.setFont(UIUtils.captionFont(10.8f));
        int rowH = 17;
        drawFactorRow(g, bx, cy, boxW, "displacement", dispScore + "/100", dispScore >= 55);
        cy += rowH;
        drawFactorRow(g, bx, cy, boxW, "volume",       volScore + "/100",  volScore >= 35);
        cy += rowH;
        drawFactorRow(g, bx, cy, boxW, "zone quality", zoneScore + "/100", zoneScore >= 65);
        cy += rowH;
        drawFactorRow(g, bx, cy, boxW, "liq sweep", hasSweep ? "confirmed" : "—", hasSweep);
        cy += rowH;
        drawFactorRow(g, bx, cy, boxW, "CHoCH / BOS", hasCHoCH ? "confirmed" : "—", hasCHoCH);
        cy += rowH;

        // ── FOOTER: model badges ──────────────────────────────────────
        cy += 3;
        g.setFont(UIUtils.captionFont(10.4f));
        g.setColor(MUTED);
        StringBuilder badges = new StringBuilder();
        if (hqm) badges.append("A+model ");
        if (l2l) badges.append("L2L ");
        if (result != null && result.sessionContext != null && result.sessionContext.inKillzone)
            badges.append("killzone");
        String badgeStr = badges.toString().trim();
        if (!badgeStr.isEmpty())
            g.drawString(trimToWidth(g, badgeStr, boxW - 22), bx + 11, cy);

        // Drag-handle dots (top-left corner, 3 pairs of dots)
        g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 120));
        for (int di = 0; di < 3; di++) {
            g.fillOval(bx + 5, by + 7 + di * 5, 2, 2);
            g.fillOval(bx + 9, by + 7 + di * 5, 2, 2);
        }

        g.setComposite(oldComp);
        g.setStroke(oldStroke);
        drawOverlayEyeButton(g, OverlayCard.FORECAST_SUMMARY, bx + boxW - 26, by + 6, dirColor);
        // Register full bounds for drag hit-testing
        panelDragBounds.put(OverlayCard.FORECAST_SUMMARY, new Rectangle(bx, by, boxW, boxH));
    }

    /** Renders one factor row: bullet dot + label (left) + value (right). */
    private void drawFactorRow(Graphics2D g, int bx, int cy, int boxW, String label, String value, boolean ok) {
        Color dotColor = ok ? BULL : new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 180);
        g.setColor(dotColor);
        g.fillOval(bx + 11, cy - 8, 6, 6);
        g.setColor(ok ? FG : MUTED);
        g.drawString(label, bx + 22, cy);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(ok ? dotColor : MUTED);
        g.drawString(value, bx + boxW - fm.stringWidth(value) - 12, cy);
    }

    private void drawPlanHealth(Graphics2D g, int x, int chartW, TradePlan p) {
        float fadeAlpha = radarForecastFadeAlpha();
        if (fadeAlpha < 0.01f) return;

        PlanHealth health = assessPlanHealth(p);
        int boxW = FORECAST_PANEL_BOX_W;
        int boxH = 96;
        // Default: stacked directly below the forecast summary panel
        int defaultBx = x + chartW - boxW - 14;
        int defaultBy = paddingTop + 164 + FORECAST_PANEL_BOX_H + 6;
        Point stored = panelPositions.get(OverlayCard.PLAN_HEALTH);
        int bx = (stored != null) ? stored.x : defaultBx;
        int by = (stored != null) ? stored.y : defaultBy;

        Composite oldComp  = g.getComposite();
        Stroke    oldStroke = g.getStroke();
        g.setComposite(AlphaComposite.SrcOver.derive(fadeAlpha));

        // Background + border
        g.setColor(glassColor(210));
        g.fillRoundRect(bx, by, boxW, boxH, 10, 10);
        g.setColor(new Color(health.color.getRed(), health.color.getGreen(), health.color.getBlue(), 180));
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(bx, by, boxW, boxH, 10, 10);
        g.fillRect(bx, by + 1, 3, boxH - 2);

        // Status badge
        g.setFont(UIUtils.labelFont(11.8f));
        g.setColor(health.color);
        g.drawString("ПЛАН: " + health.status, bx + 11, by + 20);

        // Action + R current
        g.setFont(UIUtils.captionFont(10.8f));
        g.setColor(FG);
        g.drawString(trimToWidth(g, health.action, boxW - 22), bx + 11, by + 39);

        g.setFont(UIUtils.captionFont(10.4f));
        g.setColor(MUTED);
        g.drawString(trimToWidth(g, "age " + health.ageText + "  ·  R " + health.rText, boxW - 22), bx + 11, by + 56);
        g.drawString(trimToWidth(g, "вход " + fmt(p.entry) + "  ·  стоп " + fmt(p.stop), boxW - 22), bx + 11, by + 72);
        g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 160));
        g.drawString(trimToWidth(g, health.note, boxW - 22), bx + 11, by + 88);

        // Drag-handle dots
        g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 120));
        for (int di = 0; di < 3; di++) {
            g.fillOval(bx + 5, by + 7 + di * 5, 2, 2);
            g.fillOval(bx + 9, by + 7 + di * 5, 2, 2);
        }

        g.setComposite(oldComp);
        g.setStroke(oldStroke);
        drawOverlayEyeButton(g, OverlayCard.PLAN_HEALTH, bx + boxW - 26, by + 6, health.color);
        panelDragBounds.put(OverlayCard.PLAN_HEALTH, new Rectangle(bx, by, boxW, boxH));
    }

    private PlanHealth assessPlanHealth(TradePlan p) {
        if (result == null || p == null || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0) {
            return new PlanHealth("WAIT", "no market data", "-", "-", MUTED, "forecast is waiting for fresh candles");
        }
        double risk = Math.abs(p.entry - p.stop);
        double currentR = 0.0;
        if (risk > 0.0) {
            currentR = (p.longSide ? result.currentPrice - p.entry : p.entry - result.currentPrice) / risk;
        }
        String rText = risk <= 0.0 ? "?" : String.format(java.util.Locale.US, "%.2f", currentR);
        String ageText = planAgeText(p);
        boolean stopped = p.longSide ? result.currentPrice <= p.stop : result.currentPrice >= p.stop;
        if (stopped) {
            return new PlanHealth("STOP HIT", "exit / no entry", ageText, rText, BEAR,
                    "setup invalidated by stop level");
        }

        boolean managed = hasManagedTradeContext();
        int reachedTarget = reachedTargetRank(p, result.currentPrice);
        if (reachedTarget > 0) {
            String target = "TP" + reachedTarget;
            String action = managed
                    ? (reachedTarget >= 3 ? "close rest" : "take partial / trail")
                    : "skip late entry";
            String note = managed
                    ? target + " already touched by live price"
                    : target + " already touched; wait for a fresh setup";
            return new PlanHealth(target + " HIT", action, ageText, rText, managed ? BULL : YELLOW, note);
        }

        double avgMove = Math.max(minRecentMove(), result.currentPrice * 0.00005);
        double directionalSpeed = recentDirectionalSpeed() * (p.longSide ? 1.0 : -1.0);
        boolean staleTp = managed && (shouldSuggestClose(p, p.tp1, avgMove, directionalSpeed)
                || shouldSuggestClose(p, p.tp2, avgMove, directionalSpeed)
                || shouldSuggestClose(p, p.tp3, avgMove, directionalSpeed));
        if (staleTp) {
            return new PlanHealth("LATE", "reduce / close", ageText, rText, BEAR,
                    "target timing faded against the plan");
        }
        if (managed && currentR >= 1.0) {
            return new PlanHealth("MANAGE", "take partial", ageText, rText, BULL,
                    "price has paid for the initial risk");
        }
        boolean missedEntry = !managed && (p.longSide ? result.currentPrice > p.entry + risk * 0.35
                : result.currentPrice < p.entry - risk * 0.35);
        if (missedEntry) {
            return new PlanHealth("CHECK ENTRY", "avoid chasing", ageText, rText, YELLOW,
                    "radar idea moved away from entry");
        }
        String action = managed ? "hold / trail calmly" : "wait for entry";
        return new PlanHealth("VALID", action, ageText, rText, BULL,
                managed ? "managed trade follows radar plan" : "radar forecast is still coherent");
    }

    private String planAgeText(TradePlan plan) {
        if (result == null || result.candles == null || result.candles.isEmpty() || plan == null) return "-";
        int elapsedBars = Math.max(0, result.candles.size() - 1 - signalSourceIndex(plan));
        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(result.ltfInterval));
        return elapsedBars + " bars / " + durationText(elapsedBars * intervalMs);
    }

    private void drawCurrentPriceLine(Graphics2D g, PriceMapper m, int x, int w) {
        double displayPrice = displayCurrentPrice();
        if (!Double.isFinite(displayPrice) || displayPrice <= 0.0) displayPrice = result.currentPrice;
        int y = m.y(displayPrice);
        g.setColor(currentPriceColor);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 3f}, 0f));
        g.drawLine(x, y, x + w, y);
        g.setStroke(new BasicStroke(1f));

        String lbl = fmt(displayPrice);
        FontMetrics fm = g.getFontMetrics(priceFont);
        int tw = fm.stringWidth(lbl) + 10;
        g.setColor(currentPriceColor);
        g.fillRect(x + w + 2, y - 8, tw, 16);
        g.setColor(Color.BLACK);
        g.setFont(priceFont);
        g.drawString(lbl, x + w + 6, y + 4);
        int countdownWidth = drawCandleCloseCountdown(g, x, w, y, tw);
        drawCurrentPricePnl(g, x, w, y, tw + countdownWidth);
    }

    private int drawCandleCloseCountdown(Graphics2D g, int x, int w, int y, int priceLabelWidth) {
        String countdown = candleCloseCountdownText();
        if (countdown.isBlank()) return 0;
        g.setFont(priceFont);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(countdown) + 12;
        int labelX = x + w + 8 + priceLabelWidth;
        labelX = Math.min(labelX, getWidth() - tw - 4);
        int yy = clamp(y, paddingTop + 10, getHeight() - paddingBottom - 10);
        g.setColor(glassColor(228));
        g.fillRect(labelX, yy - 8, tw, 16);
        g.setColor(new Color(currentPriceColor.getRed(), currentPriceColor.getGreen(), currentPriceColor.getBlue(), 190));
        g.drawRect(labelX, yy - 8, tw, 16);
        g.setColor(FG);
        g.drawString(countdown, labelX + 6, yy + 4);
        return tw + 4;
    }

    private String candleCloseCountdownText() {
        if (result == null || result.candles == null || result.candles.isEmpty()) return "";
        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(result.ltfInterval));
        Candle last = result.candles.get(result.candles.size() - 1);
        long openTime = last.time;
        if (liveCandleOverride != null && liveCandleIndex >= result.candles.size() - 1) {
            openTime = Math.max(openTime, liveCandleOverride.time);
        }
        long now = System.currentTimeMillis();
        long closeTime = openTime + intervalMs;
        if (closeTime <= now) {
            long steps = Math.max(1L, (now - openTime) / intervalMs + 1L);
            closeTime = openTime + steps * intervalMs;
        }
        long remaining = Math.max(0L, closeTime - now);
        return compactCountdown(remaining);
    }

    private String compactCountdown(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + String.format(java.util.Locale.US, "%02dm", minutes);
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void drawCurrentPricePnl(Graphics2D g, int x, int w, int y, int priceLabelWidth) {
        List<OpenPositionSnapshot> positions = positionsForCurrentSymbol();
        if (positions.isEmpty() || !Double.isFinite(result.currentPrice) || result.currentPrice <= 0.0) return;
        double pnl = 0.0;
        for (OpenPositionSnapshot position : positions) {
            pnl += position.unrealizedPnl;
        }
        String label = "PnL " + signed(pnl);
        Color color = pnl >= 0.0 ? BULL : BEAR;
        FontMetrics fm = g.getFontMetrics(priceFont);
        int tw = fm.stringWidth(label) + 10;
        int labelX = Math.min(x + w + 6 + priceLabelWidth, getWidth() - tw - 4);
        g.setColor(color);
        g.fillRect(labelX, y - 8, tw, 16);
        g.setColor(Color.BLACK);
        g.drawString(label, labelX + 5, y + 4);
    }

    private void drawCrosshair(Graphics2D g, List<Candle> view, PriceMapper m, double cw, int chartX, int chartY, int chartW, int chartH) {
        if (mouse == null) return;
        if (mouse.x < chartX || mouse.x > chartX + chartW || mouse.y < chartY || mouse.y > chartY + chartH) return;

        g.setColor(new Color(200, 200, 200, 90));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 4f}, 0f));
        g.drawLine(mouse.x, chartY, mouse.x, chartY + chartH);
        g.drawLine(chartX, mouse.y, chartX + chartW, mouse.y);
        g.setStroke(new BasicStroke(1f));

        int idx = (int) ((mouse.x - chartX) / cw);
        if (idx >= 0 && idx < view.size()) {
            Candle c = view.get(idx);
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.time), ZoneId.systemDefault());
            String text = String.format("O %.4f  H %.4f  L %.4f  C %.4f  · %s", c.open, c.high, c.low, c.close, t.format(TIME_AXIS_DATE_TIME));
            g.setFont(textFont);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            g.setColor(new Color(0, 0, 0, 180));
            g.fillRect(chartX + 10, chartY + 6, tw + 14, 22);
            g.setColor(c.bullish() ? bullCandleColor : c.bearish() ? bearCandleColor : neutralCandleColor);
            g.drawString(text, chartX + 17, chartY + 22);
        }

        double price = m.priceAt(mouse.y);
        String pl = fmt(price);
        g.setFont(priceFont);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(pl) + 10;
        g.setColor(CYAN);
        g.fillRect(chartX + chartW + 2, mouse.y - 8, tw, 16);
        g.setColor(Color.BLACK);
        g.drawString(pl, chartX + chartW + 6, mouse.y + 4);
    }

    private void drawTitleBar(Graphics2D g, int x, int y) {
        String text = result.symbol + " · " + result.ltfInterval + "   "
                + (result.htfBullish ? "HTF лонг" : "HTF шорт")
                + "   SMC " + result.smcScore + "/100";
        if (result.sessionContext != null) text += "   " + sessionRu(result.sessionContext.activeSession);
        if (priceBarRatioLocked) text += "   цена/бар LOCK";
        g.setFont(titleFont);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 16;
        g.setColor(glassColor(150));
        g.fillRoundRect(x + 4, y - 18, tw, 18, 8, 8);
        g.setColor(result.htfBullish ? BULL : BEAR);
        g.drawString(text, x + 12, y - 4);
    }

    private void drawLegendBox(Graphics2D g, int x, int y, int chartW) {
        String[] items = { "OB L", "OB S", "FVG L", "FVG S", "IFVG", "OTE", "DEV", "Вход", "Цена" };
        Color[] colors = { obBullColor, obBearColor, fvgBullColor, fvgBearColor, fvgBullColor,
                oteZoneColor, displacementBullColor, entryLevelColor, currentPriceColor };
        boolean[] hatch = { false, false, true, true, true, false, false, false, false };
        g.setFont(labelFont);
        FontMetrics fm = g.getFontMetrics();
        int total = 0;
        for (String it : items) total += fm.stringWidth(it) + 26;
        int bx = Math.max(x + 8, x + chartW - total - 10);
        int by = y + 6;

        g.setColor(new Color(0, 0, 0, 130));
        g.fillRoundRect(bx - 8, by - 14, total + 16, 22, 8, 8);

        int cx = bx;
        for (int i = 0; i < items.length; i++) {
            g.setColor(colors[i]);
            g.fillRect(cx, by - 8, 10, 10);
            if (hatch[i]) {
                g.setColor(glassColor(150));
                g.drawLine(cx, by + 2, cx + 10, by - 8);
            }
            g.setColor(FG);
            g.drawString(items[i], cx + 14, by);
            cx += fm.stringWidth(items[i]) + 26;
        }
    }

    private boolean isOverlayHidden(OverlayCard card) {
        return hiddenOverlayCards.contains(card);
    }

    private boolean handleOverlayToggleClick(Point point) {
        if (point == null) return false;
        for (Map.Entry<OverlayCard, Rectangle> entry : overlayToggleBounds.entrySet()) {
            if (entry.getValue().contains(point)) {
                hiddenOverlayCards.add(entry.getKey());
                setCursor(Cursor.getDefaultCursor());
                repaint();
                return true;
            }
        }
        for (Map.Entry<OverlayCard, Rectangle> entry : overlayRestoreBounds.entrySet()) {
            if (entry.getValue().contains(point)) {
                hiddenOverlayCards.remove(entry.getKey());
                setCursor(Cursor.getDefaultCursor());
                repaint();
                return true;
            }
        }
        return false;
    }

    private void updateOverlayCursor(Point point) {
        if (point == null) { setCursor(Cursor.getDefaultCursor()); return; }
        if (isOverlayControlAt(point)) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        // Show MOVE cursor when hovering over any draggable panel (but not its eye button)
        for (Map.Entry<OverlayCard, Rectangle> entry : panelDragBounds.entrySet()) {
            if (entry.getValue().contains(point)) {
                Rectangle eye = overlayToggleBounds.get(entry.getKey());
                if (eye == null || !eye.contains(point)) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
            }
        }
        setCursor(Cursor.getDefaultCursor());
    }

    private boolean isOverlayControlAt(Point point) {
        if (point == null) return false;
        for (Rectangle rect : overlayToggleBounds.values()) {
            if (rect.contains(point)) return true;
        }
        for (Rectangle rect : overlayRestoreBounds.values()) {
            if (rect.contains(point)) return true;
        }
        return false;
    }

    private boolean overlayCardAvailable(OverlayCard card) {
        return switch (card) {
            case FORECAST_SUMMARY, PLAN_HEALTH ->
                    shouldDrawTradePlanOverlay() && showForecast && visibleTradePlan() != null;
            case CHART_INSIGHT -> insightMatchesCurrentChart();
            case STRATEGY -> strategyMatchesCurrentChart();
        };
    }

    private void drawOverlayEyeButton(Graphics2D g, OverlayCard card, int x, int y, Color accent) {
        Rectangle rect = new Rectangle(x, y, OVERLAY_EYE_SIZE, OVERLAY_EYE_SIZE);
        overlayToggleBounds.put(card, rect);
        g.setColor(glassColor(230));
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 7, 7);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 215));
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 7, 7);
        drawEyeGlyph(g, rect, accent, true);
    }

    private void drawOverlayRestoreBar(Graphics2D g, int chartX, int chartY, int chartW) {
        int x = chartX + 10;
        int y = chartY + 10;
        g.setFont(labelFont);
        FontMetrics fm = g.getFontMetrics();
        for (OverlayCard card : OverlayCard.values()) {
            if (!hiddenOverlayCards.contains(card) || !overlayCardAvailable(card)) continue;
            int chipW = fm.stringWidth(card.label) + 36;
            if (x + chipW > chartX + chartW - 8) break;
            Rectangle rect = new Rectangle(x, y, chipW, 22);
            overlayRestoreBounds.put(card, rect);
            g.setColor(glassColor(218));
            g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 7, 7);
            g.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 190));
            g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 7, 7);
            drawEyeGlyph(g, new Rectangle(rect.x + 5, rect.y + 1, OVERLAY_EYE_SIZE, OVERLAY_EYE_SIZE), CYAN, false);
            g.setColor(FG);
            g.drawString(card.label, rect.x + 28, rect.y + 15);
            x += chipW + 6;
        }
    }

    private void drawEyeGlyph(Graphics2D g, Rectangle rect, Color color, boolean slash) {
        Stroke oldStroke = g.getStroke();
        g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 232));
        int eyeX = rect.x + 4;
        int eyeY = rect.y + 6;
        int eyeW = rect.width - 8;
        int eyeH = rect.height - 12;
        g.drawArc(eyeX, eyeY, eyeW, eyeH, 0, 180);
        g.drawArc(eyeX, eyeY, eyeW, eyeH, 180, 180);
        int cx = rect.x + rect.width / 2;
        int cy = rect.y + rect.height / 2;
        g.fillOval(cx - 2, cy - 2, 4, 4);
        if (slash) {
            g.drawLine(rect.x + 5, rect.y + rect.height - 5, rect.x + rect.width - 5, rect.y + 5);
        }
        g.setStroke(oldStroke);
    }

    private Zone findZoneAt(Point p) {
        if (result == null) return null;
        double cw = candleWidth();
        int chartW = plotWidth();
        int chartH = getHeight() - paddingTop - paddingBottom;

        int end = Math.min(candleCount(), firstVisible + visibleCount);
        List<Candle> view = visibleCandles(firstVisible, end);
        PriceRange displayRange = displayPriceRange(computeAutoPriceRange(view));
        PriceMapper m = new PriceMapper(paddingTop, chartH, displayRange.min, displayRange.max);

        zoneBuffer.clear();
        zoneBuffer.addAll(result.zones);
        zoneBuffer.sort(Comparator.comparingInt(this::zoneLayer));
        for (int i = zoneBuffer.size() - 1; i >= 0; i--) {
            Zone z = zoneBuffer.get(i);
            int createIdx = Math.max(0, z.creationIndex - firstVisible);
            int endIdx = z.mitigated ? z.mitigatedIndex - firstVisible : visibleCount + 8;
            if (endIdx < 0 || createIdx >= visibleCount) continue;
            endIdx = Math.min(visibleCount + 8, endIdx);
            int x1 = paddingLeft + (int) (createIdx * cw);
            int x2 = paddingLeft + (int) (endIdx * cw);
            int yT = m.y(z.top);
            int yB = m.y(z.bottom);
            Rectangle r = new Rectangle(x1, yT, Math.max(2, x2 - x1), Math.max(1, yB - yT));
            if (r.contains(p)) return z;
        }
        return null;
    }

    // Вспомогательные методы (раньше были в главном классе)
    private String fmt(double v) {
        return String.format("%.4f", v);
    }

    private String qty(double v) {
        return String.format(java.util.Locale.US, "%.6f", Math.abs(v)).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String signed(double v) {
        return (v >= 0.0 ? "+" : "") + String.format(java.util.Locale.US, "%.2f", v);
    }

    private String sessionRu(String session) {
        if (session == null) return "";
        return session.replace("London killzone", "Лондон KZ")
                .replace("New York killzone", "Нью-Йорк KZ")
                .replace("NY continuation", "NY continuation")
                .replace("PM session", "PM сессия")
                .replace("off-killzone", "вне KZ");
    }

    private int alpha(int opacity, double factor) {
        return clamp((int) Math.round(opacity / 100.0 * 255.0 * factor), 0, 255);
    }

    private int strokeAlpha(int opacity, double factor, int minAlpha, int maxAlpha) {
        int scaled = alpha(opacity, factor);
        return clamp(Math.max(minAlpha, scaled), minAlpha, Math.max(minAlpha, maxAlpha));
    }

    private String trimToWidth(Graphics2D g, String text, int maxWidth) {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (g.getFontMetrics().stringWidth(text.substring(0, mid) + ellipsis) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, Math.max(0, lo)) + ellipsis;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double clampDouble(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private Color brighten(Color color, int add) {
        return new Color(
                clamp(color.getRed() + add, 0, 255),
                clamp(color.getGreen() + add, 0, 255),
                clamp(color.getBlue() + add, 0, 255));
    }

    private Color glassColor(int alpha) {
        return new Color(FIELD_BG.getRed(), FIELD_BG.getGreen(), FIELD_BG.getBlue(), clamp(alpha, 0, 255));
    }

    private static class CinematicViewport {
        final int firstVisible;
        final int visibleCount;
        final boolean priceBarRatioLocked;
        final double manualPriceCenter;
        final double lockedPricePerBar;
        final boolean previousCinematicMode;
        final float previousCinematicAlpha;

        CinematicViewport(int firstVisible,
                          int visibleCount,
                          boolean priceBarRatioLocked,
                          double manualPriceCenter,
                          double lockedPricePerBar,
                          boolean previousCinematicMode,
                          float previousCinematicAlpha) {
            this.firstVisible = firstVisible;
            this.visibleCount = visibleCount;
            this.priceBarRatioLocked = priceBarRatioLocked;
            this.manualPriceCenter = manualPriceCenter;
            this.lockedPricePerBar = lockedPricePerBar;
            this.previousCinematicMode = previousCinematicMode;
            this.previousCinematicAlpha = previousCinematicAlpha;
        }
    }

    private static class LiquidityLabel {
        final LiquidityLevel level;
        final String text;
        final Color color;
        final int y;
        int drawY;

        LiquidityLabel(LiquidityLevel level, String text, Color color, int y) {
            this.level = level;
            this.text = text;
            this.color = color;
            this.y = y;
            this.drawY = y;
        }
    }

    private static class PriceRange {
        final double min;
        final double max;

        PriceRange(double min, double max) {
            this.min = min;
            this.max = max;
        }

        double center() {
            return (min + max) / 2.0;
        }

        double range() {
            return Math.max(0.00000001, max - min);
        }
    }

    private static class LevelProjection {
        final long timeMillis;
        final boolean reached;
        final boolean exitAlert;

        LevelProjection(long timeMillis, boolean reached, boolean exitAlert) {
            this.timeMillis = Math.max(0L, timeMillis);
            this.reached = reached;
            this.exitAlert = exitAlert;
        }
    }

    private static class PlanHealth {
        final String status;
        final String action;
        final String ageText;
        final String rText;
        final Color color;
        final String note;

        PlanHealth(String status, String action, String ageText, String rText, Color color, String note) {
            this.status = status;
            this.action = action;
            this.ageText = ageText;
            this.rText = rText;
            this.color = color;
            this.note = note;
        }
    }
}
