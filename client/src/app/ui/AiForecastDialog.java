package app.ui;

import app.ai.AiForecastEngine;
import app.ai.AiForecastEngine.Forecast;
import app.common.I18n;
import app.model.AnalysisResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;

public class AiForecastDialog extends JDialog {

    // Reuse one instance per app window (dispose on close, re-open fresh)
    public static void show(Window parent, AnalysisResult result) {
        AiForecastDialog dlg = new AiForecastDialog(parent, result);
        dlg.setVisible(true);
    }

    private AiForecastDialog(Window parent, AnalysisResult result) {
        super(parent,
                I18n.t("ИИ Прогноз") + (result != null && result.symbol != null ? "  ·  " + result.symbol : ""),
                ModalityType.MODELESS);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        AiForecastEngine engine = new AiForecastEngine();
        Forecast forecast = engine.generate(result);
        Color bg = app.Colors.BG;
        Color headerBg = app.Colors.CARD_BG;
        Color borderClr = app.Colors.GRID;

        // ── Content area ─────────────────────────────────────────────────────
        JEditorPane editor = new JEditorPane("text/html", forecast.html());
        editor.setEditable(false);
        editor.setBackground(bg);
        editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        editor.setFont(UIUtils.bodyFont(13.25f));
        SwingUtilities.invokeLater(() -> editor.setCaretPosition(0));

        JScrollPane scroll = new JScrollPane(editor,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setBackground(bg);
        scroll.getViewport().setBackground(bg);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new ModernScrollBarUI());

        // ── Header ───────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(headerBg);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderClr),
                new EmptyBorder(10, 16, 10, 12)));

        JLabel title = new JLabel((I18n.english() ? "AI Forecast" : "ИИ Прогноз")
                + "  ·  " + forecast.symbol() + "  " + forecast.interval());
        title.setForeground(app.Colors.ACCENT);
        title.setFont(UIUtils.titleFont(15.8f));

        JLabel verdictChip = buildVerdictChip(forecast.verdict(), forecast.quality());

        JButton closeBtn = new JButton("x");
        closeBtn.setForeground(app.Colors.MUTED);
        closeBtn.setBackground(headerBg);
        closeBtn.setOpaque(false);
        closeBtn.setBorder(new EmptyBorder(4, 10, 4, 6));
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> dispose());

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerRight.setBackground(headerBg);
        headerRight.add(verdictChip);
        headerRight.add(closeBtn);

        header.add(title, BorderLayout.WEST);
        header.add(headerRight, BorderLayout.EAST);

        // ── Footer ───────────────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(headerBg);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderClr),
                new EmptyBorder(7, 16, 7, 12)));

        JLabel note = new JLabel(I18n.english()
                ? "Local forecast: SMC, liquidity, order flow, statistics and risk filters. No external AI API."
                : "Локальный прогноз: SMC, ликвидность, order flow, статистика и риск-фильтры. Без внешних AI API.");
        note.setForeground(app.Colors.MUTED);
        note.setFont(UIUtils.captionFont(11.6f));

        JButton refreshBtn = new JButton(I18n.english() ? "Recalculate" : "Пересчитать");
        refreshBtn.setPreferredSize(new Dimension(138, 30));
        UIUtils.stylePrimaryButton(refreshBtn, app.Colors.ACCENT);
        refreshBtn.addActionListener(e -> {
            Forecast fresh = engine.generate(result);
            editor.setText(fresh.html());
            title.setText((I18n.english() ? "AI Forecast" : "ИИ Прогноз")
                    + "  ·  " + fresh.symbol() + "  " + fresh.interval());
            updateVerdictChip(verdictChip, fresh.verdict(), fresh.quality());
            SwingUtilities.invokeLater(() -> editor.setCaretPosition(0));
        });

        footer.add(note, BorderLayout.WEST);
        footer.add(refreshBtn, BorderLayout.EAST);

        // ── Root ─────────────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(bg);
        root.add(header, BorderLayout.NORTH);
        root.add(scroll,  BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        setSize(780, 760);
        setMinimumSize(new Dimension(620, 460));
        setLocationRelativeTo(parent);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JLabel buildVerdictChip(String verdict, String quality) {
        JLabel chip = new JLabel();
        chip.setFont(UIUtils.labelFont(12.7f));
        chip.setOpaque(true);
        updateVerdictChip(chip, verdict, quality);
        return chip;
    }

    private void updateVerdictChip(JLabel chip, String verdict, String quality) {
        Color bg, fg, border;
        switch (verdict) {
            case "ВХОДИТЬ", "ENTER" -> { bg = new Color(27, 58, 40);  fg = new Color(76, 175, 120); border = new Color(46, 125, 82); }
            case "ЖДАТЬ", "WAIT"   -> { bg = new Color(42, 37, 16);  fg = new Color(255, 179, 71); border = new Color(122, 96, 32); }
            default        -> { bg = new Color(42, 21, 21);  fg = new Color(240, 112, 112); border = new Color(122, 48, 48); }
        }
        chip.setText(" " + verdict + "  " + quality + " ");
        chip.setForeground(fg);
        chip.setBackground(bg);
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1),
                new EmptyBorder(4, 10, 4, 10)));
    }
}
