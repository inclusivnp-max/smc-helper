package app.ui;

import app.backtest.BacktestIntelligence;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import static app.Colors.*;

public class BacktestIntelligenceDialog extends JDialog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    public static void showDialog(Window parent) {
        BacktestIntelligenceDialog dialog = new BacktestIntelligenceDialog(parent);
        dialog.setVisible(true);
    }

    private BacktestIntelligenceDialog(Window parent) {
        super(parent, "Backtest Intelligence", ModalityType.MODELESS);
        setSize(840, 760);
        setMinimumSize(new Dimension(680, 560));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot());
    }

    private JComponent buildRoot() {
        BacktestIntelligence.Snapshot snapshot = BacktestIntelligence.loadFresh();

        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(PANEL_BG);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(16, 18, 16, 18)));

        JLabel title = new JLabel("Backtest Intelligence");
        title.setForeground(FG);
        title.setFont(UIUtils.displayFont(21f));
        JLabel subtitle = new JLabel(snapshot.directory.toString());
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12f));
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        JEditorPane content = new JEditorPane("text/html", html(snapshot));
        content.setEditable(false);
        content.setOpaque(true);
        content.setBackground(PANEL_BG);
        content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        content.setFont(UIUtils.bodyFont(12.5f));
        JScrollPane scroll = new JScrollPane(content);
        UIUtils.styleScrollPane(scroll, GRID);
        scroll.setBorder(BorderFactory.createLineBorder(GRID));
        scrollToTop(content, scroll);
        root.add(scroll, BorderLayout.CENTER);

        JButton refresh = new JButton("Обновить");
        JButton close = new JButton("Закрыть");
        UIUtils.stylePrimaryButton(refresh, ACCENT);
        UIUtils.stylePrimaryButton(close, FIELD_BG);
        refresh.addActionListener(e -> {
            content.setText(html(BacktestIntelligence.loadFresh()));
            scrollToTop(content, scroll);
        });
        close.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(refresh);
        footer.add(close);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void scrollToTop(JEditorPane content, JScrollPane scroll) {
        content.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> {
            content.setCaretPosition(0);
            scroll.getVerticalScrollBar().setValue(0);
            scroll.getHorizontalScrollBar().setValue(0);
        });
    }

    private String html(BacktestIntelligence.Snapshot s) {
        StringBuilder b = new StringBuilder();
        b.append("<html><head>").append(styles()).append("</head><body>");
        if (s.trades == 0) {
            b.append("<div class='note'>CSV бэктестов пока не найдено. Запусти несколько бэктестов, и здесь появится накопленная статистика.</div>");
            b.append("</body></html>");
            return b.toString();
        }

        b.append("<div class='summary'>")
                .append(metric("CSV файлов", Integer.toString(s.files), hex(ACCENT)))
                .append(metric("Сделок", num(s.trades), hex(FG)))
                .append(metric("Winrate", fmt(s.all.winrate()) + "%", color(s.all.expectancy(), s.all.winrate())))
                .append(metric("Expectancy", fmt(s.all.expectancy()) + "R", s.all.expectancy() >= 0 ? hex(BULL) : hex(BEAR)))
                .append("</div>");

        b.append(section("Общая картина"));
        b.append(table(
                row("Net R", signed(s.all.netR) + "R"),
                row("Profit Factor", fmt(s.all.profitFactor())),
                row("Wins / Losses / BE", s.all.wins + " / " + s.all.losses + " / " + s.all.breakeven),
                row("Avg SMC", fmt(s.all.avgSmc)),
                row("Последний CSV", s.latestModified == 0L ? "нет" : TIME.format(Instant.ofEpochMilli(s.latestModified))),
                row("Ошибок файлов / строк", s.failedFiles + " / " + s.skippedRows)
        ));

        appendTable(b, "По монетам", s.symbols, 12, 3);
        appendTable(b, "По таймфреймам", s.intervals, 10, 3);
        appendTable(b, "По направлениям", s.directions, 6, 3);
        appendTable(b, "По сетапам", s.setups, 10, 3);
        appendTable(b, "По моделям цели", s.targetModels, 10, 3);
        appendTable(b, "По Risk:Reward", s.riskReward, 10, 3);
        appendTable(b, "По Deviation / Smart Money Trap", s.deviations, 10, 3);
        appendTable(b, "По Imbalance", s.imbalances, 10, 3);
        appendTable(b, "По Event Risk", s.eventRisk, 10, 3);
        appendTable(b, "По режимам рынка", s.regimes, 10, 3);
        appendTable(b, "По зонам", s.zones, 10, 3);
        appendTable(b, "По сессиям", s.sessions, 10, 3);

        b.append(section("Фильтры, которые дают плюс"));
        appendBuckets(b, s.best(s.filters, 12, 5));
        b.append(section("Фильтры, которые портят статистику"));
        appendBuckets(b, s.worst(s.filters, 12, 5));

        b.append("<div class='note'>Эта статистика читается из всех сохраненных CSV. Финальный прогноз теперь дополнительно смотрит накопленную историю по текущей монете, таймфрейму и направлению.</div>");
        b.append("</body></html>");
        return b.toString();
    }

    private void appendTable(StringBuilder b, String title, Map<String, BacktestIntelligence.Bucket> buckets,
                             int limit, int minTrades) {
        b.append(section(title));
        appendBuckets(b, buckets.values().stream()
                .filter(bucket -> bucket.trades >= minTrades)
                .limit(limit)
                .toList());
    }

    private void appendBuckets(StringBuilder b, java.util.List<BacktestIntelligence.Bucket> buckets) {
        if (buckets.isEmpty()) {
            b.append("<div class='empty'>Недостаточно сделок для вывода.</div>");
            return;
        }
        b.append("<table>");
        b.append("<tr><th>Группа</th><th>Сделки</th><th>WR</th><th>Exp</th><th>PF</th><th>Net</th></tr>");
        for (BacktestIntelligence.Bucket bucket : buckets) {
            b.append("<tr><td>").append(escape(bucket.key)).append("</td>")
                    .append("<td>").append(bucket.trades).append("</td>")
                    .append("<td>").append(fmt(bucket.winrate())).append("%</td>")
                    .append("<td>").append(colored(fmt(bucket.expectancy()) + "R", bucket.expectancy())).append("</td>")
                    .append("<td>").append(fmt(bucket.profitFactor())).append("</td>")
                    .append("<td>").append(colored(signed(bucket.netR) + "R", bucket.netR)).append("</td></tr>");
        }
        b.append("</table>");
    }

    private String styles() {
        return """
                <style>
                body { background:__PANEL__; color:__FG__; font-family:__FONT__; font-size:12.5px; line-height:1.5; margin:12px; }
                .summary { width:100%; margin-bottom:12px; }
                .metric { background:__CARD__; border:1px solid __GRID__; padding:10px; margin-bottom:8px; }
                .label { color:__MUTED__; font-size:10.5px; text-transform:uppercase; letter-spacing:.35px; }
                .value { font-size:20px; font-weight:700; margin-top:2px; }
                .section { color:__ACCENT__; font-size:13.5px; font-weight:700; margin:14px 0 6px 0; }
                table { width:100%; border-collapse:collapse; background:__CARD__; border:1px solid __GRID__; }
                th { color:__MUTED__; text-align:right; padding:7px 9px; border-bottom:1px solid __GRID__; font-weight:700; }
                th:first-child { text-align:left; }
                td { color:__SOFT__; padding:7px 9px; border-bottom:1px solid __GRID__; text-align:right; }
                td:first-child { color:__FG__; text-align:left; width:42%; }
                .good { color:__BULL__; font-weight:700; }
                .bad { color:__BEAR__; font-weight:700; }
                .warn { color:__YELLOW__; font-weight:700; }
                .note { background:__BG__; border-left:3px solid __ACCENT__; color:__SOFT__; padding:9px 10px; margin-top:10px; }
                .empty { color:__MUTED__; background:__CARD__; border:1px solid __GRID__; padding:10px; }
                </style>
                """
                .replace("__FONT__", UIUtils.HTML_FONT_STACK)
                .replace("__PANEL__", hex(PANEL_BG))
                .replace("__FG__", hex(FG))
                .replace("__CARD__", hex(CARD_BG))
                .replace("__GRID__", hex(GRID))
                .replace("__MUTED__", hex(MUTED))
                .replace("__ACCENT__", hex(ACCENT))
                .replace("__SOFT__", hex(FG))
                .replace("__BULL__", hex(BULL))
                .replace("__BEAR__", hex(BEAR))
                .replace("__YELLOW__", hex(YELLOW))
                .replace("__BG__", hex(BG));
    }

    private String metric(String label, String value, String color) {
        return "<div class='metric'><div class='label'>" + escape(label) + "</div>"
                + "<div class='value' style='color:" + color + ";'>" + escape(value) + "</div></div>";
    }

    private String section(String title) {
        return "<div class='section'>" + escape(title) + "</div>";
    }

    private String table(String... rows) {
        StringBuilder b = new StringBuilder("<table>");
        for (String row : rows) b.append(row);
        return b.append("</table>").toString();
    }

    private String row(String k, String v) {
        return "<tr><td>" + escape(k) + "</td><td>" + escape(v) + "</td></tr>";
    }

    private String colored(String value, double number) {
        String klass = number > 0.0 ? "good" : number < 0.0 ? "bad" : "warn";
        return "<span class='" + klass + "'>" + escape(value) + "</span>";
    }

    private String color(double expectancy, double winrate) {
        if (expectancy > 0.0 && winrate >= 40.0) return hex(BULL);
        if (expectancy >= 0.0) return hex(YELLOW);
        return hex(BEAR);
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private String num(int value) {
        return String.format(Locale.US, "%,d", value).replace(',', ' ');
    }

    private String fmt(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return String.format(Locale.US, "%.2f", value);
    }

    private String signed(double value) {
        return (value >= 0.0 ? "+" : "") + fmt(value);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
