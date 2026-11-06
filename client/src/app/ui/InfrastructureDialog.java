package app.ui;

import app.common.ProjectPaths;
import app.datalake.DataQualityMonitor;
import app.execution.fix.ExecutionJournal;
import app.execution.fix.RiskSettings;
import app.infrastructure.PythonRuntimeManager;
import app.learning.ModelGovernanceRegistry;
import app.logic.BinanceClient;
import app.model.Candle;
import app.ml.MlBridgeClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static app.Colors.*;

public class InfrastructureDialog extends JDialog {
    private static final PythonRuntimeManager MANAGER = new PythonRuntimeManager();

    private final JTextField appFolderField = readonly(ProjectPaths.root().toString());
    private final JTextField lakeField = readonly(ProjectPaths.dataLakeRoot().toString());
    private final JTextField runnerField = readonly(MANAGER.runnerLabel());
    private final JTextField storageField = readonly(storageSummary());
    private final JTextField logsField = readonly(ProjectPaths.pythonLogsDir().toString());
    private final JTextField symbolsField = new JTextField("BTCUSDT,ETHUSDT,SOLUSDT");
    private final JTextArea logArea = new JTextArea();

    public static void showDialog(JFrame parent) {
        InfrastructureDialog dialog = new InfrastructureDialog(parent);
        dialog.setVisible(true);
    }

    private InfrastructureDialog(JFrame parent) {
        super(parent, "Инфраструктура", false);
        setSize(880, 560);
        setMinimumSize(new Dimension(760, 520));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);

        append(MANAGER.statusText());
        appendOperationalDiagnostics();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setOpaque(false);

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Инфраструктура Python / ML");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(19f));

        JLabel subtitle = new JLabel("ML-сервис, ticker plant, Data Lake и запуск через java-python-executor");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));

        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subtitle);

        JButton close = new JButton("x");
        close.setForeground(MUTED);
        close.setFocusPainted(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addActionListener(e -> dispose());

        header.add(titleBox, BorderLayout.WEST);
        header.add(close, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(10, 12));
        body.setOpaque(false);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 0, 4, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;
        addRow(fields, gc, "Папка", appFolderField);
        addRow(fields, gc, "Data Lake", lakeField);
        addRow(fields, gc, "Runner", runnerField);
        addRow(fields, gc, "Хранилище", storageField);
        addRow(fields, gc, "Логи", logsField);
        styleTextField(symbolsField);
        addRow(fields, gc, "Пары", symbolsField);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(BG);
        logArea.setForeground(FG);
        logArea.setCaretColor(FG);
        logArea.setFont(UIUtils.monoFont(11.4f, Font.PLAIN));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(0, 250));
        UIUtils.styleScrollPane(scroll, GRID);

        body.add(fields, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        return body;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);

        JButton ml = new JButton("ML service");
        JButton plant = new JButton("Ticker plant");
        JButton health = new JButton("ML health");
        JButton risk = new JButton("Risk");
        JButton quality = new JButton("Data quality");
        JButton journal = new JButton("Journal");
        JButton folder = new JButton("Папка");
        JButton logs = new JButton("Логи");
        JButton refresh = new JButton("Статус");
        JButton stop = new JButton("Остановить");

        UIUtils.stylePrimaryButton(ml, ACCENT);
        UIUtils.stylePrimaryButton(plant, BULL);
        UIUtils.stylePrimaryButton(health, CYAN);
        UIUtils.stylePrimaryButton(risk, YELLOW);
        UIUtils.stylePrimaryButton(quality, CYAN);
        UIUtils.stylePrimaryButton(journal, PURPLE);
        UIUtils.stylePrimaryButton(folder, FIELD_BG);
        UIUtils.stylePrimaryButton(logs, FIELD_BG);
        UIUtils.stylePrimaryButton(refresh, FIELD_BG);
        UIUtils.stylePrimaryButton(stop, BEAR);

        ml.addActionListener(e -> runAsync("ML service", () -> MANAGER.startMlService(this::append)));
        plant.addActionListener(e -> runAsync("Ticker plant", () -> MANAGER.startTickerPlant(symbolsField.getText(), this::append)));
        health.addActionListener(e -> probeMlBridge());
        risk.addActionListener(e -> openRiskSettings());
        quality.addActionListener(e -> runDataQualityCheck());
        journal.addActionListener(e -> appendOperationalDiagnostics());
        folder.addActionListener(e -> openAppFolder());
        logs.addActionListener(e -> openLogsFolder());
        refresh.addActionListener(e -> refreshStatus());
        stop.addActionListener(e -> {
            MANAGER.stopAll();
            append("All Python tasks were stopped");
            refreshStatus();
        });

        footer.add(ml);
        footer.add(plant);
        footer.add(health);
        footer.add(risk);
        footer.add(quality);
        footer.add(journal);
        footer.add(folder);
        footer.add(logs);
        footer.add(refresh);
        footer.add(stop);
        return footer;
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

    private void addRiskRow(JPanel panel, GridBagConstraints gc, String label, JComponent value) {
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

    private JTextField riskField(double value) {
        JTextField field = new JTextField(Double.toString(value));
        styleTextField(field);
        return field;
    }

    private JCheckBox riskCheck(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setForeground(FG);
        box.setFont(UIUtils.bodyFont(12.5f));
        return box;
    }

    private double doubleFrom(JTextField field, double fallback) {
        try {
            return Double.parseDouble(field.getText().trim().replace(',', '.'));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static JTextField readonly(String value) {
        JTextField field = new JTextField(value);
        field.setEditable(false);
        styleTextField(field);
        return field;
    }

    private static void styleTextField(JTextField field) {
        field.setBackground(BG);
        field.setForeground(FG);
        field.setCaretColor(FG);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        field.setFont(UIUtils.bodyFont(12.8f));
    }

    private void runAsync(String label, ThrowingAction action) {
        append("Starting " + label + "...");
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    runnerField.setText(MANAGER.runnerLabel());
                    refreshStatus();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    append(label + " failed: " + cause.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void refreshStatus() {
        runnerField.setText(MANAGER.runnerLabel());
        storageField.setText(storageSummary());
        append(MANAGER.statusText());
        appendOperationalDiagnostics();
    }

    private void appendOperationalDiagnostics() {
        append("Risk settings: " + RiskSettings.load().summary());
        for (String line : ExecutionJournal.defaultJournal().summary().lines()) append(line);
        for (String line : new ModelGovernanceRegistry().summary().lines()) append(line);
    }

    private void openRiskSettings() {
        RiskSettings settings = RiskSettings.load();
        JDialog dialog = new JDialog(this, "Execution Risk", true);
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(new EmptyBorder(14, 14, 14, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;

        JTextField maxOrder = riskField(settings.maxOrderNotional);
        JTextField maxRisk = riskField(settings.maxRiskNotional);
        JTextField minScore = riskField(settings.minSmcScore);
        JTextField dailyLoss = riskField(settings.maxDailyLossNotional);
        JTextField dailyLossPct = riskField(settings.maxDailyLossPct);
        JTextField maxTrades = riskField(settings.maxTradesPerDay);
        JTextField maxLosses = riskField(settings.maxConsecutiveLosses);
        JTextField maxPositions = riskField(settings.maxOpenPositions);
        JTextField duplicateLock = riskField(settings.duplicateOrderLockSeconds);
        JTextField maxQty = riskField(settings.maxSingleOrderQuantity);
        JTextField entryDev = riskField(settings.maxEntryDeviationPct);
        JTextField stopDistance = riskField(settings.maxStopDistancePct);
        JTextField reconcileSeconds = riskField(settings.reconcileSeconds);
        JCheckBox confirmed = riskCheck("Require confirmed entry", settings.requireConfirmedEntry);
        JCheckBox cancelOrphans = riskCheck("Auto cancel orphaned exchange orders", settings.autoCancelOrphans);
        JCheckBox protective = riskCheck("Auto SL/TP after partial fills", settings.autoProtectiveOrders);

        addRiskRow(panel, gc, "Max order notional", maxOrder);
        addRiskRow(panel, gc, "Max risk notional", maxRisk);
        addRiskRow(panel, gc, "Min SMC score", minScore);
        addRiskRow(panel, gc, "Daily loss kill switch", dailyLoss);
        addRiskRow(panel, gc, "Daily loss % kill switch", dailyLossPct);
        addRiskRow(panel, gc, "Max trades per day", maxTrades);
        addRiskRow(panel, gc, "Max loss streak", maxLosses);
        addRiskRow(panel, gc, "Max open positions", maxPositions);
        addRiskRow(panel, gc, "Duplicate lock seconds", duplicateLock);
        addRiskRow(panel, gc, "Max single quantity", maxQty);
        addRiskRow(panel, gc, "Max entry deviation %", entryDev);
        addRiskRow(panel, gc, "Max stop distance %", stopDistance);
        addRiskRow(panel, gc, "Reconcile seconds", reconcileSeconds);
        addRiskRow(panel, gc, "", confirmed);
        addRiskRow(panel, gc, "", cancelOrphans);
        addRiskRow(panel, gc, "", protective);

        JButton save = new JButton("Save");
        UIUtils.stylePrimaryButton(save, BULL);
        save.addActionListener(e -> {
            settings.maxOrderNotional = doubleFrom(maxOrder, settings.maxOrderNotional);
            settings.maxRiskNotional = doubleFrom(maxRisk, settings.maxRiskNotional);
            settings.minSmcScore = (int) doubleFrom(minScore, settings.minSmcScore);
            settings.maxDailyLossNotional = doubleFrom(dailyLoss, settings.maxDailyLossNotional);
            settings.maxDailyLossPct = doubleFrom(dailyLossPct, settings.maxDailyLossPct);
            settings.maxTradesPerDay = Math.max(0, (int) doubleFrom(maxTrades, settings.maxTradesPerDay));
            settings.maxConsecutiveLosses = Math.max(0, (int) doubleFrom(maxLosses, settings.maxConsecutiveLosses));
            settings.maxOpenPositions = Math.max(0, (int) doubleFrom(maxPositions, settings.maxOpenPositions));
            settings.duplicateOrderLockSeconds = Math.max(0, (int) doubleFrom(duplicateLock, settings.duplicateOrderLockSeconds));
            settings.maxSingleOrderQuantity = doubleFrom(maxQty, settings.maxSingleOrderQuantity);
            settings.maxEntryDeviationPct = doubleFrom(entryDev, settings.maxEntryDeviationPct);
            settings.maxStopDistancePct = doubleFrom(stopDistance, settings.maxStopDistancePct);
            settings.reconcileSeconds = Math.max(5, (int) doubleFrom(reconcileSeconds, settings.reconcileSeconds));
            settings.requireConfirmedEntry = confirmed.isSelected();
            settings.autoCancelOrphans = cancelOrphans.isSelected();
            settings.autoProtectiveOrders = protective.isSelected();
            settings.save();
            append("Risk settings saved: " + settings.summary());
            dialog.dispose();
        });
        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(save, gc);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(460, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        AppIcons.install(dialog);
        WindowsTitleBar.applyAppTheme(dialog);
        dialog.setVisible(true);
    }

    private void runDataQualityCheck() {
        append("Checking Data Quality...");
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                StringBuilder sb = new StringBuilder();
                DataQualityMonitor monitor = new DataQualityMonitor();
                for (String raw : symbolsField.getText().split(",")) {
                    String symbol = raw.trim().toUpperCase();
                    if (symbol.isBlank()) continue;
                    java.util.List<Candle> candles = BinanceClient.fetchCandlesHistory(
                            symbol, "1m", 500, BinanceClient.Market.FUTURES);
                    DataQualityMonitor.Report report = monitor.inspect(symbol,
                            BinanceClient.Market.FUTURES, "1m", candles);
                    for (String line : report.lines()) sb.append(line).append('\n');
                }
                return sb.toString();
            }

            @Override
            protected void done() {
                try {
                    append(get());
                } catch (Exception ex) {
                    append("Data quality check failed: " + (ex.getCause() == null ? ex : ex.getCause()).getMessage());
                }
            }
        };
        worker.execute();
    }

    private void probeMlBridge() {
        append("Checking ML bridge...");
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return new MlBridgeClient().probe();
            }

            @Override
            protected void done() {
                try {
                    append(get());
                } catch (Exception ex) {
                    append("ML bridge unavailable: " + (ex.getCause() == null ? ex : ex.getCause()).getMessage());
                }
            }
        };
        worker.execute();
    }

    private void openAppFolder() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(ProjectPaths.root().toFile());
                append("Opened app folder: " + ProjectPaths.root());
            } else {
                append("Desktop open is not supported on this system");
            }
        } catch (Exception ex) {
            append("Cannot open app folder: " + ex.getMessage());
        }
    }

    private void openLogsFolder() {
        try {
            Files.createDirectories(ProjectPaths.pythonLogsDir());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(ProjectPaths.pythonLogsDir().toFile());
                append("Opened logs folder: " + ProjectPaths.pythonLogsDir());
            } else {
                append("Desktop open is not supported on this system");
            }
        } catch (Exception ex) {
            append("Cannot open logs folder: " + ex.getMessage());
        }
    }

    private static String storageSummary() {
        long bytes = fastDirSize(ProjectPaths.dataLakeRoot())
                + fastDirSize(ProjectPaths.tickCacheRoot())
                + fastDirSize(ProjectPaths.weightsRoot())
                + fastDirSize(ProjectPaths.backtestsDir())
                + fastDirSize(ProjectPaths.executionJournalDir())
                + fastDirSize(ProjectPaths.modelGovernanceDir())
                + fastDirSize(ProjectPaths.dataQualityDir())
                + fastDirSize(ProjectPaths.pythonRuntimeDir());
        return formatBytes(bytes);
    }

    private static long fastDirSize(Path root) {
        if (root == null || !Files.exists(root)) return 0L;
        AtomicInteger seen = new AtomicInteger();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .takeWhile(path -> seen.incrementAndGet() <= 5000)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ex) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return new DecimalFormat(value >= 100 ? "0" : "0.0").format(value) + " " + units[unit]
                + " локальных данных";
    }

    private void append(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line == null ? "" : line);
            if (line == null || !line.endsWith("\n")) logArea.append("\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private interface ThrowingAction {
        void run() throws IOException;
    }
}
