package app.ui;

import app.logic.Backtester;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Locale;
import java.util.Map;

import static app.Colors.*;

public class BacktestReportDialog extends JDialog {
    private static final String C_GREEN = "#26A69A";
    private static final String C_YELLOW = "#FFB300";
    private static final String C_RED = "#EF5350";

    public static void showReport(JFrame parent, String symbol, String htf, String ltf, Backtester.Report report) {
        BacktestReportDialog dialog = new BacktestReportDialog(parent, symbol, htf, ltf, report);
        dialog.setVisible(true);
    }

    private BacktestReportDialog(JFrame parent, String symbol, String htf, String ltf, Backtester.Report report) {
        super(parent, "Отчет бэктеста", true);
        setSize(760, 740);
        setMinimumSize(new Dimension(620, 560));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot(symbol, htf, ltf, report));
    }

    private JComponent buildRoot(String symbol, String htf, String ltf, Backtester.Report report) {
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(PANEL_BG);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(16, 18, 16, 18)));

        root.add(buildHeader(symbol, htf, ltf, report), BorderLayout.NORTH);

        JEditorPane content = new JEditorPane("text/html", buildHtml(symbol, htf, ltf, report));
        content.setEditable(false);
        content.setOpaque(true);
        content.setBackground(PANEL_BG);
        content.setForeground(FG);
        content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        content.setFont(UIUtils.bodyFont(12.5f));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(GRID));
        scroll.getViewport().setBackground(PANEL_BG);
        UIUtils.styleScrollPane(scroll, GRID);
        scrollToTop(content, scroll);
        root.add(scroll, BorderLayout.CENTER);

        JButton intelligence = new JButton("Общая статистика");
        JButton ok = new JButton("Закрыть");
        UIUtils.stylePrimaryButton(intelligence, FIELD_BG);
        UIUtils.stylePrimaryButton(ok, ACCENT);
        intelligence.addActionListener(e -> BacktestIntelligenceDialog.showDialog(this));
        ok.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(intelligence);
        footer.add(ok);
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

    private JComponent buildHeader(String symbol, String htf, String ltf, Backtester.Report report) {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Отчет бэктеста");
        title.setForeground(FG);
        title.setFont(UIUtils.displayFont(21f));
        JLabel subtitle = new JLabel(symbol + " · HTF " + htf + " · LTF " + ltf + " · " + report.executionModel);
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));
        text.add(title);
        text.add(Box.createVerticalStrut(4));
        text.add(subtitle);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        chips.setOpaque(false);
        chips.add(chip("Winrate " + fmt(report.winrate) + "%", winrateColor(report.winrate)));
        chips.add(chip("Exp " + fmt(report.expectancy) + "R", report.expectancy >= 0 ? BULL : BEAR));
        chips.add(chip(report.executedTrades + " сделок", FIELD_BG));

        header.add(text, BorderLayout.CENTER);
        header.add(chips, BorderLayout.EAST);
        return header;
    }

    private JLabel chip(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setForeground(chipTextColor(color));
        label.setBackground(color);
        label.setBorder(new EmptyBorder(6, 10, 6, 10));
        label.setFont(UIUtils.labelFont(12.3f));
        return label;
    }

    private Color chipTextColor(Color bg) {
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
        return luminance >= 0.55 ? new Color(20, 24, 32) : FG;
    }

    private String buildHtml(String symbol, String htf, String ltf, Backtester.Report report) {
        StringBuilder b = new StringBuilder();
        b.append("<html><head>").append(styles()).append("</head><body>");
        b.append("<div class='summary'>");
        b.append(metric("Winrate", fmt(report.winrate) + "%", winrateHex(report.winrate)));
        b.append(metric("Net R", fmt(report.netR) + "R", report.netR >= 0 ? C_GREEN : C_RED));
        b.append(metric("Expectancy", fmt(report.expectancy) + "R", report.expectancy >= 0 ? C_GREEN : C_RED));
        b.append(metric("Profit Factor", fmt(report.profitFactor), report.profitFactor >= 1.2 ? C_GREEN : C_YELLOW));
        b.append("</div>");

        b.append(section("Контекст"));
        b.append(table(
                row("Монета", escape(symbol)),
                row("Таймфреймы", escape(htf) + " / " + escape(ltf)),
                row("История LTF", num(report.historyCandles)),
                row("Прогрев SMC", num(report.warmupCandles)),
                row("Свечей в симуляции", num(report.testedCandles))
        ));

        b.append(section("Сделки и результат"));
        b.append(table(
                row("Найдено сетапов", num(report.totalSetups)),
                row("Упущено без касания", muted(num(report.missed))),
                row("Исполнено сделок", strong(num(report.executedTrades))),
                row("Профит TP", good(num(report.wins))),
                row("Убыток SL", bad(num(report.losses))),
                row("Breakeven", muted(num(report.breakeven))),
                row("Max Drawdown", bad(fmt(report.maxDrawdown) + "R")),
                row("Sharpe", strong(fmt(report.sharpeRatio)))
        ));

        b.append(section("Время до целей"));
        b.append(table(
                row("Avg bars TP1 / TP2 / TP3", good(fmt(report.avgBarsToTp1) + " / " + fmt(report.avgBarsToTp2) + " / " + fmt(report.avgBarsToTp3))),
                row("Avg bars STOP", bad(fmt(report.avgBarsToStop))),
                row("Intrabar events", strong(num(report.intrabarEvents)))
        ));

        b.append(section("Качество данных"));
        b.append(table(
                row("Модель исполнения", blue(escape(report.executionModel))),
                row("Свечи по тикам", good(num(report.tickExecutionCandles))),
                row("WebSocket ticks", good(num(report.websocketTickCandles))),
                row("WebSocket misses", warn(num(report.websocketTickMisses))),
                row("Tick cache hits / misses", good(num(report.tickCacheHits)) + " / " + warn(num(report.tickCacheMisses))),
                row("Data lake ticks / misses", good(num(report.dataLakeTickCandles)) + " / " + warn(num(report.dataLakeMisses))),
                row("Data lake source", blue(empty(report.dataLakeSource, "нет"))),
                row("OHLC fallback", warn(num(report.ohlcFallbackCandles))),
                row("Ошибки тик-данных", bad(num(report.tickFetchErrors))),
                row("API cooldown", report.tickApiCooldown ? bad("да") : good("нет"))
        ));

        b.append(section("Исполнение и impact"));
        b.append(table(
                row("Market impact", report.marketImpactEnabled ? blue("$" + fmt0(report.executionNotionalUsd)) : muted("off")),
                row("Impact avg", warn(fmt(report.avgImpactBps) + " bps · " + fmt(report.avgImpactR) + "R")),
                row("Participation", strong(fmt(report.avgParticipationPct) + "% · " + report.executionSlices + " slices")),
                row("CSV записей", strong(num(report.records.size())))
        ));

        appendStats(b, "Статистика по сетапам", report.setupStats);
        appendStats(b, "Filter evidence", report.factorStats);
        appendStats(b, "Статистика по режимам рынка", report.regimeStats);

        appendWarnings(b, report);
        b.append(section("Файлы"));
        b.append("<div class='paths'>")
                .append(path("Tick cache", report.tickCachePath))
                .append(path("WebSocket ticks", report.websocketTickPath))
                .append(path("Data lake", report.dataLakePath))
                .append(path("CSV", empty(report.csvPath, "нет записей")))
                .append(report.csvError == null || report.csvError.isBlank()
                        ? ""
                        : path("CSV error", report.csvError))
                .append("</div>");

        b.append("</body></html>");
        return b.toString();
    }

    private String styles() {
        return """
                <style>
                body {
                  background: __PANEL__;
                  color: __FG__;
                  font-family: __FONT__;
                  font-size: 12.5px;
                  line-height: 1.5;
                  margin: 12px;
                }
                .summary { width: 100%; margin-bottom: 12px; }
                .metric {
                  background: __CARD__;
                  border: 1px solid __GRID__;
                  padding: 10px;
                  margin-bottom: 8px;
                }
                .label { color: __MUTED__; font-size: 10.5px; text-transform: uppercase; letter-spacing:.35px; }
                .value { font-size: 20px; font-weight: 700; margin-top: 2px; }
                .section {
                  color: __ACCENT__;
                  font-size: 13.5px;
                  font-weight: 700;
                  margin: 14px 0 6px 0;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                  background: __CARD__;
                  border: 1px solid __GRID__;
                }
                td {
                  padding: 7px 9px;
                  border-bottom: 1px solid __GRID__;
                  color: __SOFT__;
                  vertical-align: top;
                }
                td:first-child { color: __MUTED__; width: 52%; }
                td:last-child { text-align: right; color: __FG__; }
                .good { color: __BULL__; font-weight: 700; }
                .bad { color: __BEAR__; font-weight: 700; }
                .warn { color: __YELLOW__; font-weight: 700; }
                .blue { color: __ACCENT__; font-weight: 700; }
                .strong { color: __FG__; font-weight: 700; }
                .muted { color: __MUTED__; }
                .note {
                  background: __BG__;
                  border-left: 3px solid __YELLOW__;
                  color: __SOFT__;
                  padding: 9px 10px;
                  margin-top: 9px;
                }
                .paths {
                  background: __BG__;
                  border: 1px solid __GRID__;
                  color: __MUTED__;
                  padding: 8px 10px;
                  font-family: __MONO_FONT__;
                  font-size: 10.8px;
                }
                .pathLabel { color: __SOFT__; font-family: __FONT__; }
                </style>
                """
                .replace("__FONT__", UIUtils.HTML_FONT_STACK)
                .replace("__MONO_FONT__", UIUtils.HTML_MONO_FONT_STACK)
                .replace("__PANEL__", hex(PANEL_BG))
                .replace("__BG__", hex(BG))
                .replace("__CARD__", hex(CARD_BG))
                .replace("__GRID__", hex(GRID))
                .replace("__FG__", hex(FG))
                .replace("__SOFT__", hex(FG))
                .replace("__MUTED__", hex(MUTED))
                .replace("__ACCENT__", hex(ACCENT))
                .replace("__BULL__", hex(BULL))
                .replace("__BEAR__", hex(BEAR))
                .replace("__YELLOW__", hex(YELLOW));
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

    private String row(String key, String value) {
        return "<tr><td>" + escape(key) + "</td><td>" + value + "</td></tr>";
    }

    private void appendStats(StringBuilder b, String title, Map<String, Backtester.StatsBucket> stats) {
        if (stats == null || stats.isEmpty()) return;
        b.append(section(title));
        b.append("<table>");
        stats.entrySet().stream()
                .sorted((a, c) -> Double.compare(c.getValue().expectancy(), a.getValue().expectancy()))
                .limit(8)
                .forEach(entry -> {
                    Backtester.StatsBucket s = entry.getValue();
                    String exp = fmt(s.expectancy()) + "R";
                    String klass = s.expectancy() >= 0 ? "good" : "bad";
                    b.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>")
                            .append("<span class='strong'>").append(s.trades).append(" сдел.</span>")
                            .append(" · ").append(fmt(s.winrate())).append("%")
                            .append(" · <span class='").append(klass).append("'>").append(exp).append("</span>")
                            .append("</td></tr>");
                });
        b.append("</table>");
    }

    private void appendWarnings(StringBuilder b, Backtester.Report report) {
        if (report.winrate < 30) {
            b.append("<div class='note'>Винрейт ниже 30%. Проверь, держится ли R/R хотя бы около 1:3, иначе стратегия может быть математически слабой.</div>");
        }
        if (report.missed > report.executedTrades * 3) {
            b.append("<div class='note'>Много упущенных сделок без касания. Возможно, вход слишком глубоко в зоне и стоит проверить более раннюю точку входа.</div>");
        }
        if (report.tickExecutionCandles == 0 && report.ohlcFallbackCandles > 0) {
            b.append("<div class='note'>Бэктест в основном опирался на OHLC fallback. Для боевой оценки лучше накопить WebSocket/DataLake ticks.</div>");
        }
    }

    private String path(String label, String value) {
        return "<div><span class='pathLabel'>" + escape(label) + ":</span> " + escape(empty(value, "нет")) + "</div>";
    }

    private String num(int value) {
        return String.format(Locale.US, "%,d", value).replace(',', ' ');
    }

    private String fmt(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return String.format(Locale.US, "%.2f", value);
    }

    private String fmt0(double value) {
        if (!Double.isFinite(value)) return "0";
        return String.format(Locale.US, "%.0f", value);
    }

    private String empty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Color winrateColor(double winrate) {
        if (winrate >= 45.0) return BULL;
        if (winrate >= 30.0) return YELLOW;
        return BEAR;
    }

    private String winrateHex(double winrate) {
        if (winrate >= 45.0) return C_GREEN;
        if (winrate >= 30.0) return C_YELLOW;
        return C_RED;
    }

    private String good(String value) {
        return "<span class='good'>" + escape(value) + "</span>";
    }

    private String bad(String value) {
        return "<span class='bad'>" + escape(value) + "</span>";
    }

    private String warn(String value) {
        return "<span class='warn'>" + escape(value) + "</span>";
    }

    private String blue(String value) {
        return "<span class='blue'>" + escape(value) + "</span>";
    }

    private String strong(String value) {
        return "<span class='strong'>" + escape(value) + "</span>";
    }

    private String muted(String value) {
        return "<span class='muted'>" + escape(value) + "</span>";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
