package app.ui;

import app.common.UiSettings;
import app.common.I18n;
import app.execution.fix.AccountAssetSnapshot;
import app.execution.fix.AccountSnapshot;
import app.execution.fix.ExchangeAccountSettings;
import app.execution.fix.OpenOrderSnapshot;
import app.execution.fix.OpenPositionSnapshot;
import app.liquidity.LiquidityLevel;
import app.model.AnalysisResult;
import app.model.ChartInsight;
import app.model.TradePlan;
import app.model.Zone;
import app.orderflow.FootprintCandle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.util.List;
import java.util.Locale;

import static app.Colors.*;

public class SidePanel extends JPanel implements Scrollable {
    private static final UiSettings SETTINGS = UiSettings.get();
    private static final String PREF_FVG_OPACITY = "chart.opacity.fvg";
    private static final String PREF_OB_OPACITY = "chart.opacity.ob";
    private static final String PREF_OTE_OPACITY = "chart.opacity.ote";
    private static final String PREF_LIQUIDITY_OPACITY = "chart.opacity.liquidity";
    private static final String PREF_MARKER_OPACITY = "chart.opacity.marker";
    private static final String PREF_ZONE_LABEL_OPACITY = "chart.opacity.zoneLabels";
    private static final String PREF_PRICE_BAR_RATIO_LOCK = "chart.scale.priceBarRatioLock";
    private static final String PREF_CANDLE_BULL = "chart.candle.bull";
    private static final String PREF_CANDLE_BEAR = "chart.candle.bear";
    private static final String PREF_CANDLE_NEUTRAL = "chart.candle.neutral";
    private static final String PREF_CANDLE_WICK = "chart.candle.wick";
    private static final String PREF_CANDLE_WICKS_FOLLOW_BODY = "chart.candle.wicksFollowBody";
    private static final String PREF_LAYER_FVG_BULL = "chart.layer.fvgBull";
    private static final String PREF_LAYER_FVG_BEAR = "chart.layer.fvgBear";
    private static final String PREF_LAYER_OB_BULL = "chart.layer.obBull";
    private static final String PREF_LAYER_OB_BEAR = "chart.layer.obBear";
    private static final String PREF_LAYER_OTE = "chart.layer.ote";
    private static final String PREF_LAYER_ENTRY = "chart.layer.entry";
    private static final String PREF_LAYER_STOP = "chart.layer.stop";
    private static final String PREF_LAYER_TARGET = "chart.layer.target";
    private static final String PREF_LAYER_CURRENT_PRICE = "chart.layer.currentPrice";
    private static final String PREF_LAYER_BSL = "chart.layer.bsl";
    private static final String PREF_LAYER_SSL = "chart.layer.ssl";
    private static final String PREF_LAYER_DISP_BULL = "chart.layer.displacementBull";
    private static final String PREF_LAYER_DISP_BEAR = "chart.layer.displacementBear";
    private static final String PREF_LAYER_SWEEP = "chart.layer.sweep";
    private static final String PREF_LAYER_IDM = "chart.layer.idm";

    private final JLabel symbolCaption = label("Монета", 11f, Font.BOLD, MUTED);
    private final JLabel symbol = label(" ", 19f, Font.BOLD, FG);
    private final JLabel price = label(" ", 25f, Font.BOLD, YELLOW);
    // Reused across updatePrice() calls instead of allocating a new Timer per tick (this fires
    // up to ~8x/sec while a live price is streaming) -- restart() resets the delay without
    // registering a brand new Timer with the Swing timer queue every time.
    private Timer priceFlashTimer;
    private final JLabel trend = label(" ", 12f, Font.BOLD, FG);
    private final JLabel priority = label(" ", 12f, Font.BOLD, MUTED);
    private final JLabel scoreBadge = label("SMC --/100", 12f, Font.BOLD, MUTED);
    private final JLabel sessionBadge = label("Сессия: --", 12f, Font.PLAIN, MUTED);

    private final JEditorPane chartAnalysis = editor();
    private final JEditorPane plan = editor();
    private final JEditorPane positions = editor();
    private final JEditorPane liquidity = editor();
    private final JEditorPane orderFlow = editor();
    private final JEditorPane details = editor();
    private final JEditorPane help = editor();
    private final JTabbedPane tabs = new JTabbedPane();
    private JPanel headerActionSlot;
    private ChartPanel chartPanel;
    private PositionActionListener positionActionListener;
    private int fvgOpacity = opacityPref(PREF_FVG_OPACITY, 34);
    private int obOpacity = opacityPref(PREF_OB_OPACITY, 24);
    private int oteOpacity = opacityPref(PREF_OTE_OPACITY, 18);
    private int liquidityOpacity = opacityPref(PREF_LIQUIDITY_OPACITY, 72);
    private int markerOpacity = opacityPref(PREF_MARKER_OPACITY, 88);
    private int zoneLabelOpacity = opacityPref(PREF_ZONE_LABEL_OPACITY, 82);
    private boolean priceBarRatioLocked = SETTINGS.getInt(PREF_PRICE_BAR_RATIO_LOCK, 0, 0, 1) == 1;
    private Color bullCandleColor = colorPref(PREF_CANDLE_BULL, BULL);
    private Color bearCandleColor = colorPref(PREF_CANDLE_BEAR, BEAR);
    private Color neutralCandleColor = colorPref(PREF_CANDLE_NEUTRAL, FG);
    private Color wickCandleColor = colorPref(PREF_CANDLE_WICK, MUTED);
    private boolean candleWicksFollowBody = SETTINGS.getInt(PREF_CANDLE_WICKS_FOLLOW_BODY, 1, 0, 1) == 1;
    private Color fvgBullColor = colorPref(PREF_LAYER_FVG_BULL, new Color(45, 170, 255));
    private Color fvgBearColor = colorPref(PREF_LAYER_FVG_BEAR, new Color(255, 152, 67));
    private Color obBullColor = colorPref(PREF_LAYER_OB_BULL, BULL);
    private Color obBearColor = colorPref(PREF_LAYER_OB_BEAR, BEAR);
    private Color oteColor = colorPref(PREF_LAYER_OTE, new Color(126, 87, 194));
    private Color entryColor = colorPref(PREF_LAYER_ENTRY, new Color(255, 64, 129));
    private Color stopColor = colorPref(PREF_LAYER_STOP, BEAR);
    private Color targetColor = colorPref(PREF_LAYER_TARGET, BULL);
    private Color currentPriceColor = colorPref(PREF_LAYER_CURRENT_PRICE, YELLOW);
    private Color bslColor = colorPref(PREF_LAYER_BSL, BEAR);
    private Color sslColor = colorPref(PREF_LAYER_SSL, BULL);
    private Color displacementBullColor = colorPref(PREF_LAYER_DISP_BULL, BULL);
    private Color displacementBearColor = colorPref(PREF_LAYER_DISP_BEAR, BEAR);
    private Color sweepColor = colorPref(PREF_LAYER_SWEEP, YELLOW);
    private Color idmColor = colorPref(PREF_LAYER_IDM, PURPLE);

    private AnalysisResult lastResult;
    private ChartInsight lastInsight;
    private ExchangeAccountSettings lastSettings;
    private AccountSnapshot lastSnapshot;
    private String lastError;
    private boolean lastLoading;

    public SidePanel() {
        setBackground(PANEL_BG);
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 10));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        chartAnalysis.setText(emptyChartInsight());
        details.setText(emptyDetails());
        positions.setText(positionsText(null, null, "", false));
        positions.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                handlePositionLink(e.getDescription());
            }
        });
        help.setText(helpText());
    }

    public void bindChart(ChartPanel chartPanel) {
        this.chartPanel = chartPanel;
        applyChartOpacitySettings();
    }

    public void setPositionActionListener(PositionActionListener listener) {
        this.positionActionListener = listener;
    }

    public void setHeaderActionComponent(JComponent component) {
        if (headerActionSlot == null) return;
        headerActionSlot.removeAll();
        if (component != null) headerActionSlot.add(component);
        headerActionSlot.setVisible(component != null);
        headerActionSlot.revalidate();
        headerActionSlot.repaint();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(true);
        header.setBackground(CARD_BG);
        header.putClientProperty("theme.role", "card");
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(10, 12, 10, 12)));
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        symbolCaption.setAlignmentX(LEFT_ALIGNMENT);
        symbol.setAlignmentX(LEFT_ALIGNMENT);
        left.add(symbolCaption);
        left.add(Box.createVerticalStrut(2));
        left.add(symbol);

        headerActionSlot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        headerActionSlot.setOpaque(false);
        headerActionSlot.setVisible(false);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        headerActionSlot.setAlignmentX(RIGHT_ALIGNMENT);
        scoreBadge.setAlignmentX(RIGHT_ALIGNMENT);
        right.add(headerActionSlot);
        right.add(Box.createVerticalStrut(8));
        right.add(scoreBadge);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);

        header.add(row);
        header.add(Box.createVerticalStrut(6));
        header.add(price);
        header.add(Box.createVerticalStrut(8));

        JPanel meta = new JPanel(new GridLayout(3, 1, 0, 4));
        meta.setOpaque(false);
        meta.add(trend);
        meta.add(priority);
        meta.add(sessionBadge);
        header.add(meta);

        return header;
    }

    private JComponent buildTabs() {
        tabs.setFont(UIUtils.labelFont(12.4f));
        tabs.setBackground(PANEL_BG);
        tabs.setForeground(FG);
        UIUtils.styleTabbedPane(tabs, PANEL_BG, BG, FG, MUTED, ACCENT);
        tabs.addTab(I18n.t("План"), scroll(plan));
        tabs.addTab(I18n.t("Анализ"), scroll(chartAnalysis));
        tabs.addTab(I18n.t("Позиции"), scroll(positions));
        tabs.addTab(I18n.t("Ликвидность"), scroll(liquidity));
        tabs.addTab(I18n.t("Поток"), scroll(orderFlow));
        tabs.addTab(I18n.t("Детали"), scroll(details));
        tabs.addTab(I18n.t("Настройки"), buildSettings());
        tabs.addTab(I18n.t("Справка"), scroll(help));
        I18n.refreshTree(tabs);
        return tabs;
    }

    private JScrollPane scroll(JEditorPane pane) {
        JScrollPane sp = new JScrollPane(pane);
        UIUtils.styleScrollPane(sp, GRID);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createLineBorder(GRID));
        return sp;
    }

    private JEditorPane editor() {
        JEditorPane pane = new WrapEditorPane();
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setBackground(BG);
        pane.setForeground(FG);
        pane.setFont(UIUtils.bodyFont(12.4f));
        return pane;
    }

    private JPanel buildSettings() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.putClientProperty("theme.role", "bg");
        root.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(settingsGroup(I18n.t("Тема оформления"), I18n.t("Цветовая схема приложения.")));
        JComboBox<app.Colors.Theme> themeBox = new JComboBox<>(app.Colors.Theme.values());
        themeBox.setSelectedItem(app.Colors.currentTheme);
        themeBox.setPreferredSize(new Dimension(220, 28));
        themeBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        themeBox.setAlignmentX(LEFT_ALIGNMENT);
        UIUtils.styleComboBox(themeBox, BG, FG, GRID);
        themeBox.addActionListener(e -> {
            app.Colors.Theme selected = (app.Colors.Theme) themeBox.getSelectedItem();
            if (selected != null && selected != app.Colors.currentTheme) {
                app.SmcChartApp.applyThemeAndRefresh(selected);
            }
        });
        panel.add(themeBox);
        panel.add(Box.createVerticalStrut(10));

        panel.add(settingsGroup(I18n.t("Язык"), I18n.t("Язык интерфейса. Изменения применяются сразу.")));
        JComboBox<I18n.Language> languageBox = new JComboBox<>(I18n.Language.values());
        languageBox.setSelectedItem(I18n.language());
        languageBox.setPreferredSize(new Dimension(220, 28));
        languageBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        languageBox.setAlignmentX(LEFT_ALIGNMENT);
        UIUtils.styleComboBox(languageBox, BG, FG, GRID);
        languageBox.addActionListener(e -> {
            I18n.Language selected = (I18n.Language) languageBox.getSelectedItem();
            if (selected != null && selected != I18n.language()) {
                app.SmcChartApp.applyLanguageAndRefresh(selected);
            }
        });
        panel.add(languageBox);
        panel.add(Box.createVerticalStrut(10));

        panel.add(settingsGroup(I18n.t("График"), I18n.t("Ручной масштаб без автоцентрирования.")));
        panel.add(toggle(I18n.t("Зафиксировать цена/бар"), I18n.t("Колесо - zoom, drag - панорама, Shift+колесо - вертикальный шаг."),
                priceBarRatioLocked, PREF_PRICE_BAR_RATIO_LOCK, value -> {
                    priceBarRatioLocked = value;
                    if (chartPanel != null) chartPanel.setPriceBarRatioLocked(value);
                }));
        panel.add(Box.createVerticalStrut(5));

        panel.add(settingsGroup(I18n.t("Свечи"), I18n.t("Цвет тела и фитилей японских свечей.")));
        panel.add(colorRow(I18n.t("Бычья свеча"), bullCandleColor, color -> {
            bullCandleColor = color;
            saveColor(PREF_CANDLE_BULL, color);
            applyChartCandleSettings();
        }));
        panel.add(colorRow(I18n.t("Медвежья свеча"), bearCandleColor, color -> {
            bearCandleColor = color;
            saveColor(PREF_CANDLE_BEAR, color);
            applyChartCandleSettings();
        }));
        panel.add(colorRow(I18n.t("Нейтральная свеча"), neutralCandleColor, color -> {
            neutralCandleColor = color;
            saveColor(PREF_CANDLE_NEUTRAL, color);
            applyChartCandleSettings();
        }));
        panel.add(colorRow(I18n.t("Фитили"), wickCandleColor, color -> {
            wickCandleColor = color;
            saveColor(PREF_CANDLE_WICK, color);
            applyChartCandleSettings();
        }));
        panel.add(toggle(I18n.t("Фитили цветом тела"), "", candleWicksFollowBody,
                PREF_CANDLE_WICKS_FOLLOW_BODY, value -> {
                    candleWicksFollowBody = value;
                    applyChartCandleSettings();
                }));
        JButton resetCandles = new JButton(I18n.t("Сбросить цвета свечей"));
        resetCandles.putClientProperty("i18n.source", "Сбросить цвета свечей");
        resetCandles.putClientProperty("theme.button", "muted");
        resetCandles.setAlignmentX(LEFT_ALIGNMENT);
        resetCandles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        UIUtils.stylePrimaryButton(resetCandles, MUTED);
        resetCandles.addActionListener(e -> resetCandleColors());
        panel.add(resetCandles);
        panel.add(Box.createVerticalStrut(10));

        panel.add(settingsGroup(I18n.t("Слои графика"), I18n.t("FVG, OB, OTE, ликвидность, вход и ценовая линия.")));
        addChartLayerColorRows(panel);
        JButton resetLayers = new JButton(I18n.t("Сбросить цвета слоев"));
        resetLayers.putClientProperty("i18n.source", "Сбросить цвета слоев");
        resetLayers.putClientProperty("theme.button", "muted");
        resetLayers.setAlignmentX(LEFT_ALIGNMENT);
        resetLayers.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        UIUtils.stylePrimaryButton(resetLayers, MUTED);
        resetLayers.addActionListener(e -> resetLayerColors());
        panel.add(resetLayers);
        panel.add(Box.createVerticalStrut(10));

        panel.add(settingsGroup(I18n.t("Зоны"), I18n.t("Заливка отдельно от подписей.")));
        panel.add(slider(I18n.t("FVG: ценовой разрыв"), fvgOpacity, PREF_FVG_OPACITY, value -> {
            fvgOpacity = value;
            if (chartPanel != null) chartPanel.setFvgOpacity(value);
        }));
        panel.add(slider(I18n.t("OB: order block"), obOpacity, PREF_OB_OPACITY, value -> {
            obOpacity = value;
            if (chartPanel != null) chartPanel.setObOpacity(value);
        }));
        panel.add(slider(I18n.t("OTE: зона отката"), oteOpacity, PREF_OTE_OPACITY, value -> {
            oteOpacity = value;
            if (chartPanel != null) chartPanel.setOteOpacity(value);
        }));
        panel.add(slider(I18n.t("Подписи OB/FVG/OTE"), zoneLabelOpacity, PREF_ZONE_LABEL_OPACITY, value -> {
            zoneLabelOpacity = value;
            if (chartPanel != null) chartPanel.setZoneLabelOpacity(value);
        }));
        panel.add(Box.createVerticalStrut(5));
        panel.add(settingsGroup(I18n.t("События"), I18n.t("Линии и маркеры без влияния на зоны.")));
        panel.add(slider(I18n.t("BSL / SSL линии"), liquidityOpacity, PREF_LIQUIDITY_OPACITY, value -> {
            liquidityOpacity = value;
            if (chartPanel != null) chartPanel.setLiquidityOpacity(value);
        }));
        panel.add(slider(I18n.t("Sweep / IDM метки"), markerOpacity, PREF_MARKER_OPACITY, value -> {
            markerOpacity = value;
            if (chartPanel != null) chartPanel.setMarkerOpacity(value);
        }));

        panel.add(Box.createVerticalStrut(5));
        panel.add(settingsHint("<html><body style='width:220px;'>" + I18n.t("Если график перегружен: FVG/OB 20-35%, подписи 45-70%. Подпись OTE тоже регулируется этим слайдером.") + "</body></html>"));
        root.add(panel, BorderLayout.NORTH);
        return root;
    }

    private void addChartLayerColorRows(JPanel panel) {
        panel.add(colorRow(I18n.t("FVG long"), fvgBullColor, color -> {
            fvgBullColor = color;
            saveColor(PREF_LAYER_FVG_BULL, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("FVG short"), fvgBearColor, color -> {
            fvgBearColor = color;
            saveColor(PREF_LAYER_FVG_BEAR, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("OB long"), obBullColor, color -> {
            obBullColor = color;
            saveColor(PREF_LAYER_OB_BULL, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("OB short"), obBearColor, color -> {
            obBearColor = color;
            saveColor(PREF_LAYER_OB_BEAR, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("OTE"), oteColor, color -> {
            oteColor = color;
            saveColor(PREF_LAYER_OTE, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Entry line"), entryColor, color -> {
            entryColor = color;
            saveColor(PREF_LAYER_ENTRY, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Stop line"), stopColor, color -> {
            stopColor = color;
            saveColor(PREF_LAYER_STOP, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Target/TP line"), targetColor, color -> {
            targetColor = color;
            saveColor(PREF_LAYER_TARGET, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Current price line"), currentPriceColor, color -> {
            currentPriceColor = color;
            saveColor(PREF_LAYER_CURRENT_PRICE, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("BSL"), bslColor, color -> {
            bslColor = color;
            saveColor(PREF_LAYER_BSL, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("SSL"), sslColor, color -> {
            sslColor = color;
            saveColor(PREF_LAYER_SSL, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Displacement long"), displacementBullColor, color -> {
            displacementBullColor = color;
            saveColor(PREF_LAYER_DISP_BULL, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Displacement short"), displacementBearColor, color -> {
            displacementBearColor = color;
            saveColor(PREF_LAYER_DISP_BEAR, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("Sweep marker"), sweepColor, color -> {
            sweepColor = color;
            saveColor(PREF_LAYER_SWEEP, color);
            applyChartLayerSettings();
        }));
        panel.add(colorRow(I18n.t("IDM marker"), idmColor, color -> {
            idmColor = color;
            saveColor(PREF_LAYER_IDM, color);
            applyChartLayerSettings();
        }));
    }

    public void refreshThemeComponents() {
        setBackground(app.Colors.PANEL_BG);
        restyleThemeTree(this);
        symbolCaption.setForeground(app.Colors.MUTED);
        symbol.setForeground(app.Colors.FG);
        trend.setForeground(trend.getForeground() == BULL || trend.getForeground() == BEAR ? trend.getForeground() : app.Colors.FG);
        priority.setForeground(priority.getForeground() == BULL || priority.getForeground() == BEAR ? priority.getForeground() : app.Colors.MUTED);
        
        plan.setBackground(app.Colors.BG);
        plan.setForeground(app.Colors.FG);
        chartAnalysis.setBackground(app.Colors.BG);
        chartAnalysis.setForeground(app.Colors.FG);
        positions.setBackground(app.Colors.BG);
        positions.setForeground(app.Colors.FG);
        liquidity.setBackground(app.Colors.BG);
        liquidity.setForeground(app.Colors.FG);
        orderFlow.setBackground(app.Colors.BG);
        orderFlow.setForeground(app.Colors.FG);
        details.setBackground(app.Colors.BG);
        details.setForeground(app.Colors.FG);
        help.setBackground(app.Colors.BG);
        help.setForeground(app.Colors.FG);

        tabs.setBackground(app.Colors.PANEL_BG);
        tabs.setForeground(app.Colors.FG);
        UIUtils.styleTabbedPane(tabs, app.Colors.PANEL_BG, app.Colors.BG,
                app.Colors.FG, app.Colors.MUTED, app.Colors.ACCENT);
        
        refreshTexts();
    }

    private void restyleThemeTree(Component component) {
        if (component == null) return;
        if (component instanceof JScrollPane sp) {
            UIUtils.styleScrollPane(sp, app.Colors.GRID);
            sp.setBackground(app.Colors.BG);
            sp.getViewport().setBackground(app.Colors.BG);
        } else if (component instanceof JEditorPane editor) {
            editor.setBackground(app.Colors.BG);
            editor.setForeground(app.Colors.FG);
        } else if (component instanceof JComboBox<?> combo) {
            UIUtils.styleComboBox(combo, app.Colors.BG, app.Colors.FG, app.Colors.GRID);
        } else if (component instanceof JCheckBox box) {
            box.setForeground(app.Colors.FG);
            box.setBackground(app.Colors.FIELD_BG);
        } else if (component instanceof JSlider slider) {
            slider.setForeground(app.Colors.MUTED);
            slider.setBackground(app.Colors.FIELD_BG);
        } else if (component instanceof AbstractButton button) {
            Object role = button.getClientProperty("theme.button");
            if ("muted".equals(role)) UIUtils.stylePrimaryButton(button, app.Colors.MUTED);
            else if ("color".equals(role) && button instanceof JButton jb) styleColorButton(jb, jb.getBackground());
            else button.setForeground(app.Colors.FG);
        } else if (component instanceof JLabel label) {
            Object role = label.getClientProperty("theme.fg");
            if ("muted".equals(role)) label.setForeground(app.Colors.MUTED);
            else if ("fg".equals(role)) label.setForeground(app.Colors.FG);
            else if ("accent".equals(role)) label.setForeground(app.Colors.ACCENT);
        } else if (component instanceof JPanel panel) {
            Object role = panel.getClientProperty("theme.role");
            if ("card".equals(role)) panel.setBackground(app.Colors.CARD_BG);
            else if ("field".equals(role)) panel.setBackground(app.Colors.FIELD_BG);
            else if ("bg".equals(role)) panel.setBackground(app.Colors.BG);
            else if ("panel".equals(role)) panel.setBackground(app.Colors.PANEL_BG);
        }
        if (component instanceof JComponent jc
                && ("field".equals(jc.getClientProperty("theme.role"))
                || "card".equals(jc.getClientProperty("theme.role")))) {
            refreshGridBorder(jc);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) restyleThemeTree(child);
        }
    }

    private void refreshGridBorder(JComponent component) {
        javax.swing.border.Border border = component.getBorder();
        if (border instanceof javax.swing.border.CompoundBorder compound) {
            component.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(app.Colors.GRID),
                    compound.getInsideBorder()));
        } else if (border instanceof javax.swing.border.LineBorder) {
            component.setBorder(BorderFactory.createLineBorder(app.Colors.GRID));
        }
    }

    public void refreshTexts() {
        if (lastResult != null) {
            update(lastResult);
        }
        if (lastInsight != null) {
            updateChartInsight(lastInsight, false);
        }
        positions.setText(positionsText(lastSettings, lastSnapshot, lastError, lastLoading));
        help.setText(helpText());
    }

    public void refreshLanguageComponents() {
        tabs.setTitleAt(0, I18n.t("План"));
        tabs.setTitleAt(1, I18n.t("Анализ"));
        tabs.setTitleAt(2, I18n.t("Позиции"));
        tabs.setTitleAt(3, I18n.t("Ликвидность"));
        tabs.setTitleAt(4, I18n.t("Поток"));
        tabs.setTitleAt(5, I18n.t("Детали"));
        tabs.setTitleAt(6, I18n.t("Настройки"));
        tabs.setTitleAt(7, I18n.t("Справка"));
        rebuildSettingsTab();
        I18n.refreshTree(this);
        refreshTexts();
    }

    private JComponent settingsGroup(String title, String hint) {
        JPanel box = new JPanel(new BorderLayout(0, 3));
        box.setOpaque(false);
        box.setBorder(new EmptyBorder(0, 0, 5, 0));
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        JLabel heading = label(title, 12f, Font.BOLD, FG);
        JLabel sub = label(hint, 10f, Font.PLAIN, MUTED);
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(heading, BorderLayout.WEST);
        row.add(sub, BorderLayout.CENTER);
        box.add(row, BorderLayout.CENTER);
        return box;
    }

    private JComponent slider(String title, int value, String prefKey, SliderConsumer consumer) {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBackground(FIELD_BG);
        panel.setOpaque(true);
        panel.putClientProperty("theme.role", "field");
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(6, 9, 6, 9)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        JLabel name = label(title, 12f, Font.BOLD, FG);
        JLabel percent = label(value + "%", 11f, Font.BOLD, opacityColor(value));
        JSlider slider = new JSlider(0, 100, value);
        slider.setOpaque(false);
        slider.setFocusable(false);
        slider.setMajorTickSpacing(25);
        slider.setPaintTicks(false);
        slider.setForeground(MUTED);
        slider.addChangeListener(e -> {
            int v = slider.getValue();
            percent.setText(v + "%");
            percent.setForeground(opacityColor(v));
            if (!slider.getValueIsAdjusting()) SETTINGS.putInt(prefKey, v);
            consumer.accept(v);
        });
        panel.add(name, BorderLayout.WEST);
        panel.add(percent, BorderLayout.EAST);
        panel.add(slider, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent colorRow(String title, Color initial, ColorConsumer consumer) {
        JPanel panel = new JPanel(new BorderLayout(8, 3));
        panel.setBackground(FIELD_BG);
        panel.setOpaque(true);
        panel.putClientProperty("theme.role", "field");
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(7, 9, 7, 9)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

        JLabel name = label(title, 12f, Font.BOLD, FG);
        JButton pick = new JButton(colorHex(initial));
        pick.putClientProperty("theme.button", "color");
        pick.setPreferredSize(new Dimension(96, 28));
        styleColorButton(pick, initial);
        pick.addActionListener(e -> {
            Color selected = JColorChooser.showDialog(this, title, pick.getBackground());
            if (selected == null) return;
            styleColorButton(pick, selected);
            consumer.accept(selected);
        });
        panel.add(name, BorderLayout.WEST);
        panel.add(pick, BorderLayout.EAST);
        return panel;
    }

    private void styleColorButton(JButton button, Color color) {
        Color safe = color == null ? FG : color;
        button.setText(colorHex(safe));
        button.setBackground(safe);
        button.setForeground(contrastColor(safe));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(GRID));
        button.setFont(UIUtils.monoFont(11f, Font.BOLD));
    }

    private JComponent toggle(String title, String hint, boolean selected,
                              String prefKey, ToggleConsumer consumer) {
        JPanel panel = new JPanel(new BorderLayout(8, 3));
        panel.setBackground(FIELD_BG);
        panel.setOpaque(true);
        panel.putClientProperty("theme.role", "field");
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(7, 9, 7, 9)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

        JCheckBox box = new JCheckBox(title, selected);
        box.setOpaque(false);
        box.setFocusable(false);
        box.setForeground(FG);
        box.setFont(UIUtils.labelFont(12.2f));

        JLabel note = label(hint, 10f, Font.PLAIN, MUTED);
        panel.add(box, BorderLayout.NORTH);
        panel.add(note, BorderLayout.SOUTH);
        box.addActionListener(e -> {
            boolean value = box.isSelected();
            SETTINGS.putInt(prefKey, value ? 1 : 0);
            consumer.accept(value);
        });
        return panel;
    }

    private JLabel settingsHint(String html) {
        JLabel note = label(html, 11f, Font.PLAIN, MUTED);
        note.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(8, 10, 8, 10)));
        note.setOpaque(true);
        note.setBackground(FIELD_BG);
        note.putClientProperty("theme.role", "field");
        note.setAlignmentX(LEFT_ALIGNMENT);
        return note;
    }

    private Color opacityColor(int value) {
        if (value >= 70) return BULL;
        if (value >= 35) return YELLOW;
        return MUTED;
    }

    private void applyChartOpacitySettings() {
        if (chartPanel == null) return;
        chartPanel.setFvgOpacity(fvgOpacity);
        chartPanel.setObOpacity(obOpacity);
        chartPanel.setOteOpacity(oteOpacity);
        chartPanel.setLiquidityOpacity(liquidityOpacity);
        chartPanel.setMarkerOpacity(markerOpacity);
        chartPanel.setZoneLabelOpacity(zoneLabelOpacity);
        chartPanel.setPriceBarRatioLocked(priceBarRatioLocked);
        applyChartCandleSettings();
        applyChartLayerSettings();
    }

    private void applyChartCandleSettings() {
        if (chartPanel == null) return;
        chartPanel.setCandleColors(bullCandleColor, bearCandleColor, neutralCandleColor,
                wickCandleColor, candleWicksFollowBody);
    }

    private void applyChartLayerSettings() {
        if (chartPanel == null) return;
        chartPanel.setChartLayerColors(fvgBullColor, fvgBearColor,
                obBullColor, obBearColor, oteColor,
                entryColor, stopColor, targetColor, currentPriceColor,
                bslColor, sslColor,
                displacementBullColor, displacementBearColor,
                sweepColor, idmColor);
    }

    private void resetCandleColors() {
        bullCandleColor = BULL;
        bearCandleColor = BEAR;
        neutralCandleColor = FG;
        wickCandleColor = MUTED;
        candleWicksFollowBody = true;
        saveColor(PREF_CANDLE_BULL, bullCandleColor);
        saveColor(PREF_CANDLE_BEAR, bearCandleColor);
        saveColor(PREF_CANDLE_NEUTRAL, neutralCandleColor);
        saveColor(PREF_CANDLE_WICK, wickCandleColor);
        SETTINGS.putInt(PREF_CANDLE_WICKS_FOLLOW_BODY, 1);
        applyChartCandleSettings();
        rebuildSettingsTab();
    }

    private void resetLayerColors() {
        fvgBullColor = new Color(45, 170, 255);
        fvgBearColor = new Color(255, 152, 67);
        obBullColor = BULL;
        obBearColor = BEAR;
        oteColor = new Color(126, 87, 194);
        entryColor = new Color(255, 64, 129);
        stopColor = BEAR;
        targetColor = BULL;
        currentPriceColor = YELLOW;
        bslColor = BEAR;
        sslColor = BULL;
        displacementBullColor = BULL;
        displacementBearColor = BEAR;
        sweepColor = YELLOW;
        idmColor = PURPLE;
        saveLayerColors();
        applyChartLayerSettings();
        rebuildSettingsTab();
    }

    private void saveLayerColors() {
        saveColor(PREF_LAYER_FVG_BULL, fvgBullColor);
        saveColor(PREF_LAYER_FVG_BEAR, fvgBearColor);
        saveColor(PREF_LAYER_OB_BULL, obBullColor);
        saveColor(PREF_LAYER_OB_BEAR, obBearColor);
        saveColor(PREF_LAYER_OTE, oteColor);
        saveColor(PREF_LAYER_ENTRY, entryColor);
        saveColor(PREF_LAYER_STOP, stopColor);
        saveColor(PREF_LAYER_TARGET, targetColor);
        saveColor(PREF_LAYER_CURRENT_PRICE, currentPriceColor);
        saveColor(PREF_LAYER_BSL, bslColor);
        saveColor(PREF_LAYER_SSL, sslColor);
        saveColor(PREF_LAYER_DISP_BULL, displacementBullColor);
        saveColor(PREF_LAYER_DISP_BEAR, displacementBearColor);
        saveColor(PREF_LAYER_SWEEP, sweepColor);
        saveColor(PREF_LAYER_IDM, idmColor);
    }

    private int opacityPref(String key, int fallback) {
        return SETTINGS.getInt(key, fallback, 0, 100);
    }

    private Color colorPref(String key, Color fallback) {
        String value = SETTINGS.getString(key, colorHex(fallback));
        try {
            return Color.decode(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void saveColor(String key, Color color) {
        SETTINGS.putString(key, colorHex(color));
    }

    private String colorHex(Color color) {
        Color safe = color == null ? Color.WHITE : color;
        return String.format("#%02X%02X%02X", safe.getRed(), safe.getGreen(), safe.getBlue());
    }

    private Color contrastColor(Color color) {
        int luminance = (int) (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue());
        return luminance >= 150 ? Color.BLACK : Color.WHITE;
    }

    private void rebuildSettingsTab() {
        int selected = tabs.getSelectedIndex();
        tabs.setComponentAt(6, buildSettings());
        tabs.setTitleAt(6, I18n.t("Настройки"));
        tabs.setSelectedIndex(Math.min(selected, tabs.getTabCount() - 1));
        revalidate();
        repaint();
    }

    private JLabel label(String text, float size, int style, Color color) {
        JLabel l = new JLabel(I18n.t(text));
        l.putClientProperty("i18n.source", text);
        l.setForeground(color);
        l.setFont((style & Font.BOLD) != 0
                ? UIUtils.labelFont(size)
                : (size <= 11.5f ? UIUtils.captionFont(size) : UIUtils.bodyFont(size)));
        if (sameRgb(color, FG)) l.putClientProperty("theme.fg", "fg");
        else if (sameRgb(color, MUTED)) l.putClientProperty("theme.fg", "muted");
        else if (sameRgb(color, ACCENT)) l.putClientProperty("theme.fg", "accent");
        return l;
    }

    private boolean sameRgb(Color a, Color b) {
        return a != null && b != null
                && a.getRed() == b.getRed()
                && a.getGreen() == b.getGreen()
                && a.getBlue() == b.getBlue();
    }

    public void update(AnalysisResult r) {
        this.lastResult = r;
        symbol.setText(r.symbol + " · " + r.ltfInterval);
        price.setText(String.format("%.4f", r.currentPrice));
        price.setForeground(YELLOW);

        String bias = r.htfBullish ? "бычий" : "медвежий";
        trend.setText("HTF bias: " + bias);
        trend.setForeground(r.htfBullish ? BULL : BEAR);
        priority.setText(r.plan == null ? "Статус: наблюдение, вход не подтвержден" : "Приоритет: " + (r.plan.longSide ? "лонг" : "шорт"));
        priority.setForeground(r.plan == null ? MUTED : (r.plan.longSide ? BULL : BEAR));

        scoreBadge.setText("SMC " + r.smcScore + "/100");
        scoreBadge.setForeground(scoreColor(r.smcScore));
        String session = r.sessionContext == null ? "--" : translateSession(r.sessionContext.activeSession) + " · " + translatePhase(r.sessionContext.phase);
        sessionBadge.setText("Сессия: " + session);

        plan.setText(planText(r));
        liquidity.setText(liquidityText(r));
        orderFlow.setText(orderFlowText(r));
        if (tabs.getSelectedIndex() < 0) tabs.setSelectedIndex(0);
    }

    public void updateChartInsight(ChartInsight insight) {
        updateChartInsight(insight, true);
    }

    public void updateChartInsight(ChartInsight insight, boolean resetScroll) {
        this.lastInsight = insight;
        int caret = chartAnalysis.getCaretPosition();
        chartAnalysis.setText(chartInsightText(insight));
        int target = resetScroll ? 0 : Math.min(caret, chartAnalysis.getDocument().getLength());
        chartAnalysis.setCaretPosition(Math.max(0, target));
    }

    public void updatePrice(double newPrice, double oldPrice) {
        price.setText(String.format("%.4f", newPrice));
        price.setForeground(newPrice > oldPrice ? BULL : (newPrice < oldPrice ? BEAR : YELLOW));
        if (priceFlashTimer == null) {
            priceFlashTimer = new Timer(650, e -> price.setForeground(YELLOW));
            priceFlashTimer.setRepeats(false);
        }
        priceFlashTimer.restart();
    }

    public void showZone(Zone z, double currentPrice) {
        details.setText(zoneText(z, currentPrice));
        details.setCaretPosition(0);
        tabs.setSelectedComponent(details.getParent().getParent());
    }

    public void updatePositions(ExchangeAccountSettings settings,
                                AccountSnapshot snapshot,
                                String error,
                                boolean loading) {
        this.lastSettings = settings;
        this.lastSnapshot = snapshot;
        this.lastError = error;
        this.lastLoading = loading;
        positions.setText(positionsText(settings, snapshot, error, loading));
    }

    private String positionsText(ExchangeAccountSettings settings,
                                 AccountSnapshot snapshot,
                                 String error,
                                 boolean loading) {
        StringBuilder b = new StringBuilder();
        String account = settings == null ? "Аккаунт не выбран" : settings.summary();
        b.append("<div class='hero'>")
                .append("<div class='kicker'>Позиции / аккаунт</div>")
                .append("<div class='title'>").append(escape(accountProfile(settings))).append("</div>")
                .append("<div class='text'>").append(escape(account)).append("</div>")
                .append("</div>");

        if (loading) {
            b.append("<div class='note'>Обновляю баланс, позиции и открытые заявки...</div>");
        }
        if (error != null && !error.isBlank()) {
            b.append("<div class='note badBorder'><b>Ошибка обновления:</b> ")
                    .append(escape(error))
                    .append("</div>");
        }
        if (settings == null || !settings.configured()) {
            b.append("<div class='empty'>Аккаунт не настроен. Paper — локальный режим, Testnet / Demo — демо-биржа, Live — реальные заявки.</div>");
            return html(b.toString());
        }
        if (snapshot == null) {
            b.append("<div class='empty'>Снимок аккаунта еще не загружен.</div>");
            return html(b.toString());
        }

        String balanceText = Double.isFinite(snapshot.walletBalance)
                ? money(snapshot.walletBalance, snapshot.currency)
                + (settings != null && settings.mode == ExchangeAccountSettings.Mode.PAPER ? " · Paper local" : "")
                : Double.isFinite(snapshot.equity)
                ? money(snapshot.equity, snapshot.currency)
                : "Paper / нет реального баланса";
        String availableText = Double.isFinite(snapshot.availableBalance)
                ? money(snapshot.availableBalance, snapshot.currency)
                : "?";
        b.append(String.format(Locale.US, """
                <table class='tbl'>
                  <tr><td>Баланс кошелька</td><td><b style='color:#26A69A;'>%s</b></td></tr>
                  <tr><td>Свободно</td><td>%s</td></tr>
                  <tr><td>Equity</td><td>%s</td></tr>
                  <tr><td>uPnL</td><td><b style='color:%s;'>%s</b></td></tr>
                  <tr><td>Позиции</td><td><b>%d</b> · LONG %d / SHORT %d</td></tr>
                  <tr><td>Заявки</td><td><b>%d</b> · BUY %d / SELL %d · LIMIT %d · STOP %d</td></tr>
                </table>
                """,
                balanceText,
                availableText,
                Double.isFinite(snapshot.equity) ? money(snapshot.equity, snapshot.currency) : "?",
                snapshot.unrealizedPnl >= 0.0 ? "#26A69A" : "#EF5350",
                Double.isFinite(snapshot.unrealizedPnl) ? money(snapshot.unrealizedPnl, snapshot.currency) : "?",
                snapshot.openPositions.size(), snapshot.longPositions(), snapshot.shortPositions(),
                snapshot.openOrders.size(), snapshot.buyOrders(), snapshot.sellOrders(),
                snapshot.limitOrders(), snapshot.stopOrders()));

        if (snapshot.hasAssets()) {
            b.append("<div class='section'>Активы USD-M</div>");
            b.append("<table class='tbl'>");
            for (AccountAssetSnapshot asset : snapshot.assets) {
                b.append(String.format(Locale.US, """
                        <tr>
                          <td><b>%s</b></td>
                          <td>wallet %s<br>margin %s · free %s</td>
                        </tr>
                        """,
                        escape(asset.asset),
                        money(asset.walletBalance, asset.asset),
                        money(asset.marginBalance, asset.asset),
                        money(asset.availableBalance, asset.asset)));
            }
            b.append("</table>");
        }

        b.append("<div class='section'>Открытые сделки</div>");
        if (snapshot.openPositions.isEmpty()) {
            b.append("<div class='empty'>Открытых позиций нет.</div>");
        } else {
            b.append("<table class='tbl'>");
            for (OpenPositionSnapshot p : snapshot.openPositions) {
                String color = p.longSide() ? "#26A69A" : "#EF5350";
                String pnlColor = p.unrealizedPnl >= 0.0 ? "#26A69A" : "#EF5350";
                String side = p.longSide() ? "LONG" : "SHORT";
                String symbol = escape(p.symbol);
                String entry = raw(p.entryPrice);
                String qty = raw(p.quantity);
                b.append(String.format(Locale.US, """
                        <tr>
                          <td><b style='color:%s;'>%s</b></td>
                          <td><a href='pos:%s:%s'><b>%s</b></a> · qty %s<br>entry %s · mark %s · pnl <b style='color:%s;'>%s</b><br><a href='pos:%s:%s'>График</a> · <a href='close:%s:%s:%s:%s'>Закрыть</a></td>
                        </tr>
                        """,
                        color, escape(p.sideLabel()), symbol, entry, symbol, qty(p.quantity),
                        price(p.entryPrice), price(p.markPrice), pnlColor, money(p.unrealizedPnl, snapshot.currency),
                        symbol, entry, symbol, side, entry, qty));
            }
            b.append("</table>");
        }

        b.append("<div class='section'>Открытые заявки</div>");
        if (snapshot.openOrders.isEmpty()) {
            b.append("<div class='empty'>Активных лимитных/стоп-заявок нет.</div>");
        } else {
            b.append("<table class='tbl'>");
            for (OpenOrderSnapshot o : snapshot.openOrders) {
                String color = o.side == app.execution.fix.ExecutionOrder.Side.BUY ? "#26A69A" : "#EF5350";
                String clientId = escape(o.clientOrderId);
                String symbol = escape(o.symbol);
                b.append(String.format(Locale.US, """
                        <tr>
                          <td><b style='color:%s;'>%s</b></td>
                          <td><a href='pos:%s:0'><b>%s</b></a> · %s · rem %s<br>price %s%s<br><a href='cancel:%s:%s'>Отменить заявку</a></td>
                        </tr>
                        """,
                        color, o.side.name(), symbol, symbol, o.type.name(), qty(o.remainingQuantity),
                        price(o.price), o.stopPrice > 0.0 ? " · stop " + price(o.stopPrice) : "",
                        clientId, symbol));
            }
            b.append("</table>");
        }

        b.append("<div class='note'>Paper local читается из execution journal и не синхронизируется с Binance demo. Testnet / Demo и Live читаются с биржи через account/open-orders endpoints.</div>");
        return html(b.toString());
    }

    private String chartInsightText(ChartInsight insight) {
        if (insight == null) return emptyChartInsight();
        String color = htmlColor(insightToneColor(insight.tone));
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, """
                <div class='hero'>
                  <div class='kicker'>Текущий график · %s</div>
                  <div class='title' style='color:%s;'>%s</div>
                  <div class='text'>%s</div>
                </div>
                <div class='score'>
                  <div class='kicker'>Синхронный анализ</div>
                  <div class='scoreNum' style='color:%s;'>%d/100</div>
                  <div class='text'><b>%s</b></div>
                </div>
                """,
                escape(insight.marketLabel), color, escape(insight.heading),
                escape(insight.summary), color, insight.score, escape(insight.action)));

        if (!insight.metrics.isEmpty()) {
            b.append("<div class='section'>Состояние</div><table class='tbl'>");
            for (ChartInsight.Metric metric : insight.metrics) {
                b.append(String.format("<tr><td>%s</td><td><b style='color:%s;'>%s</b></td></tr>",
                        escape(metric.label), htmlColor(insightToneColor(metric.tone)), escape(metric.value)));
            }
            b.append("</table>");
        }

        b.append(listBlock("Что подтверждает", insight.confirmations, "good"));
        b.append(listBlock("Что может сломать сценарий", insight.risks, "bad"));
        b.append(listBlock("Следующие действия", insight.nextSteps, ""));

        if (!insight.levels.isEmpty()) {
            b.append("<div class='section'>Уровни на графике</div><table class='tbl'>");
            int shown = 0;
            for (ChartInsight.LevelMarker level : insight.levels) {
                if (shown++ >= 10) break;
                b.append(String.format(Locale.US,
                        "<tr><td>%s</td><td><b style='color:%s;'>%.4f</b></td></tr>",
                        escape(level.label), htmlColor(levelColor(level.type)), level.price));
            }
            b.append("</table>");
        }

        b.append("<div class='note'>Эта вкладка пересчитывается при смене пары, HTF, графического таймфрейма и после открытия монеты из радара.</div>");
        return html(b.toString());
    }

    private String listBlock(String title, List<String> items, String cssClass) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append("<div class='section'>").append(escape(title)).append("</div><ul>");
        for (String item : items) {
            b.append("<li");
            if (cssClass != null && !cssClass.isBlank()) b.append(" class='").append(cssClass).append("'");
            b.append(">").append(escape(item)).append("</li>");
        }
        b.append("</ul>");
        return b.toString();
    }

    private String emptyChartInsight() {
        if (I18n.english()) {
            return html("<div class='empty'>Current chart analysis will appear after candles are refreshed.</div>");
        }
        return html("<div class='empty'>Анализ текущего графика появится после обновления свечей.</div>");
    }

    private void handlePositionLink(String description) {
        if (description == null || positionActionListener == null) return;
        String[] parts = description.split(":");
        if (parts.length < 2) return;
        String action = parts[0];
        if ("pos".equals(action) && parts.length >= 3) {
            positionActionListener.openPositionChart(parts[1], parse(parts[2]));
        } else if ("close".equals(action) && parts.length >= 5) {
            positionActionListener.closePosition(parts[1], "LONG".equalsIgnoreCase(parts[2]), parse(parts[3]), parse(parts[4]));
        } else if ("cancel".equals(action) && parts.length >= 3) {
            positionActionListener.cancelOrder(parts[1], parts[2]);
        }
    }

    private String planText(AnalysisResult r) {
        if (r.plan == null) {
            if (I18n.english()) {
                return html("""
                        <div class='hero muted'>
                          <div class='kicker'>No active trade</div>
                          <div class='title'>Waiting for a complete smart-money scenario</div>
                          <div class='text'>The engine does not create entries from a simple OB/FVG touch.</div>
                          <ul>
                            <li>liquidity sweep</li>
                            <li>displacement</li>
                            <li>CHoCH/BOS</li>
                            <li>mitigation back into the zone</li>
                          </ul>
                        </div>
                        """ + scoreBlock(r) + factorsBlock(r));
            }
            return html("""
                    <div class='hero muted'>
                      <div class='kicker'>Сделки сейчас нет</div>
                      <div class='title'>Ждем полный smart-money сценарий</div>
                      <div class='text'>Вход не строится от простого касания OB/FVG.</div>
                      <ul>
                        <li>снятие ликвидности</li>
                        <li>displacement</li>
                        <li>CHoCH/BOS</li>
                        <li>возврат в зону</li>
                      </ul>
                    </div>
                    """ + scoreBlock(r) + factorsBlock(r));
        }

        TradePlan p = r.plan;
        String side = p.longSide ? "ЛОНГ" : "ШОРТ";
        String color = p.longSide ? hex(BULL) : hex(BEAR);
        String statusColor = p.entryConfirmed ? hex(BULL) : hex(YELLOW);
        String status = p.entryConfirmed ? "подтвержден" : "continuation-сценарий";
        double riskPct = Math.abs(p.entry - p.stop) / p.entry * 100.0;
        double tp3Pct = Math.abs(p.tp3 - p.entry) / p.entry * 100.0;
        double rr = p.rr3();
        double weightedReward = p.weightedRewardR(0.40, 0.40, 0.20);
        double breakEven = weightedReward <= 0.0 ? 100.0 : 100.0 / (1.0 + weightedReward);
        double evR = p.expectedValueR(app.backtest.RadarBacktestStats.calibratedWinRate01(r, p));
        String rating = p.qualityRating();
        String ratingColor = "A+".equals(rating) ? hex(BULL) : "A".equals(rating) ? hex(BULL)
                : "B".equals(rating) ? hex(YELLOW) : hex(MUTED);
        String mitigationStr = p.zone != null
                ? (int) Math.round(p.zone.mitigationDepth * 100) + "%"
                : "—";

        return html(String.format("""
                <div class='hero'>
                  <div class='kicker'>Торговый план</div>
                  <div class='title' style='color:%s;'>%s · %s · SMC %d/100
                    &nbsp;<span style='color:%s;font-size:11px;font-weight:700;'>Рейтинг %s</span>
                  </div>
                  <div class='text'>%s</div>
                </div>
                <table class='tbl'>
                  <tr><td>Статус</td><td><b style='color:%s;'>%s</b></td></tr>
                  <tr><td>Тип сетапа</td><td><b>%s</b></td></tr>
                  <tr><td>Цели</td><td>%s</td></tr>
                  <tr><td>Зона</td><td><b>%.4f → %.4f</b></td></tr>
                  <tr><td>Вход</td><td><b class='accent'>%.4f</b></td></tr>
                  <tr><td>Стоп</td><td><b class='bad'>%.4f</b></td></tr>
                  <tr><td>Риск</td><td><b class='bad'>%.2f%%</b></td></tr>
                  <tr><td>TP3 / R:R</td><td><b class='good'>+%.2f%% · 1:%.2f</b></td></tr>
                  <tr><td>Мат. профиль</td><td><b class='warn'>weighted %.2fR · BE %.1f%% · EV %.2fR</b></td></tr>
                  <tr><td>Mitigation зоны</td><td><b>%s</b></td></tr>
                </table>
                <div class='section'>Тейки</div>
                <div class='chips'>
                  <span>TP1 %.4f · 40%%</span>
                  <span>TP2 %.4f · 40%%</span>
                  <span>TP3 %.4f · 20%%</span>
                </div>
                <div class='section'>Логика входа</div>
                <table class='tbl'>
                  <tr><td>Sweep</td><td>%s</td></tr>
                  <tr><td>CHoCH/BOS</td><td>%s</td></tr>
                  <tr><td>Зона</td><td>%s · displacement %d/100</td></tr>
                </table>
                %s
                %s
                """,
                color, side, p.zone.type, p.confidence,
                ratingColor, rating,
                translateScenario(p.scenario),
                statusColor, status,
                translateSetupType(p.setupType), translateTargetModel(p),
                p.zone.bottom, p.zone.top, p.entry, p.stop, riskPct, tp3Pct, rr,
                weightedReward, breakEven, evR,
                mitigationStr,
                p.tp1, p.tp2, p.tp3,
                p.sweepIndex >= 0 ? "бар " + p.sweepIndex : "нет",
                p.chochIndex >= 0 ? "бар " + p.chochIndex : "нет",
                translateQuality(p.zone.quality), p.zone.displacementScore,
                ratingLegend(), factorsBlock(r)));
    }

    private String liquidityText(AnalysisResult r) {
        StringBuilder b = new StringBuilder();
        b.append(scoreBlock(r));
        b.append(derivativesBlock(r));
        b.append("<div class='section'>Buy-side liquidity (стопы шортистов)</div>");
        b.append(levels(r, true));
        b.append("<div class='section'>Sell-side liquidity (стопы лонгистов)</div>");
        b.append(levels(r, false));
        b.append("<div class='section'>Контекст</div>");
        b.append("<div class='text'>").append(escape(translateNarrative(r.narrative))).append("</div>");
        b.append(deviationBlock(r));
        b.append(imbalanceBlock(r));
        b.append(eventRiskBlock(r));
        if (r.marketRegime != null) {
            b.append("<div class='note'>Режим рынка: <b>")
                    .append(escape(translateRegime(r.marketRegime.label())))
                    .append("</b> · score ")
                    .append(r.marketRegime.score)
                    .append("/100</div>");
        }
        b.append(factorsBlock(r));
        return html(b.toString());
    }

    private String orderFlowText(AnalysisResult r) {
        if (r.orderFlowContext == null || r.orderFlowContext.candles.isEmpty()) {
            return html("<div class='empty'>Order Flow пока недоступен.</div>");
        }
        app.orderflow.OrderFlowContext of = r.orderFlowContext;
        FootprintCandle last = of.candles.get(of.candles.size() - 1);
        String source = last.tickBacked
                ? "тик/aggTrades"
                : of.tickBackedCandles > 0
                ? "OHLCV proxy (тики есть в " + of.tickBackedCandles + " свечах истории)"
                : "OHLCV proxy";
        String sourceNote = last.tickBacked
                ? "Текущая свеча рассчитана по реальным aggTrades; buyerMaker разделяет агрессивные покупки/продажи."
                : "Реальный footprint строится по тикам/агрессивным сделкам; proxy режим оценивает поток по OHLCV, телу и закрытию свечи.";
        String deltaColor = last.delta >= 0 ? "#26A69A" : "#EF5350";
        StringBuilder b = new StringBuilder();
        b.append(String.format("""
                <div class='hero'>
                  <div class='kicker'>Footprint / Order Flow / CVD</div>
                  <div class='title' style='color:%s;'>Delta %.0f · CVD %.0f</div>
                  <div class='text'>Источник: <b>%s</b>. %s</div>
                </div>
                <table class='tbl'>
                  <tr><td>Buy volume</td><td><b style='color:#26A69A;'>%.0f</b></td></tr>
                  <tr><td>Sell volume</td><td><b style='color:#EF5350;'>%.0f</b></td></tr>
                  <tr><td>Delta</td><td><b style='color:%s;'>%.0f · %.1f%%</b></td></tr>
                  <tr><td>CVD</td><td><b>%.0f</b></td></tr>
                  <tr><td>Imbalance</td><td><b>%.2fx</b></td></tr>
                  <tr><td>Absorption</td><td>%s</td></tr>
                  <tr><td>Score</td><td><b style='color:#FFB300;'>%d/100</b></td></tr>
                </table>
                """, deltaColor, last.delta, of.cumulativeDelta, source, sourceNote,
                last.buyVolume, last.sellVolume, deltaColor, last.delta, last.deltaPercent * 100.0,
                of.cumulativeDelta, last.imbalanceRatio, last.absorption ? "да" : "нет", of.score));

        b.append("<div class='section'>Последние свечи</div><table class='tbl'>");
        int from = Math.max(0, of.candles.size() - 8);
        for (int i = of.candles.size() - 1; i >= from; i--) {
            FootprintCandle fp = of.candles.get(i);
            String color = fp.delta >= 0 ? "#26A69A" : "#EF5350";
            b.append(String.format("<tr><td>#%d</td><td><b style='color:%s;'>delta %.0f</b> · CVD %.0f · %.1f%%</td></tr>",
                    fp.index, color, fp.delta, fp.cumulativeDelta, fp.deltaPercent * 100.0));
        }
        b.append("</table>");
        b.append(orderBookBlock(r));
        b.append("<div class='note'>На графике при достаточном зуме внутри свечи появляются footprint-bins: зеленые бары справа показывают aggressive buy, красные слева - aggressive sell.</div>");
        return html(b.toString());
    }

    private String derivativesBlock(AnalysisResult r) {
        if (r.derivativesContext == null || !r.derivativesContext.available) {
            return "<div class='note'>Futures hidden liquidity: OI/Funding пока недоступны или API не ответил.</div>";
        }
        app.derivatives.DerivativesContext d = r.derivativesContext;
        String fundingColor = d.fundingRate < 0.0 ? "#26A69A" : d.fundingRate > 0.0 ? "#EF5350" : "#D1D4DC";
        String oiColor = d.openInterestChangePct >= 0.0 ? "#26A69A" : "#EF5350";
        return String.format("""
                <div class='section'>Hidden liquidity: Open Interest / Funding</div>
                <table class='tbl'>
                  <tr><td>Open Interest</td><td><b>%.0f</b></td></tr>
                  <tr><td>OI change</td><td><b style='color:%s;'>%.2f%%</b></td></tr>
                  <tr><td>Funding</td><td><b style='color:%s;'>%.4f%%</b></td></tr>
                  <tr><td>State</td><td><b>%s</b> · score %d/100</td></tr>
                </table>
                """, d.openInterest, oiColor, d.openInterestChangePct,
                fundingColor, d.fundingRate * 100.0, escape(d.state), d.score);
    }

    private String orderBookBlock(AnalysisResult r) {
        if (r.orderBookContext == null || !r.orderBookContext.available) {
            return "<div class='note'>DOM/L2 order book пока недоступен или API не ответил.</div>";
        }
        app.orderbook.OrderBookContext ob = r.orderBookContext;
        String ratioColor = ob.imbalanceRatio >= 1.25 ? "#26A69A" : ob.imbalanceRatio <= 0.80 ? "#EF5350" : "#D1D4DC";
        return String.format("""
                <div class='section'>L2 DOM Imbalance / Spoofing</div>
                <table class='tbl'>
                  <tr><td>Bid notional</td><td><b style='color:#26A69A;'>%.0f</b></td></tr>
                  <tr><td>Ask notional</td><td><b style='color:#EF5350;'>%.0f</b></td></tr>
                  <tr><td>Bid/Ask ratio</td><td><b style='color:%s;'>%.2fx</b></td></tr>
                  <tr><td>Largest bid wall</td><td>%.4f · %.0f</td></tr>
                  <tr><td>Largest ask wall</td><td>%.4f · %.0f</td></tr>
                  <tr><td>Spoofing</td><td>%s</td></tr>
                  <tr><td>Score</td><td><b style='color:#FFB300;'>%d/100</b></td></tr>
                </table>
                """, ob.bidNotional, ob.askNotional, ratioColor, ob.imbalanceRatio,
                ob.largestBidWallPrice, ob.largestBidWallNotional,
                ob.largestAskWallPrice, ob.largestAskWallNotional,
                ob.spoofingDetected ? escape(ob.spoofingSide) : "нет", ob.score);
    }

    private String levels(AnalysisResult r, boolean buySide) {
        if (r.liquidityMap == null) return "<div class='empty'>Нет уровней</div>";
        java.util.List<LiquidityLevel> levels = buySide ? r.liquidityMap.buySide : r.liquidityMap.sellSide;
        if (levels.isEmpty()) return "<div class='empty'>Нет уровней</div>";
        StringBuilder b = new StringBuilder("<table class='tbl'>");
        int shown = 0;
        for (LiquidityLevel level : levels) {
            if (shown++ >= 7) break;
            String status = level.swept ? "снята" : "не снята";
            String color = level.swept ? "#787B86" : (buySide ? "#EF5350" : "#26A69A");
            b.append(String.format("<tr><td><b style='color:%s;'>%.4f</b></td><td>%s · %s · %s</td></tr>",
                    color, level.price, translateStrength(level.strengthLabel()), translateScope(level.scope), status));
        }
        b.append("</table>");
        return b.toString();
    }

    private String deviationBlock(AnalysisResult r) {
        if (r == null || r.deviationContext == null || !r.deviationContext.available) {
            return "<div class='note'>Deviation / smart-money trap пока не подтвержден.</div>";
        }
        var d = r.deviationContext;
        String color = d.score >= 70 ? "#26A69A" : d.score >= 50 ? "#FFB300" : "#787B86";
        return String.format(Locale.US, """
                <div class='section'>Deviation / Smart Money</div>
                <table class='tbl'>
                  <tr><td>Тип</td><td><b style='color:%s;'>%s</b></td></tr>
                  <tr><td>Направление</td><td>%s</td></tr>
                  <tr><td>Score</td><td><b style='color:%s;'>%d/100</b></td></tr>
                  <tr><td>Range</td><td>%.4f -> %.4f</td></tr>
                  <tr><td>Глубина</td><td>%.2f ATR · %.2f%%</td></tr>
                  <tr><td>Возврат / displacement</td><td>%s / %s</td></tr>
                  <tr><td>VWAP / mean</td><td>%.2f%% · %.2f ATR</td></tr>
                </table>
                """,
                color, escape(d.label), escape(d.reversalDirection.toString()),
                color, d.score, d.rangeLow, d.rangeHigh, d.atrMultiple, d.deviationPct,
                d.returnedInside ? "да" : "нет", d.displacementAfterReturn ? "да" : "нет",
                d.vwapDeviationPct, d.meanDeviationAtr);
    }

    private String imbalanceBlock(AnalysisResult r) {
        if (r == null || r.imbalanceContext == null || !r.imbalanceContext.available) {
            return "<div class='note'>Imbalance контекст пока не подтвержден.</div>";
        }
        var i = r.imbalanceContext;
        String color = i.score >= 70 ? "#26A69A" : i.score >= 50 ? "#FFB300" : "#787B86";
        return String.format(Locale.US, """
                <div class='section'>Imbalance</div>
                <table class='tbl'>
                  <tr><td>Направление</td><td><b style='color:%s;'>%s</b></td></tr>
                  <tr><td>Score</td><td><b style='color:%s;'>%d/100</b></td></tr>
                  <tr><td>Candle / FVG</td><td>%d / %d · fill %.1f%%</td></tr>
                  <tr><td>Delta / DOM</td><td>%d / %d</td></tr>
                  <tr><td>Liquidity</td><td>%d</td></tr>
                </table>
                """, color, escape(i.direction.toString()), color, i.score,
                i.candleImbalanceScore, i.fvgEfficiencyScore, i.fvgFilledPercent,
                i.deltaImbalanceScore, i.bookImbalanceScore, i.liquidityImbalanceScore);
    }

    private String eventRiskBlock(AnalysisResult r) {
        if (r == null || r.eventRiskContext == null || !r.eventRiskContext.available) return "";
        var e = r.eventRiskContext;
        String color = e.blocksNewRisk() ? "#EF5350" : e.score >= 55 ? "#FFB300" : "#26A69A";
        return String.format(Locale.US, """
                <div class='section'>Event / Fundamental Risk</div>
                <div class='note' style='border-left-color:%s;'><b style='color:%s;'>%d/100</b> · %s</div>
                """, color, color, e.score, escape(e.message));
    }

    private String zoneText(Zone z, double currentPrice) {
        String color = z.bullish ? "#26A69A" : "#EF5350";
        String status = z.mitigated ? "отработана" : "активна";
        double dist = Math.abs(currentPrice - z.mid()) / currentPrice * 100.0;
        StringBuilder reasons = new StringBuilder();
        for (String reason : z.reasons) {
            reasons.append("<li>").append(escape(translateReason(reason))).append("</li>");
        }
        return html(String.format("""
                <div class='hero'>
                  <div class='kicker'>Детали зоны</div>
                  <div class='title' style='color:%s;'>%s · %s</div>
                  <div class='text'>Статус: <b>%s</b> · расстояние до цены: <b>%.2f%%</b></div>
                </div>
                <table class='tbl'>
                  <tr><td>Верх</td><td><b>%.4f</b></td></tr>
                  <tr><td>Низ</td><td><b>%.4f</b></td></tr>
                  <tr><td>Середина</td><td><b>%.4f</b></td></tr>
                  <tr><td>Score</td><td><b style='color:#FFB300;'>%d/100</b></td></tr>
                  <tr><td>SMC model</td><td>%s</td></tr>
                  <tr><td>Target liq</td><td>%s</td></tr>
                  <tr><td>Displacement</td><td>%d/100</td></tr>
                  <tr><td>Ликвидность</td><td>%s</td></tr>
                  <tr><td>Качество</td><td>%s</td></tr>
                </table>
                <div class='section'>Почему зона важна</div>
                <ul>%s</ul>
                <div class='note'>Повторные входы после полной mitigation рискованнее. Идеальный вход: после sweep, displacement и подтверждения структуры на младшем ТФ.</div>
                """,
                color, z.type, z.bullish ? "бычья" : "медвежья", status, dist,
                z.top, z.bottom, z.mid(), z.score,
                escape(z.mechanicLabel()),
                z.targetLiquidity == null || z.targetLiquidity.isBlank() ? "--" : escape(z.targetLiquidity),
                z.displacementScore,
                z.relatedLiquidity == null || z.relatedLiquidity.isEmpty() ? "нет привязки" : escape(z.relatedLiquidity),
                translateQuality(z.quality), reasons));
    }

    private String scoreBlock(AnalysisResult r) {
        String regime = r.marketRegime != null
                ? " · " + translateRegime(r.marketRegime.label()) + " " + r.marketRegime.score + "/100"
                : "";
        String planNote = r.plan == null ? "Watchlist-состояние. Нет полного сценария."
                : "Сценарий: " + translateSetupType(r.plan.setupType) + " · рейтинг " + r.plan.qualityRating();
        return String.format("""
                <div class='score'>
                  <div>
                    <div class='kicker'>Общая оценка SMC%s</div>
                    <div class='scoreNum' style='color:%s;'>%d/100</div>
                  </div>
                  <div class='text'>%s</div>
                </div>
                """, regime, htmlColor(scoreColor(r.smcScore)), r.smcScore, planNote);
    }

    private String ratingLegend() {
        return """
                <div class='section'>Справка: рейтинги и EV</div>
                <table class='tbl'>
                  <tr>
                    <td><b style='color:#26a641;'>A+</b></td>
                    <td>Уверенность ≥78, подтверждённый вход (sweep+CHoCH+митигация), R:R ≥2.5, displacement ≥65.
                        Высококачественный сетап.</td>
                  </tr>
                  <tr>
                    <td><b style='color:#3fb950;'>A</b></td>
                    <td>Уверенность ≥65, все три условия входа подтверждены.
                        Стандартный institutional сетап.</td>
                  </tr>
                  <tr>
                    <td><b style='color:#d29922;'>B</b></td>
                    <td>Уверенность ≥52, частичное подтверждение.
                        Допустимо с уменьшенным размером позиции.</td>
                  </tr>
                  <tr>
                    <td><b style='color:#8b949e;'>C</b></td>
                    <td>Слабый сигнал. Только watchlist — в сделку не входить.</td>
                  </tr>
                </table>
                <div class='note'>
                  <b>EV (ожидаемая ценность в R)</b> — математическое ожидание на сделку при разбивке
                  40% на TP1 · 40% на TP2 · 20% на TP3 и риске 1R.<br/>
                  Формула: <i>win_rate × weighted_R − (1 − win_rate) × 1R</i><br/>
                  win_rate аппроксимируется из уверенности: min(75%, confidence × 0.80 %).<br/>
                  <b>Положительный EV = математически выгодная сделка.</b>
                </div>
                <div class='note'>
                  <b>Метки зон на графике</b> — [A] = score ≥80, [B] = score ≥65.
                  Толщина рамки зоны отражает её качество.
                </div>
                """;
    }

    private String factorsBlock(AnalysisResult r) {
        StringBuilder b = new StringBuilder();
        if (r.scoreFactors.isEmpty() && r.warnings.isEmpty()) {
            b.append("<div class='section'>Факторы</div><ul><li>Недостаточно подтверждений для сделки.</li></ul>");
            return b.toString();
        }
        // Подтверждения
        if (!r.scoreFactors.isEmpty()) {
            b.append("<div class='section'>✓ Подтверждения (").append(r.scoreFactors.size()).append(")</div><ul>");
            for (String factor : r.scoreFactors) {
                b.append("<li class='good'>").append(escape(translateFactor(factor))).append("</li>");
            }
            b.append("</ul>");
        }
        // Предупреждения
        if (!r.warnings.isEmpty()) {
            b.append("<div class='section'>⚠ Риски и ограничения (").append(r.warnings.size()).append(")</div><ul>");
            for (String warning : r.warnings) {
                b.append("<li class='bad'>").append(escape(translateWarning(warning))).append("</li>");
            }
            b.append("</ul>");
        }
        return b.toString();
    }

    private String helpText() {
        if (I18n.english()) return helpTextEnglish();
        return html("""
                <div class='hero'>
                  <div class='kicker'>Как читать панель</div>
                  <div class='title'>Сигнал строится не от зоны, а от поведения цены</div>
                  <div class='text'>Движок ищет, где лежит ликвидность, было ли снятие стопов, появился ли displacement и подтвердился ли сдвиг структуры.</div>
                </div>
                <div class='section'>Метки на графике</div>
                <ul>
                  <li><b>BSL</b> - buy-side liquidity, стопы над максимумами.</li>
                  <li><b>SSL</b> - sell-side liquidity, стопы под минимумами.</li>
                  <li><b>SW</b> - sweep, снятие ликвидности с возвратом цены обратно.</li>
                  <li><b>IDM</b> - inducement, слабая структура/ловушка перед движением.</li>
                  <li><b>Стрелки</b> - displacement, импульс с расширением ATR/тела/объема.</li>
                </ul>
                <div class='section'>Фильтр входа</div>
                <div class='note'>Лучший сценарий: sweep -> displacement -> CHoCH/BOS -> mitigation в OB/FVG -> подтверждение. Если этой цепочки нет, панель показывает наблюдение, а не сделку.</div>
                <div class='section'>Анализ графика</div>
                <div class='note'>Вкладка Анализ синхронизируется с текущей парой, HTF и таймфреймом графика. Она сводит premium/discount, MTF структуру, ближайшую зону, режим рынка, deviation, imbalance и event risk в понятный статус: что подтверждает сценарий, что мешает и что делать дальше.</div>
                <div class='note'><b>Live-режим</b>: цена, расстояние до entry/SL/TP, premium/discount и статус сценария обновляются по WebSocket примерно раз в секунду. Полный SMC-пересчет зон/структуры запускается после закрытия свечи или вручную через Обновить.</div>
                <div class='section'>Order Flow</div>
                <div class='note'>Вкладка Поток показывает delta, CVD, imbalance и absorption. Если в Data Lake или live-догрузке есть aggTrades, CVD считается по сделкам; иначе панель честно показывает OHLCV proxy.</div>
                <div class='section'>Инфра и Data Lake</div>
                <div class='note'><b>Инфра</b> запускает ML service, Ticker plant и открывает логи. ML service дает адаптивные веса факторов, а Ticker plant копит aggTrades/depth/OI/funding в локальный Data Lake для реального Потока, Tick/DataLake бэктеста и ML-обучения.</div>
                <div class='note'><b>Risk</b> в Инфре управляет max order/risk, daily loss kill switch, orphan-reconcile и auto SL/TP после partial fills. <b>Journal</b> показывает <code>execution-journal/events.jsonl</code>, а <b>Data quality</b> проверяет stale/gaps/coverage по свечам и Data Lake.</div>
                <div class='note'><b>Аккаунт</b> внизу открывает профиль Binance/Bybit/BingX. Trade ticket открывается с графика кнопками Long/Short; entry, SL и TP подтягиваются из текущих данных, а перед отправкой их можно вручную поправить. Для Binance LIVE реальные заявки не уйдут, пока в профиле аккаунта не включен тумблер LIVE orders.</div>
                <div class='note'><b>Логи сделки</b> в trade ticket показывают всю цепочку: аккаунт, параметры формы, риск-настройки, gateway, установку плеча, отправку заявки и ответ биржи. Если сделка не открылась, смотри последнюю строку ERROR/FAILED/BLOCKED.</div>
                <div class='section'>План торговли</div>
                <div class='note'>Кнопка появляется снизу, когда на графике есть линия ВХОД. В окне плана можно выбрать режим Мягкий/Средний/Точный и запустить цепочку: перепроверка свечей -> ML -> быстрый бэктест -> Tick/DataLake -> итоговый прогноз.</div>
                <div class='section'>Прогноз</div>
                <div class='note'>Итоговый прогноз - это не только SMC. Он учитывает ML-веса, DOM, OI/funding, order flow и бэктест. Если статистика плохая, свечи устарели или импульс идет против сделки, максимальный score ограничивается. История в плане позволяет вернуться к прошлым отчетам.</div>
                <div class='note'><b>Время до TP/SL</b> строится из текущей волатильности и, когда есть данные, из backtest evidence. Live WebSocket aggTrades сохраняются локально и могут использоваться в Tick/WebSocket бэктесте, поэтому оценка постепенно становится менее эвристической.</div>
                <div class='note'><b>Backtest Intelligence</b> читает все CSV из папки <code>backtests</code>, показывает статистику по монетам, ТФ, сетапам, зонам, режимам и фильтрам. Финальный прогноз использует накопленную CSV-историю по текущей монете/ТФ/направлению как дополнительный статистический фильтр.</div>
                <div class='note'><b>Risk:Reward</b> считается как математический профиль сделки. Минимум - около 1:1, рабочая середина - 1:2, сильный профиль - около 1:3. Слишком дальние цели ограничиваются, потому что жадный TP часто снижает реализацию и возвращает сделку в стоп.</div>
                <div class='note'><b>Deviation / Smart Money</b> ищет ложный выход за диапазон/Asia/session high-low, sweep liquidity, возврат внутрь, displacement после возврата, premium/discount и отклонение от VWAP/mean. Радар может использовать это как отдельный фильтр smart-money trap.</div>
                <div class='note'><b>Imbalance</b> сводит candle body/range, FVG efficiency, delta, DOM и liquidity imbalance в общий score направления. Если imbalance идет против сделки, прогноз режет оценку.</div>
                <div class='note'><b>Event/Fundamental Risk</b> работает как защитный фильтр: макро-окна 08:30/14:00 NY, cash open, аномальный импульс свечи, funding crowding и OI flush. Это не предсказание новости, а запрет на лишний риск.</div>
                <div class='note'><b>Настройки прозрачности</b> разделены на зоны, подписи и маркеры. Заливку FVG/OB/OTE можно сделать почти невидимой, а подписи оставить читаемыми; сами подписи больше не темнеют вместе с зоной.</div>
                """);
    }

    private String helpTextEnglish() {
        return html("""
                <div class='hero'>
                  <div class='kicker'>How to read the panel</div>
                  <div class='title'>Signals are built from price behavior, not from a zone touch</div>
                  <div class='text'>The engine tracks liquidity, stop runs, displacement, structure shifts, mitigation, order flow, derivatives context and risk filters.</div>
                </div>
                <div class='section'>Chart labels</div>
                <ul>
                  <li><b>BSL</b> - buy-side liquidity, stops above highs.</li>
                  <li><b>SSL</b> - sell-side liquidity, stops below lows.</li>
                  <li><b>SW</b> - liquidity sweep with price reclaim.</li>
                  <li><b>IDM</b> - inducement, weak structure or trap before a move.</li>
                  <li><b>Arrows</b> - displacement with ATR/body/volume expansion.</li>
                </ul>
                <div class='section'>Entry filter</div>
                <div class='note'>Best scenario: sweep -> displacement -> CHoCH/BOS -> mitigation into OB/FVG -> confirmation. Without that chain, the panel treats the setup as observation, not execution.</div>
                <div class='section'>Chart analysis</div>
                <div class='note'>The Analysis tab is synchronized with the current symbol, HTF and chart timeframe. It combines premium/discount, MTF structure, nearest zone, market regime, deviation, imbalance and event risk into a practical status.</div>
                <div class='note'><b>Live mode</b>: price, distance to entry/SL/TP, premium/discount and scenario status update from WebSocket. A full SMC recalculation runs after candle close or manually via Refresh.</div>
                <div class='section'>Order Flow</div>
                <div class='note'>The Flow tab shows delta, CVD, imbalance and absorption. With aggTrades in Data Lake or live preload it uses real trades; otherwise it clearly falls back to an OHLCV proxy.</div>
                <div class='section'>Infra and Data Lake</div>
                <div class='note'><b>Infra</b> starts ML service, ticker plant and opens logs. ML service provides adaptive factor weights; ticker plant stores aggTrades/depth/OI/funding for Flow, Tick/DataLake backtests and learning.</div>
                <div class='note'><b>Risk</b> controls max order/risk, daily loss kill switch, orphan reconciliation and automatic SL/TP after partial fills.</div>
                <div class='note'><b>Account</b> opens Binance/Bybit/BingX profiles. Trade tickets can be opened from chart Long/Short buttons; entry, SL and TP are pulled from current data and can be edited manually.</div>
                <div class='section'>Trade plan and forecast</div>
                <div class='note'>The trade-plan button appears when the chart has an entry line. Forecast combines SMC, ML weights, DOM, OI/funding, order flow, backtest statistics and current data quality.</div>
                <div class='note'><b>Risk:Reward</b> is treated as a mathematical profile: around 1:1 is minimal, 1:2 is workable, and around 1:3 is strong. Overextended targets are capped to avoid unrealistic greed.</div>
                <div class='section'>Settings</div>
                <div class='note'>Use Settings to adjust theme, language, candle colors, wick behavior, scale lock and opacity for zones, labels and markers. Candle colors are saved and applied immediately.</div>
                """);
    }

    private String emptyDetails() {
        if (I18n.english()) {
            return html("<div class='empty'>Double-click a chart zone to see parameters, reasons and mitigation status.</div>");
        }
        return html("<div class='empty'>Дважды кликните по зоне на графике, чтобы увидеть параметры, причины и статус mitigation.</div>");
    }

    private String html(String body) {
        String fg = hex(app.Colors.FG);
        String cardBg = hex(app.Colors.CARD_BG);
        String grid = hex(app.Colors.GRID);
        String muted = hex(app.Colors.MUTED);
        String accent = hex(app.Colors.ACCENT);
        String bull = hex(app.Colors.BULL);
        String bear = hex(app.Colors.BEAR);
        String noteBg = hex(new Color(
            Math.max(10, (int)(app.Colors.FIELD_BG.getRed() * 0.9)),
            Math.max(10, (int)(app.Colors.FIELD_BG.getGreen() * 0.9)),
            Math.max(10, (int)(app.Colors.FIELD_BG.getBlue() * 0.9))
        ));

        return String.format("""
                <html><head><style>
                body { font-family: %s; font-size: 12px; color: %s; margin: 8px; line-height: 1.44; }
                .hero { background: %s; border: 1px solid %s; padding: 10px; margin-bottom: 9px; }
                .score { background: %s; border: 1px solid %s; padding: 10px; margin-bottom: 9px; }
                .kicker { color: %s; font-size: 10px; text-transform: uppercase; letter-spacing: .4px; }
                .title { font-size: 14px; font-weight: 700; color: %s; margin-top: 2px; }
                .text { color: %s; line-height: 1.46; }
                .scoreNum { font-size: 25px; font-weight: 800; }
                .section { color: %s; font-weight: 700; margin-top: 11px; margin-bottom: 5px; }
                .tbl { width: 100%%; color: %s; border-collapse: collapse; }
                .tbl td { padding: 5px 0; border-bottom: 1px solid %s; }
                .tbl td:first-child { color: %s; }
                .chips span { display: block; color: %s; padding: 4px 0; border-bottom: 1px solid %s; }
                .note { color: %s; background: %s; border-left: 3px solid %s; padding: 8px; margin-top: 8px; }
                .badBorder { border-left-color: %s; }
                .empty { color: %s; padding: 10px; }
                .good { color: %s; }
                .bad { color: %s; }
                .warn { color: %s; }
                .accent { color: %s; }
                a { color: %s; text-decoration: none; font-weight: 700; }
                ul { padding-left: 16px; margin-top: 4px; }
                li { margin-bottom: 4px; }
                </style></head><body>
                """,
                UIUtils.HTML_FONT_STACK, fg, cardBg, grid, cardBg, grid, muted, fg, fg, accent, fg, grid, muted,
                fg, grid, fg, noteBg, accent, bear, muted, bull, bear, hex(app.Colors.YELLOW), accent, accent) + body + "</body></html>";
    }

    private String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private String accountProfile(ExchangeAccountSettings settings) {
        if (settings == null) return "Аккаунт";
        if (settings.profileName != null && !settings.profileName.isBlank()) return settings.profileName;
        return settings.mode == ExchangeAccountSettings.Mode.PAPER ? "Paper local" : settings.exchange.label;
    }

    private String money(double value, String currency) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.2f %s", value, currency == null || currency.isBlank() ? "USDT" : currency);
    }

    private String qty(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.6f", Math.abs(value)).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String price(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return "?";
        return String.format(Locale.US, "%.4f", value);
    }

    private String raw(double value) {
        if (!Double.isFinite(value)) return "0";
        return String.format(Locale.US, "%.12f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private double parse(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private Color scoreColor(int score) {
        if (score >= 75) return BULL;
        if (score >= 55) return YELLOW;
        return BEAR;
    }

    private Color insightToneColor(ChartInsight.Tone tone) {
        if (tone == ChartInsight.Tone.BULLISH) return BULL;
        if (tone == ChartInsight.Tone.BEARISH || tone == ChartInsight.Tone.DANGER) return BEAR;
        if (tone == ChartInsight.Tone.WARNING) return YELLOW;
        return CYAN;
    }

    private Color levelColor(ChartInsight.LevelType type) {
        if (type == ChartInsight.LevelType.ENTRY) return new Color(255, 64, 129);
        if (type == ChartInsight.LevelType.STOP || type == ChartInsight.LevelType.ALERT) return BEAR;
        if (type == ChartInsight.LevelType.TARGET) return BULL;
        if (type == ChartInsight.LevelType.EQUILIBRIUM) return YELLOW;
        if (type == ChartInsight.LevelType.ZONE) return CYAN;
        return MUTED;
    }

    private String htmlColor(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private String translateScenario(String s) {
        if (s == null) return "";
        if (s.contains("Sweep -> displacement")) return "Снятие ликвидности -> displacement -> CHoCH -> возврат в зону.";
        if (s.contains("Continuation")) return "Продолжение тренда: HTF/LTF согласованы, BOS подтвержден displacement.";
        return s;
    }

    private String translateNarrative(String s) {
        if (s == null) return "";
        return s.replace("HTF bullish", "HTF бычий")
                .replace("HTF bearish", "HTF медвежий")
                .replace("LTF confirms continuation", "LTF подтверждает продолжение")
                .replace("LTF is pulling back; wait for entry CHoCH", "LTF в откате; ждем entry CHoCH")
                .replace("Mixed structure; require sweep and displacement confirmation", "Структура смешанная; нужен sweep и displacement")
                .replace("Session:", "Сессия:");
    }

    private String translateFactor(String s) {
        if (s == null) return "";
        return s.replace("HTF bias aligned", "HTF bias совпадает с направлением сделки")
                .replace("Recent liquidity sweep detected", "Недавно было снятие ликвидности")
                .replace("body-close confirmed", "подтвержден закрытием тела")
                .replace("Institutional displacement", "Институциональный displacement")
                .replace("Sweep confirmed by displacement", "Sweep подтвержден displacement")
                .replace("High-quality sweep", "Качественное снятие ликвидности")
                .replace("Strong displacement expansion", "Сильное displacement-расширение")
                .replace("Volume confirmation", "Подтверждение объемом")
                .replace("A-grade zone quality", "A-grade качество зоны")
                .replace("Healthy mitigation depth", "Здоровая глубина mitigation")
                .replace("Strong structure shift", "Сильный structure shift")
                .replace("HTF/LTF structure aligned", "HTF/LTF структура синхронизирована")
                .replace("LTF pullback into HTF narrative", "LTF откат в HTF narrative")
                .replace("Strong MTF context", "Сильный MTF контекст")
                .replace("Order flow delta aligned", "Order flow delta совпадает")
                .replace("Derivatives confluence", "Фьючерсный confluence")
                .replace("Derivatives context", "Фьючерсный контекст")
                .replace("DOM imbalance supports setup", "DOM imbalance поддерживает сетап")
                .replace("Spoofing heuristic detected", "Обнаружен spoofing-след")
                .replace("Adaptive weights", "Адаптивные веса")
                .replace("Footprint absorption detected", "Footprint absorption обнаружен")
                .replace("Tick-based footprint data", "Footprint построен по тикам")
                .replace("OHLCV proxy footprint; tick data not loaded", "Footprint proxy по OHLCV, тики не загружены")
                .replace("Active killzone", "Активная killzone")
                .replace("Liquidity confluence", "Есть confluence по ликвидности")
                .replace("HTF bias:", "HTF bias:")
                .replace("LTF structure:", "LTF структура:")
                // Новые фразы из FvgEngine age decay
                .replace("Fresh FVG (within 5 bars)", "Свежий FVG (≤5 баров)")
                .replace("FVG age decay:", "Штраф за возраст FVG:")
                .replace("bars old)", "баров назад)")
                // Фразы из FvgEngine (alignment, volume, compression)
                .replace("Aligned with HTF narrative", "Совпадает с HTF направлением (FVG)")
                .replace("Expansion after volatility compression", "Расширение после сжатия волатильности")
                // Новые фразы из DisplacementEngine
                .replace("Smart-money deviation confluence", "SMC deviation confluence")
                .replace("Watchlist deviation trap", "Watchlist: deviation-ловушка обнаружена")
                // Новые из ScoringEngine
                .replace("Premium/discount alignment is not ideal", "Выравнивание premium/discount не идеально")
                // Фразы из OrderBlockEngine
                .replace("Most significant OB candle by body", "OB выбран по наибольшему телу кандла")
                .replace("Market regime:", "Режим рынка:");
    }

    private String translateWarning(String s) {
        if (s == null) return "";
        return s.replace("No confirmed sweep yet", "Пока нет подтвержденного sweep")
                .replace("No recent liquidity sweep", "Нет свежего снятия ликвидности")
                .replace("Entry CHoCH/BOS not fresh", "CHoCH/BOS для входа не свежий")
                .replace("Weak volume confirmation", "Слабое подтверждение объемом")
                .replace("Sweep lacks displacement follow-through", "После sweep нет сильного displacement")
                .replace("Open interest flushed near entry; impulse fuel may be exhausted", "Open Interest резко упал рядом со входом: топливо импульса могло иссякнуть")
                .replace("DOM imbalance conflicts with setup", "DOM imbalance конфликтует с сетапом")
                .replace("Weak displacement quality", "Слабое качество displacement")
                .replace("Zone quality below institutional threshold", "Качество зоны ниже institutional-порога")
                .replace("Deep mitigation; zone may be exhausted", "Глубокий mitigation, зона может быть истощена")
                .replace("Order flow delta conflicts with setup", "Order flow delta конфликтует с сетапом")
                .replace("Low volatility chop filter active", "Активен фильтр низкой волатильности/chop")
                .replace("HTF bias conflict", "Конфликт с HTF bias")
                // Новые предупреждения
                .replace("Premium/discount alignment is not ideal", "Premium/discount не оптимальны для входа")
                .replace("Deviation direction conflicts with trade", "Направление deviation конфликтует со сделкой")
                .replace("Deviation may be real breakout, not stop-hunt", "Deviation может быть реальным пробоем, а не охотой за стопами")
                .replace("Imbalance direction conflicts with trade", "Imbalance направлен против сделки")
                .replace("Imbalance leans opposite to plan", "Imbalance перевешивает в сторону против плана")
                .replace("Market regime: chop", "Режим рынка: chop — фильтр активен")
                .replace("High-impact event risk blocks aggressive entries", "Высокий событийный риск блокирует агрессивные входы")
                .replace("No confirmed deviation trap", "Нет подтвержденной deviation-ловушки")
                .replace("No clear imbalance confluence", "Нет четкого imbalance-confluence");
    }

    private String translateReason(String s) {
        if (s == null) return "";
        return s.replace("Last opposing candle before displacement", "Последняя противоположная свеча перед displacement")
                .replace("Displacement score", "Оценка displacement")
                .replace("Validated by BOS/CHoCH body close", "Подтверждено BOS/CHoCH закрытием тела")
                .replace("Aligned with HTF bias", "Совпадает с HTF bias")
                .replace("Volume expansion on displacement", "Расширение объема на displacement")
                .replace("Liquidity sweep confirmed before zone", "Перед зоной было подтвержденное снятие ликвидности")
                .replace("Order flow delta supports zone", "Order flow delta поддерживает зону")
                .replace("Order flow delta conflicts with zone", "Order flow delta конфликтует с зоной")
                .replace("Absorption footprint near zone", "Absorption footprint рядом с зоной")
                .replace("Mitigation in progress", "Идет mitigation")
                .replace("Bullish displacement created imbalance", "Бычий displacement создал imbalance")
                .replace("Bearish displacement created imbalance", "Медвежий displacement создал imbalance")
                .replace("FVG size normalized by ATR", "Размер FVG нормализован по ATR")
                // SmartMoneyMechanicsEngine
                .replace("Premium/discount supports direction", "Premium/discount поддерживает направление")
                .replace("Liquidity-to-liquidity target:", "Цель: ликвидность на ликвидность →")
                .replace("Breaker model: sweep followed by opposite structure break", "Breaker Block: sweep → противоположный structure break")
                .replace("Propulsion model: nested zone inside larger imbalance", "Propulsion Block: вложенная зона внутри большего imbalance")
                .replace("Mitigation model: price returned into active zone after structure shift", "Mitigation Block: цена вернулась в зону после structure shift")
                .replace("Liquidity run before setup model", "Снятие ликвидности перед сетапом")
                .replace("Displacement confirms imbalance", "Displacement подтверждает imbalance")
                .replace("Structure shift confirms model", "Structure shift подтверждает модель")
                .replace("OB has nearby FVG imbalance", "OB усилен соседним FVG imbalance")
                .replace("FVG has nearby OB origin", "FVG имеет соседний OB-источник")
                .replace("Full model: liquidity -> displacement -> structure -> mitigation",
                        "Полная модель: ликвидность → displacement → structure → mitigation")
                .replace("Premium/discount is not ideal for direction", "Premium/discount не идеален для данного направления")
                // SmartEntryEngine
                .replace("Sweep: ", "Снятие ликвидности: ")
                .replace("after sweep/displacement", "после sweep/displacement")
                .replace("Price mitigating selected zone", "Цена митигирует выбранную зону");
    }

    private String translateScope(String scope) {
        if ("external".equals(scope)) return "внешняя";
        if ("session".equals(scope)) return "сессионная";
        return "внутренняя";
    }

    private String translateStrength(String strength) {
        if ("strong".equals(strength)) return "сильная";
        if ("medium".equals(strength)) return "средняя";
        return "слабая";
    }

    private String translateQuality(String quality) {
        if ("strong".equals(quality)) return "сильная";
        if ("medium".equals(quality)) return "средняя";
        return "слабая";
    }

    private String translateSetupType(String type) {
        if (type == null) return "";
        return type.replace("killzone_sweep_reversal", "killzone sweep reversal")
                .replace("breaker_block", "Breaker Block")
                .replace("propulsion_block", "Propulsion Block")
                .replace("mitigation_block", "Mitigation Block")
                .replace("ifvg_retest", "IFVG retest")
                .replace("liquidity_displacement_model", "liquidity-displacement model")
                .replace("liquidity_to_liquidity", "liquidity-to-liquidity")
                .replace("sweep_reversal", "sweep reversal")
                .replace("continuation_pullback", "continuation pullback")
                .replace("fvg_mitigation", "FVG mitigation")
                .replace("ob_mitigation", "OB mitigation")
                .replace("range_reaction", "range reaction")
                .replace("smart_money_entry", "smart-money entry");
    }

    private String translateTargetModel(TradePlan plan) {
        if ("liquidity-target".equals(plan.targetModel) && plan.liquidityTargetLabel != null && !plan.liquidityTargetLabel.isBlank()) {
            return "до ликвидности: " + escape(plan.liquidityTargetLabel);
        }
        if ("rr-capped-3r".equals(plan.targetModel)) {
            return "цель ограничена 1:3, чтобы не жадничать";
        }
        return "фиксированные R-цели";
    }

    private String translateRegime(String regime) {
        if ("trend".equals(regime)) return "тренд";
        if ("range".equals(regime)) return "диапазон";
        if ("expansion".equals(regime)) return "расширение";
        if ("chop".equals(regime)) return "chop";
        return regime == null ? "" : regime;
    }

    private String translateSession(String session) {
        if (session == null) return "--";
        return session.replace("London killzone", "Лондон killzone")
                .replace("New York killzone", "Нью-Йорк killzone")
                .replace("NY continuation", "NY продолжение")
                .replace("PM session", "PM сессия")
                .replace("off-killzone", "вне killzone");
    }

    private String translatePhase(String phase) {
        if (phase == null) return "--";
        return phase.replace("range building", "формирование диапазона")
                .replace("manipulation/expansion window", "манипуляция/расширение")
                .replace("continuation", "продолжение")
                .replace("mean reversion risk", "риск возврата к среднему");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

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
        return Math.max(80, visibleRect.height - 80);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    private interface SliderConsumer {
        void accept(int value);
    }

    private interface ToggleConsumer {
        void accept(boolean value);
    }

    private interface ColorConsumer {
        void accept(Color color);
    }

    public interface PositionActionListener {
        void openPositionChart(String symbol, double entryPrice);
        void closePosition(String symbol, boolean longSide, double entryPrice, double quantity);
        void cancelOrder(String clientOrderId, String symbol);
    }

    private static class WrapEditorPane extends JEditorPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            Container parent = getParent();
            if (parent instanceof JViewport viewport && viewport.getWidth() > 0) {
                size.width = viewport.getWidth();
            }
            return size;
        }
    }
}
