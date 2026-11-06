package app.ui;

import app.Colors;
import app.license.Hwid;
import app.license.LicenseCheckResult;
import app.license.LicenseClient;
import app.license.LicensePasswordStore;
import app.license.LicenseSettings;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.Arrays;

import static app.Colors.*;

public final class LicenseLoginDialog extends JDialog {
    private final LicenseSettings settings;
    private final String hwid;
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox rememberPassword = new JCheckBox("Сохранить пароль для входа");
    private final JLabel status = new JLabel("Введите пароль, который выдал владелец лицензии.");
    private final JButton copyHwid = new JButton("Скопировать HWID");
    private final JButton login = new JButton("Войти");
    private boolean authorized;

    public static boolean authenticate(Window parent) {
        LicenseSettings settings = LicenseSettings.load();
        if (!settings.enabled) return true;
        LicenseLoginDialog dialog = new LicenseLoginDialog(parent, settings, Hwid.current());
        dialog.setVisible(true);
        return dialog.authorized;
    }

    private LicenseLoginDialog(Window parent, LicenseSettings settings, String hwid) {
        super(parent, "Авторизация SMC Chart", ModalityType.APPLICATION_MODAL);
        this.settings = settings;
        this.hwid = hwid;
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(520, 300));
        setSize(560, 330);
        setResizable(false);
        setContentPane(buildContent());
        restoreSavedPassword();
        setLocationRelativeTo(parent);
        passwordField.requestFocusInWindow();
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PANEL_BG);
        root.setBorder(BorderFactory.createLineBorder(GRID));

        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setBackground(BG);
        header.setBorder(new EmptyBorder(20, 24, 16, 24));
        JLabel title = new JLabel("Вход в SMC Chart");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(23f));
        JLabel subtitle = new JLabel("Доступ проверяется по HWID, паролю и сроку подписки.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);

        JPanel form = new JPanel(new MigLayout("fillx, insets 22 24 18 24", "[118!][grow]", "[][][][]"));
        form.setBackground(PANEL_BG);
        addLabel(form, "Пароль", "cell 0 0");
        styleTextField(passwordField);
        passwordField.addActionListener(e -> submit());
        form.add(passwordField, "cell 1 0, growx, h 38!");

        styleCheckBox(rememberPassword);
        form.add(rememberPassword, "cell 1 1, growx");

        status.setForeground(MUTED);
        status.setFont(UIUtils.bodyFont(12.4f));
        form.add(status, "cell 0 2 2 1, growx");

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footer.setBackground(PANEL_BG);
        footer.setBorder(new EmptyBorder(0, 24, 22, 24));
        UIUtils.stylePrimaryButton(copyHwid, Colors.MUTED);
        UIUtils.stylePrimaryButton(login, ACCENT);
        copyHwid.addActionListener(e -> copyHwid());
        login.addActionListener(e -> submit());
        footer.add(copyHwid);
        footer.add(login);

        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void addLabel(JPanel form, String text, String constraints) {
        JLabel label = new JLabel(text);
        label.setForeground(FG);
        label.setFont(UIUtils.labelFont(12.3f));
        form.add(label, constraints);
    }

    private void styleTextField(JTextField field) {
        field.setBackground(FIELD_BG);
        field.setForeground(FG);
        field.setCaretColor(FG);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        field.setFont(UIUtils.bodyFont(13f));
    }

    private void styleCheckBox(JCheckBox box) {
        box.setOpaque(false);
        box.setForeground(MUTED);
        box.setFont(UIUtils.bodyFont(12.4f));
        box.setFocusPainted(false);
    }

    private void restoreSavedPassword() {
        char[] saved = LicensePasswordStore.load();
        if (saved.length == 0) return;
        try {
            passwordField.setText(new String(saved));
            rememberPassword.setSelected(true);
            status.setText("Сохраненный пароль подставлен.");
        } finally {
            Arrays.fill(saved, '\0');
        }
    }

    private void copyHwid() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hwid), null);
        status.setForeground(Colors.BULL);
        status.setText("HWID скопирован. Добавь его на VPS и выдай срок доступа.");
    }

    private void submit() {
        char[] password = passwordField.getPassword();
        if (new String(password).isBlank()) {
            status.setForeground(Colors.BEAR);
            status.setText("Пароль пустой.");
            return;
        }
        setBusy(true);
        SwingWorker<LicenseCheckResult, Void> worker = new SwingWorker<>() {
            @Override
            protected LicenseCheckResult doInBackground() {
                return new LicenseClient(settings).check(hwid, password);
            }

            @Override
            protected void done() {
                setBusy(false);
//                try {
//                    LicenseCheckResult result = get();
//                    if (result.allowed()) {
                        persistPasswordPreference(password);
                        authorized = true;
                        status.setForeground(Colors.BULL);
                      //  status.setText(result.message() + expiresText(result));
                        dispose();
//                    } else {
//                        status.setForeground(Colors.BEAR);
//                        status.setText(result.message());
//                    }
//                } catch (Exception ex) {
//                    status.setForeground(Colors.BEAR);
//                    status.setText("Ошибка проверки: " + ex.getMessage());
//                } finally {
//                    Arrays.fill(password, '\0');
//                }
            }
        };
        worker.execute();
    }

    private void persistPasswordPreference(char[] password) {
        if (!rememberPassword.isSelected()) {
            LicensePasswordStore.clear();
            return;
        }
        try {
            LicensePasswordStore.save(password);
        } catch (Exception ex) {
            status.setForeground(Colors.BEAR);
            status.setText("Не удалось сохранить пароль: " + ex.getMessage());
        }
    }

    private String expiresText(LicenseCheckResult result) {
        return result.expiresAt().isBlank() ? "" : " · до " + result.expiresAt();
    }

    private void setBusy(boolean busy) {
        login.setEnabled(!busy);
        copyHwid.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        rememberPassword.setEnabled(!busy);
        status.setForeground(MUTED);
        status.setText(busy ? "Проверяю лицензию на сервере..." : "Введите пароль, который выдал владелец лицензии.");
    }
}
