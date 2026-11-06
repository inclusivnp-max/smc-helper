package app.ui;

import app.execution.fix.ExchangeAccountSettings;
import app.execution.fix.ExchangeGatewayFactory;
import app.execution.fix.AccountSnapshot;
import app.execution.fix.ExecutionGateway;
import app.execution.fix.ExecutionJournal;
import app.execution.fix.ExecutionOrder;
import app.execution.fix.ExecutionReport;
import app.execution.fix.HftExecutionService;
import app.execution.fix.PaperFixGateway;
import app.execution.fix.RiskGate;
import app.execution.fix.RiskSettings;
import app.logic.BinanceClient;
import app.model.AnalysisResult;
import app.model.TradePlan;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

import static app.Colors.*;

public class TradeTicketDialog extends JDialog {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JFrame parent;
    private final AnalysisResult result;
    private final BinanceClient.Market market;
    private final Runnable onAccountChanged;
    private final Consumer<TradePlan> onTradeAccepted;
    private final JComboBox<String> sideBox = new JComboBox<>(new String[]{"LONG", "SHORT"});
    private final JComboBox<String> orderTypeBox = new JComboBox<>(new String[]{"MARKET", "LIMIT"});
    private final JComboBox<String> tifBox = new JComboBox<>(new String[]{"GTC", "IOC"});
    private final JTextField symbolField = field();
    private final JTextField amountField = field();
    private final JTextField leverageField = field();
    private final JTextField qtyField = field();
    private final JTextField entryField = field();
    private final JTextField stopField = field();
    private final JTextField tp1Field = field();
    private final JTextField tp2Field = field();
    private final JTextField tp3Field = field();
    private final JLabel accountStatus = new JLabel(" ");
    private final JLabel sideBadge = new JLabel(" ");
    private final JLabel previewLabel = new JLabel(" ");
    private final JButton sendButton = new JButton("Открыть сделку");
    private final JTextArea logArea = new JTextArea();
    private boolean sizingUpdate;

    public static void showDialog(JFrame parent, AnalysisResult result,
                                  BinanceClient.Market market, Boolean forcedLongSide) {
        showDialog(parent, result, market, forcedLongSide, null);
    }

    public static void showDialog(JFrame parent, AnalysisResult result,
                                  BinanceClient.Market market, Boolean forcedLongSide,
                                  Runnable onAccountChanged) {
        new TradeTicketDialog(parent, result, market, forcedLongSide, onAccountChanged).setVisible(true);
    }

    private TradeTicketDialog(JFrame parent, AnalysisResult result,
                              BinanceClient.Market market, Boolean forcedLongSide,
                              Runnable onAccountChanged) {
        this(parent, result, market, forcedLongSide, onAccountChanged, null);
    }

    public static void showDialog(JFrame parent, AnalysisResult result,
                                  BinanceClient.Market market, Boolean forcedLongSide,
                                  Runnable onAccountChanged,
                                  Consumer<TradePlan> onTradeAccepted) {
        new TradeTicketDialog(parent, result, market, forcedLongSide, onAccountChanged, onTradeAccepted).setVisible(true);
    }

    private TradeTicketDialog(JFrame parent, AnalysisResult result,
                              BinanceClient.Market market, Boolean forcedLongSide,
                              Runnable onAccountChanged,
                              Consumer<TradePlan> onTradeAccepted) {
        super(parent, "Открыть сделку", false);
        this.parent = parent;
        this.result = result;
        this.market = market == null ? BinanceClient.Market.FUTURES : market;
        this.onAccountChanged = onAccountChanged;
        this.onTradeAccepted = onTradeAccepted;
        setSize(880, 720);
        setMinimumSize(new Dimension(760, 620));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot());
        installSizingListeners();
        prefill(forcedLongSide);
        refreshAccountStatus();
    }

    private JComponent buildRoot() {
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));

        JLabel title = new JLabel("Открытие сделки");
        title.setForeground(FG);
        title.setFont(UIUtils.displayFont(22f));
        JLabel subtitle = new JLabel("Проверь сторону, размер и уровни. Все ошибки отправки появятся справа в журнале.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BorderLayout(12, 0));
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        titleStack.add(title);
        titleStack.add(Box.createVerticalStrut(4));
        titleStack.add(subtitle);
        styleBadge(sideBadge, ACCENT);
        header.add(titleStack, BorderLayout.CENTER);
        header.add(sideBadge, BorderLayout.EAST);

        UIUtils.styleComboBox(sideBox, BG, FG, GRID);
        UIUtils.styleComboBox(orderTypeBox, BG, FG, GRID);
        UIUtils.styleComboBox(tifBox, BG, FG, GRID);

        JPanel mode = formPanel();
        GridBagConstraints modeGc = rowConstraints();
        addRow(mode, modeGc, "Сторона", sideBox);
        addRow(mode, modeGc, "Тип заявки", orderTypeBox);
        addRow(mode, modeGc, "TIF", tifBox);

        JPanel sizing = formPanel();
        GridBagConstraints sizingGc = rowConstraints();
        addRow(sizing, sizingGc, "Символ", symbolField);
        addRow(sizing, sizingGc, "Маржа, USDT", amountField);
        addRow(sizing, sizingGc, "Плечо", leverageField);
        addRow(sizing, sizingGc, "Количество", qtyField);

        JPanel levels = formPanel();
        GridBagConstraints levelsGc = rowConstraints();
        addRow(levels, levelsGc, "Вход", entryField);
        addRow(levels, levelsGc, "Stop Loss", stopField);
        addRow(levels, levelsGc, "TP1", tp1Field);
        addRow(levels, levelsGc, "TP2", tp2Field);
        addRow(levels, levelsGc, "TP3", tp3Field);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(BG);
        logArea.setForeground(FG);
        logArea.setFont(UIUtils.monoFont(11.4f, Font.PLAIN));
        JScrollPane logScroll = new JScrollPane(logArea);
        UIUtils.styleScrollPane(logScroll, GRID);
        logScroll.setPreferredSize(new Dimension(0, 260));

        accountStatus.setFont(UIUtils.labelFont(12.3f));
        previewLabel.setVerticalAlignment(SwingConstants.TOP);
        previewLabel.setForeground(FG);
        previewLabel.setFont(UIUtils.bodyFont(12.4f));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(section("1. Заявка", "Сторона и режим исполнения.", mode));
        left.add(Box.createVerticalStrut(8));
        left.add(section("2. Размер", "Количество пересчитывается от маржи, плеча и входа.", sizing));
        left.add(Box.createVerticalStrut(8));
        left.add(section("3. Уровни", "Для Binance можно вводить цены через запятую.", levels));
        JScrollPane leftScroll = lightScroll(left);

        JPanel accountPanel = new JPanel(new BorderLayout(8, 4));
        accountPanel.setOpaque(false);
        JLabel accountTitle = miniTitle("Аккаунт");
        accountPanel.add(accountTitle, BorderLayout.NORTH);
        accountPanel.add(accountStatus, BorderLayout.CENTER);

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setOpaque(false);
        previewPanel.add(miniTitle("Проверка перед отправкой"), BorderLayout.NORTH);
        previewPanel.add(previewLabel, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        JPanel topRight = new JPanel();
        topRight.setOpaque(false);
        topRight.setLayout(new BoxLayout(topRight, BoxLayout.Y_AXIS));
        topRight.add(section("Статус", "Куда и что будет отправлено.", accountPanel));
        topRight.add(Box.createVerticalStrut(10));
        topRight.add(section("Риск", "Быстрая проверка геометрии сделки.", previewPanel));
        right.add(topRight, BorderLayout.NORTH);
        right.add(section("Журнал", "Успехи, блокировки и ошибки биржи.", logScroll), BorderLayout.CENTER);

        JPanel center = new JPanel(new GridLayout(1, 2, 14, 0));
        center.setOpaque(false);
        center.add(leftScroll);
        center.add(right);

        JButton account = new JButton("Аккаунт");
        JButton close = new JButton("Закрыть");
        UIUtils.stylePrimaryButton(account, FIELD_BG);
        styleTradeButton(sendButton, BULL);
        UIUtils.stylePrimaryButton(close, FIELD_BG);
        account.addActionListener(e -> ExchangeAccountDialog.showDialog(parent, saved -> refreshAccountStatus()));
        sendButton.addActionListener(e -> submit());
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new BorderLayout(10, 0));
        buttons.setOpaque(false);
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftButtons.setOpaque(false);
        leftButtons.add(account);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightButtons.setOpaque(false);
        rightButtons.add(close);
        rightButtons.add(sendButton);
        buttons.add(leftButtons, BorderLayout.WEST);
        buttons.add(rightButtons, BorderLayout.EAST);

        root.add(header, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private void prefill(Boolean forcedLongSide) {
        symbolField.setText(result == null ? "" : result.symbol);
        orderTypeBox.setSelectedItem(forcedLongSide != null ? "MARKET" : "LIMIT");
        orderTypeBox.addActionListener(e -> {
            boolean marketOrder = "MARKET".equals(orderTypeBox.getSelectedItem());
            tifBox.setSelectedItem(marketOrder ? "IOC" : "GTC");
            tifBox.setToolTipText(marketOrder ? "Для MARKET используется IOC" : "Time in force для лимитной заявки");
        });
        TradePlan plan = result == null ? null : result.plan;
        boolean longSide = forcedLongSide != null ? forcedLongSide : plan == null || plan.longSide;
        sideBox.setSelectedItem(longSide ? "LONG" : "SHORT");

        double current = result == null ? 0.0 : result.currentPrice;
        double entry = plan != null && forcedLongSide == null ? plan.entry : current;
        if (entry <= 0.0 && plan != null) entry = plan.entry;
        double risk = Math.max(entry * 0.005, entry > 0.0 ? entry * 0.003 : 1.0);

        double stop;
        double tp1;
        double tp2;
        double tp3;
        if (plan != null && forcedLongSide == null) {
            stop = plan.stop;
            tp1 = plan.tp1;
            tp2 = plan.tp2;
            tp3 = plan.tp3;
        } else if (longSide) {
            stop = entry - risk;
            tp1 = entry + risk;
            tp2 = entry + risk * 2.0;
            tp3 = entry + risk * 3.0;
        } else {
            stop = entry + risk;
            tp1 = entry - risk;
            tp2 = entry - risk * 2.0;
            tp3 = entry - risk * 3.0;
        }

        entryField.setText(fmt(entry));
        stopField.setText(fmt(stop));
        tp1Field.setText(fmt(tp1));
        tp2Field.setText(fmt(tp2));
        tp3Field.setText(fmt(tp3));
        amountField.setText("100");
        leverageField.setText("1");
        recalculateQuantityFromSizing();
        boolean marketOrder = "MARKET".equals(orderTypeBox.getSelectedItem());
        tifBox.setSelectedItem(marketOrder ? "IOC" : "GTC");
        tifBox.setToolTipText(marketOrder ? "Для MARKET используется IOC" : "Time in force для лимитной заявки");
        updateTicketPreview();
    }

    private void submit() {
        append("Start order submit: checking account and form");
        ExchangeAccountSettings account = ExchangeAccountSettings.current();
        if (!account.configured()) {
            append("BLOCKED: account is not configured. Open Account and choose Paper/Testnet/Demo/Live.");
            ExchangeAccountDialog.showDialog(parent, saved -> refreshAccountStatus());
            return;
        }
        append("Account OK: " + account.summary());

        if (account.exchange == ExchangeAccountSettings.Exchange.BINANCE
                && account.mode == ExchangeAccountSettings.Mode.LIVE
                && !account.liveTradingAllowed()) {
            append("BLOCKED: Binance LIVE safety is OFF. Open Account and switch LIVE orders: ON.");
            return;
        }

        try {
            boolean longSide = "LONG".equals(sideBox.getSelectedItem());
            String symbol = symbolField.getText().trim().toUpperCase(Locale.ROOT);
            if (symbol.isBlank()) throw new IllegalArgumentException("Символ пустой");
            String tifText = "MARKET".equals(orderTypeBox.getSelectedItem())
                    ? "IOC"
                    : String.valueOf(tifBox.getSelectedItem());
            append("Reading form: " + symbol + " " + (longSide ? "LONG" : "SHORT")
                    + " " + orderTypeBox.getSelectedItem() + " / " + tifText);
            recalculateQuantityFromSizing();
            double marginAmount = positive(amountField, "Сумма");
            int leverage = leverageValue();
            double qty = positive(qtyField, "Количество");
            double entry = positive(entryField, "Цена входа");
            double stop = positive(stopField, "Stop Loss");
            double tp1 = positive(tp1Field, "TP1");
            double tp2 = positive(tp2Field, "TP2");
            double tp3 = positive(tp3Field, "TP3");
            validateGeometry(longSide, entry, stop, tp1, tp2, tp3);
            append("Form OK: margin=" + marginAmount + " USDT, lev=x" + leverage
                    + ", qty=" + qty + ", entry=" + entry + ", SL=" + stop
                    + ", TP=[" + tp1 + ", " + tp2 + ", " + tp3 + "]");

            RiskSettings riskSettings = RiskSettings.load();
            append("Risk settings: " + riskSettings.summary());
            RiskGate riskGate = riskSettings.toRiskGate();
            int rawScore = result == null ? 80 : Math.max(1, result.smcScore);
            int confidence = Math.max(rawScore, riskSettings.minSmcScore);
            boolean confirmed = true;
            String setup = String.format(Locale.US, "manual-ticket margin=%.8f lev=%d", marginAmount, leverage);
            TradePlan plan = TradePlan.restored(longSide, entry, stop, tp1, tp2, tp3,
                    confidence, confirmed, setup);
            append("Trade plan OK: confidence=" + confidence + ", confirmed=" + confirmed);
            ExecutionGateway gateway = gateway(account);
            append("Gateway ready: " + gateway.getClass().getSimpleName());
            try {
                AccountSnapshot snapshot = gateway.accountSnapshot();
                riskGate.setDayStartEquity(snapshot.equity);
                append("Account snapshot OK: equity=" + snapshot.equity
                        + ", available=" + snapshot.availableBalance
                        + ", openPositions=" + snapshot.openPositions.size()
                        + ", openOrders=" + snapshot.openOrders.size());
            } catch (Exception ex) {
                append("Account snapshot warning: " + ex.getMessage());
            }
            HftExecutionService service = new HftExecutionService(gateway,
                    riskGate, ExecutionJournal.defaultJournal());
            service.setAutoProtectiveOrders(riskSettings.autoProtectiveOrders);
            ExecutionOrder.TimeInForce tif = "MARKET".equals(orderTypeBox.getSelectedItem())
                    ? ExecutionOrder.TimeInForce.IOC
                    : ExecutionOrder.TimeInForce.valueOf((String) tifBox.getSelectedItem());

            if (account.mode == ExchangeAccountSettings.Mode.LIVE) {
                append("LIVE confirmation required before sending real order");
                int ok = JOptionPane.showConfirmDialog(this,
                        "Отправить LIVE " + (longSide ? "LONG" : "SHORT") + " " + symbol
                                + " qty " + qty + " · " + marginAmount + " USDT x" + leverage + "?",
                        "Live confirmation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ok != JOptionPane.YES_OPTION) {
                    append("CANCELLED: user rejected LIVE confirmation");
                    return;
                }
            }

            if (account.mode != ExchangeAccountSettings.Mode.PAPER) {
                append("Setting leverage on exchange: " + symbol + " x" + leverage);
                gateway.setLeverage(symbol, leverage);
                append("Плечо выставлено/проверено: x" + leverage);
            }
            append("Отправляю " + account.summary() + " · " + symbol + " · "
                    + (longSide ? "LONG" : "SHORT") + " · amount " + marginAmount
                    + " USDT · x" + leverage + " · qty " + qty + " · entry " + entry
                    + " · " + orderTypeBox.getSelectedItem());
            ExecutionOrder.Type orderType = "MARKET".equals(orderTypeBox.getSelectedItem())
                    ? ExecutionOrder.Type.MARKET
                    : ExecutionOrder.Type.LIMIT;
            append("Submitting entry order: type=" + orderType + ", tif=" + tif);
            ExecutionReport report = service.submitEntry(symbol, plan, qty, tif, orderType);
            append("Report: " + report.status + " · filled=" + report.filledQuantity
                    + " · remaining=" + report.remainingQuantity + " · avg=" + report.avgPrice
                    + " · clientId=" + report.clientOrderId + " · exchangeId=" + report.exchangeOrderId
                    + " · " + report.message);
            if (report.status == ExecutionReport.Status.REJECTED) {
                append("FAILED: order rejected by gateway/exchange. Reason: " + report.message);
                return;
            }
            if (account.mode == ExchangeAccountSettings.Mode.PAPER
                    && gateway instanceof PaperFixGateway paper
                    && report.status == ExecutionReport.Status.ACCEPTED) {
                ExecutionReport fill = paper.simulateFill(report.clientOrderId, qty, entry);
                service.onExecutionReport(fill);
                append("Paper fill: " + fill.status + " · filled=" + fill.filledQuantity
                        + " · protective SL/TP recalculated in ledger");
            }
            if (account.mode != ExchangeAccountSettings.Mode.PAPER
                    && report.status == ExecutionReport.Status.ACCEPTED
                    && tif == ExecutionOrder.TimeInForce.GTC) {
                append("GTC заявка принята/поставлена. SL/TP будут выставлены после fill через execution report/reconcile.");
            }
            if ((report.status == ExecutionReport.Status.ACCEPTED
                    || report.status == ExecutionReport.Status.FILLED
                    || report.status == ExecutionReport.Status.PARTIALLY_FILLED)
                    && onTradeAccepted != null) {
                onTradeAccepted.accept(plan);
            }
            if (onAccountChanged != null) onAccountChanged.run();
            append("DONE: order flow finished with status " + report.status);
        } catch (Exception ex) {
            append("ERROR: " + ex.getClass().getSimpleName() + " · " + ex.getMessage());
        }
    }

    private ExecutionGateway gateway(ExchangeAccountSettings account) {
        return ExchangeGatewayFactory.create(account);
    }

    private void installSizingListeners() {
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { recalculateQuantityFromSizing(); }
            @Override public void removeUpdate(DocumentEvent e) { recalculateQuantityFromSizing(); }
            @Override public void changedUpdate(DocumentEvent e) { recalculateQuantityFromSizing(); }
        };
        amountField.getDocument().addDocumentListener(listener);
        leverageField.getDocument().addDocumentListener(listener);
        entryField.getDocument().addDocumentListener(listener);

        DocumentListener previewListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateTicketPreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updateTicketPreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updateTicketPreview(); }
        };
        symbolField.getDocument().addDocumentListener(previewListener);
        qtyField.getDocument().addDocumentListener(previewListener);
        stopField.getDocument().addDocumentListener(previewListener);
        tp1Field.getDocument().addDocumentListener(previewListener);
        tp2Field.getDocument().addDocumentListener(previewListener);
        tp3Field.getDocument().addDocumentListener(previewListener);
        sideBox.addActionListener(e -> updateTicketPreview());
        orderTypeBox.addActionListener(e -> updateTicketPreview());
        tifBox.addActionListener(e -> updateTicketPreview());
    }

    private void recalculateQuantityFromSizing() {
        if (sizingUpdate) return;
        double amount = parsePositive(amountField);
        double leverage = parsePositive(leverageField);
        double entry = parsePositive(entryField);
        if (amount <= 0.0 || leverage <= 0.0 || entry <= 0.0) return;
        double qty = amount * leverage / entry;
        sizingUpdate = true;
        try {
            qtyField.setText(decimalComma(String.format(Locale.US, "%.6f", Math.max(0.000001, qty))));
        } finally {
            sizingUpdate = false;
        }
        updateTicketPreview();
    }

    private int leverageValue() {
        double raw = positive(leverageField, "Плечо");
        int leverage = (int) Math.round(raw);
        if (leverage < 1 || leverage > 125) {
            throw new IllegalArgumentException("Плечо должно быть от 1 до 125");
        }
        leverageField.setText(Integer.toString(leverage));
        return leverage;
    }

    private void validateGeometry(boolean longSide, double entry, double stop,
                                  double tp1, double tp2, double tp3) {
        if (longSide) {
            if (stop >= entry) throw new IllegalArgumentException("Для LONG stop должен быть ниже entry");
            if (tp1 <= entry || tp2 <= entry || tp3 <= entry) throw new IllegalArgumentException("Для LONG TP должны быть выше entry");
        } else {
            if (stop <= entry) throw new IllegalArgumentException("Для SHORT stop должен быть выше entry");
            if (tp1 >= entry || tp2 >= entry || tp3 >= entry) throw new IllegalArgumentException("Для SHORT TP должны быть ниже entry");
        }
    }

    private double positive(JTextField field, String label) {
        try {
            double value = Double.parseDouble(field.getText().trim().replace(',', '.'));
            if (value <= 0.0 || !Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " должен быть больше 0");
        }
    }

    private double parsePositive(JTextField field) {
        try {
            double value = Double.parseDouble(field.getText().trim().replace(',', '.'));
            return value > 0.0 && Double.isFinite(value) ? value : 0.0;
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private void refreshAccountStatus() {
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        accountStatus.setText(settings.summary());
        accountStatus.setForeground(settings.configured() ? BULL : BEAR);
        updateTicketPreview();
    }

    private void append(String line) {
        String text = line == null ? "" : line;
        if (text.isBlank()) {
            logArea.append("\n");
        } else {
            logArea.append(LOG_TIME.format(LocalDateTime.now()) + " | " + text + "\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void updateTicketPreview() {
        boolean longSide = "LONG".equals(sideBox.getSelectedItem());
        Color sideColor = longSide ? BULL : BEAR;
        sideBadge.setText((longSide ? "LONG" : "SHORT") + " · " + value(symbolField));
        styleBadge(sideBadge, sideColor);
        sendButton.setText(longSide ? "Отправить LONG" : "Отправить SHORT");
        styleTradeButton(sendButton, sideColor);

        double entry = parsePositive(entryField);
        double stop = parsePositive(stopField);
        double tp1 = parsePositive(tp1Field);
        double tp2 = parsePositive(tp2Field);
        double tp3 = parsePositive(tp3Field);
        double qty = parsePositive(qtyField);
        double amount = parsePositive(amountField);
        double risk = entry > 0.0 && stop > 0.0 ? Math.abs(entry - stop) : 0.0;
        double rr1 = risk > 0.0 && tp1 > 0.0 ? Math.abs(tp1 - entry) / risk : 0.0;
        double rr2 = risk > 0.0 && tp2 > 0.0 ? Math.abs(tp2 - entry) / risk : 0.0;
        double rr3 = risk > 0.0 && tp3 > 0.0 ? Math.abs(tp3 - entry) / risk : 0.0;
        double notional = entry > 0.0 && qty > 0.0 ? entry * qty : 0.0;
        double riskUsd = risk > 0.0 && qty > 0.0 ? risk * qty : 0.0;
        String geometry = geometryText(longSide, entry, stop, tp1, tp2, tp3);
        String tifText = "MARKET".equals(orderTypeBox.getSelectedItem())
                ? "IOC"
                : String.valueOf(tifBox.getSelectedItem());
        String mode = String.valueOf(orderTypeBox.getSelectedItem()) + " / " + tifText;
        previewLabel.setText("<html><body style='width:310px;'><table width='100%' cellspacing='0' cellpadding='2'>"
                + line("Режим", escape(mode), FG)
                + line("Номинал", money(notional) + " · маржа " + money(amount), FG)
                + line("Риск до SL", money(riskUsd), riskUsd > 0.0 ? YELLOW : MUTED)
                + line("R:R", "TP1 " + fmt2(rr1) + " · TP2 " + fmt2(rr2) + " · TP3 " + fmt2(rr3),
                rr3 >= 2.0 ? BULL : rr3 >= 1.0 ? YELLOW : BEAR)
                + line("Геометрия", escape(geometry), "OK".equals(geometry) ? BULL : BEAR)
                + "</table></body></html>");
    }

    private String geometryText(boolean longSide, double entry, double stop, double tp1, double tp2, double tp3) {
        if (entry <= 0.0 || stop <= 0.0 || tp1 <= 0.0 || tp2 <= 0.0 || tp3 <= 0.0) return "заполни уровни";
        if (longSide && stop >= entry) return "SL должен быть ниже входа";
        if (!longSide && stop <= entry) return "SL должен быть выше входа";
        if (longSide && (tp1 <= entry || tp2 <= entry || tp3 <= entry)) return "TP должны быть выше входа";
        if (!longSide && (tp1 >= entry || tp2 >= entry || tp3 >= entry)) return "TP должны быть ниже входа";
        return "OK";
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        return panel;
    }

    private GridBagConstraints rowConstraints() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 0, 4, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;
        return gc;
    }

    private JScrollPane lightScroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        UIUtils.styleScrollPane(scroll, GRID);
        return scroll;
    }

    private JPanel section(String title, String note, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 7));
        panel.setOpaque(true);
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                BorderFactory.createEmptyBorder(9, 10, 10, 10)));
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel titleLabel = miniTitle(title);
        JLabel noteLabel = new JLabel(note);
        noteLabel.setForeground(MUTED);
        noteLabel.setFont(UIUtils.captionFont(11.4f));
        head.add(titleLabel);
        head.add(Box.createVerticalStrut(2));
        head.add(noteLabel);
        panel.add(head, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JLabel miniTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FG);
        label.setFont(UIUtils.labelFont(12.7f));
        return label;
    }

    private void addRow(JPanel panel, GridBagConstraints gc, String label, JComponent value) {
        gc.gridx = 0;
        gc.weightx = 0.0;
        JLabel l = new JLabel(label);
        l.setForeground(MUTED);
        l.setFont(UIUtils.labelFont(12.3f));
        panel.add(l, gc);

        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(value, gc);
        gc.gridy++;
    }

    private static JTextField field() {
        JTextField field = new JTextField();
        field.setBackground(BG);
        field.setForeground(FG);
        field.setCaretColor(FG);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        field.setFont(UIUtils.bodyFont(12.8f));
        return field;
    }

    private void styleBadge(JLabel label, Color color) {
        label.setForeground(Color.WHITE);
        label.setBackground(color);
        label.setOpaque(true);
        label.setFont(UIUtils.labelFont(12.3f));
        label.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
    }

    private void styleTradeButton(AbstractButton button, Color color) {
        button.setForeground(Color.WHITE);
        button.setBackground(color);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(UIUtils.buttonFont(13f));
        button.setMargin(new Insets(7, 18, 7, 18));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color.darker(), 1, true),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
    }

    private String fmt(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return "";
        return decimalComma(String.format(Locale.US, "%.4f", value));
    }

    private String decimalComma(String value) {
        return value == null ? "" : value.replace('.', ',');
    }

    private String value(JTextField field) {
        String text = field.getText();
        return text == null || text.isBlank() ? "-" : text.trim().toUpperCase(Locale.ROOT);
    }

    private String line(String label, String value, Color color) {
        return "<tr><td style='color:" + hex(MUTED) + ";'>" + escape(label)
                + "</td><td align='right' style='color:" + hex(color) + "; font-weight:700;'>"
                + value + "</td></tr>";
    }

    private String money(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return "-";
        return String.format(Locale.US, "%.2f USDT", value);
    }

    private String fmt2(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return "-";
        return String.format(Locale.US, "1:%.2f", value);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
