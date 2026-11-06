package app.ui;

import app.execution.fix.ExchangeAccountSettings;
import app.execution.fix.ExchangeGatewayFactory;
import app.execution.fix.ExecutionGateway;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.function.Consumer;

import static app.Colors.*;

public class ExchangeAccountDialog extends JDialog {
    private final JComboBox<String> exchangeBox = new JComboBox<>(new String[]{
            ExchangeAccountSettings.Exchange.BINANCE.label,
            ExchangeAccountSettings.Exchange.BYBIT.label,
            ExchangeAccountSettings.Exchange.BINGX.label
    });
    private final JComboBox<String> modeBox = new JComboBox<>(new String[]{
            ExchangeAccountSettings.Mode.PAPER.label,
            ExchangeAccountSettings.Mode.TESTNET.label,
            ExchangeAccountSettings.Mode.LIVE.label
    });
    private final JTextField profileField = field();
    private final JTextField apiKeyField = field();
    private final JPasswordField apiSecretField = passwordField();
    private final JPasswordField passphraseField = passwordField();
    private final JCheckBox rememberSecrets = new JCheckBox("Сохранять секреты локально");
    private final JToggleButton liveTradingToggle = new JToggleButton("LIVE orders: OFF");
    private final JLabel status = new JLabel(" ");
    private final JButton saveButton = new JButton("Войти / сохранить");
    private final Consumer<ExchangeAccountSettings> onSaved;

    public static void showDialog(JFrame parent, Consumer<ExchangeAccountSettings> onSaved) {
        new ExchangeAccountDialog(parent, onSaved).setVisible(true);
    }

    private ExchangeAccountDialog(JFrame parent, Consumer<ExchangeAccountSettings> onSaved) {
        super(parent, "Аккаунт биржи", true);
        this.onSaved = onSaved;
        setSize(560, 470);
        setMinimumSize(new Dimension(500, 430));
        setResizable(false);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot());
        loadCurrent();
    }

    private JComponent buildRoot() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel title = new JLabel("Аккаунт / биржа");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(20f));
        JLabel subtitle = new JLabel("Paper — локальный журнал. Для демо Binance выбирай Testnet / Demo и testnet API-ключи.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 0, 5, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;

        UIUtils.styleComboBox(exchangeBox, BG, FG, GRID);
        UIUtils.styleComboBox(modeBox, BG, FG, GRID);
        liveTradingToggle.setFocusPainted(false);
        liveTradingToggle.setMargin(new Insets(4, 10, 4, 10));
        liveTradingToggle.addActionListener(e -> updateLiveToggleState());
        modeBox.addActionListener(e -> updateLiveToggleState());
        addRow(form, gc, "Биржа", exchangeBox);
        addRow(form, gc, "Режим", modeBox);
        addRow(form, gc, "Профиль", profileField);
        addRow(form, gc, "LIVE safety", liveTradingToggle);
        addRow(form, gc, "API key", apiKeyField);
        addRow(form, gc, "API secret", apiSecretField);
        addRow(form, gc, "Passphrase / sourceKey", passphraseField);

        rememberSecrets.setOpaque(false);
        rememberSecrets.setForeground(FG);
        rememberSecrets.setFont(UIUtils.bodyFont(12.4f));
        addRow(form, gc, "", rememberSecrets);

        status.setForeground(MUTED);
        status.setFont(UIUtils.labelFont(12.3f));
        addRow(form, gc, "Статус", status);

        JButton save = saveButton;
        JButton close = new JButton("Закрыть");
        UIUtils.stylePrimaryButton(save, BULL);
        UIUtils.stylePrimaryButton(close, FIELD_BG);
        save.addActionListener(e -> save());
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        buttons.add(save);

        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private void loadCurrent() {
        ExchangeAccountSettings settings = ExchangeAccountSettings.current();
        exchangeBox.setSelectedItem(settings.exchange.label);
        modeBox.setSelectedItem(settings.mode.label);
        profileField.setText(settings.profileName);
        apiKeyField.setText(settings.apiKey);
        apiSecretField.setText(settings.apiSecret);
        passphraseField.setText(settings.passphrase);
        rememberSecrets.setSelected(settings.rememberSecrets);
        liveTradingToggle.setSelected(settings.liveTradingEnabled);
        updateLiveToggleState();
        status.setText(settings.summary());
    }

    private void save() {
        ExchangeAccountSettings settings = readSettings();

        if (settings.mode == ExchangeAccountSettings.Mode.LIVE) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "Live-режим может отправлять реальные заявки. Продолжить?",
                    "Подтверждение Live", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
            if (settings.liveTradingEnabled) {
                int arm = JOptionPane.showConfirmDialog(this,
                        "LIVE orders are ON. This allows real orders from the trade ticket. Confirm?",
                        "Arm LIVE orders", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (arm != JOptionPane.YES_OPTION) return;
            }
        }
        if (settings.mode != ExchangeAccountSettings.Mode.PAPER && !settings.configured()) {
            status.setText("Нужны API key и secret");
            status.setForeground(BEAR);
            return;
        }

        setBusy(true, settings.mode == ExchangeAccountSettings.Mode.PAPER
                ? "Сохраняю Paper-профиль..."
                : "Проверяю подключение к бирже...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return probeConnection(settings);
            }

            @Override
            protected void done() {
                setBusy(false, null);
                try {
                    String message = get();
                    ExchangeAccountSettings.saveSession(settings, true);
                    status.setText(message);
                    status.setForeground(BULL);
                    if (onSaved != null) onSaved.accept(settings);
                    JOptionPane.showMessageDialog(ExchangeAccountDialog.this,
                            message,
                            "Аккаунт подключен", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    status.setText("Ошибка входа: " + message);
                    status.setForeground(BEAR);
                    JOptionPane.showMessageDialog(ExchangeAccountDialog.this,
                            "Не удалось подключить аккаунт:\n" + message,
                            "Ошибка входа", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private ExchangeAccountSettings readSettings() {
        ExchangeAccountSettings settings = new ExchangeAccountSettings();
        settings.exchange = ExchangeAccountSettings.Exchange.fromLabel((String) exchangeBox.getSelectedItem());
        settings.mode = ExchangeAccountSettings.Mode.fromLabel((String) modeBox.getSelectedItem());
        settings.profileName = profileField.getText().trim();
        settings.apiKey = apiKeyField.getText().trim();
        char[] apiSecret = apiSecretField.getPassword();
        char[] passphrase = passphraseField.getPassword();
        try {
            settings.apiSecret = new String(apiSecret).trim();
            settings.passphrase = new String(passphrase).trim();
        } finally {
            Arrays.fill(apiSecret, '\0');
            Arrays.fill(passphrase, '\0');
        }
        settings.rememberSecrets = rememberSecrets.isSelected();
        settings.liveTradingEnabled = liveTradingToggle.isSelected()
                && settings.mode == ExchangeAccountSettings.Mode.LIVE;
        return settings;
    }

    private void updateLiveToggleState() {
        boolean liveMode = ExchangeAccountSettings.Mode.fromLabel((String) modeBox.getSelectedItem())
                == ExchangeAccountSettings.Mode.LIVE;
        liveTradingToggle.setEnabled(liveMode);
        if (!liveMode) liveTradingToggle.setSelected(false);
        liveTradingToggle.setText(liveTradingToggle.isSelected() ? "LIVE orders: ON" : "LIVE orders: OFF");
        liveTradingToggle.setForeground(liveTradingToggle.isSelected() ? Color.BLACK : FG);
        UIUtils.stylePrimaryButton(liveTradingToggle, liveTradingToggle.isSelected() ? YELLOW : FIELD_BG);
        if (liveMode && !liveTradingToggle.isSelected()) {
            status.setText("LIVE selected, orders blocked until LIVE orders is ON");
            status.setForeground(YELLOW);
        }
    }

    private String probeConnection(ExchangeAccountSettings settings) throws Exception {
        if (settings.mode == ExchangeAccountSettings.Mode.PAPER) {
            return settings.summary() + " · сохранен";
        }
        ExecutionGateway gateway = ExchangeGatewayFactory.create(settings);
        gateway.openOrders();
        return "Подключено: " + settings.summary();
    }

    private void setBusy(boolean busy, String message) {
        saveButton.setEnabled(!busy);
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        if (message != null) {
            status.setText(message);
            status.setForeground(MUTED);
        }
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
        styleField(field);
        return field;
    }

    private static JPasswordField passwordField() {
        JPasswordField field = new JPasswordField();
        styleField(field);
        return field;
    }

    private static void styleField(JTextField field) {
        field.setBackground(BG);
        field.setForeground(FG);
        field.setCaretColor(FG);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        field.setFont(UIUtils.bodyFont(12.8f));
    }
}
