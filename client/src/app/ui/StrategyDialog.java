package app.ui;

import app.model.AnalysisResult;
import app.strategy.StrategyEngine;
import app.strategy.StrategySignal;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

import static app.Colors.*;

public class StrategyDialog extends JDialog {
    private final List<StrategySignal> strategies;
    private final JList<StrategySignal> strategyList;
    private final JEditorPane details = new JEditorPane();
    private final Consumer<StrategySignal> onApply;

    public static void showDialog(JFrame parent, AnalysisResult result, Consumer<StrategySignal> onApply) {
        StrategyDialog dialog = new StrategyDialog(parent, result, onApply);
        dialog.setVisible(true);
    }

    private StrategyDialog(JFrame parent, AnalysisResult result, Consumer<StrategySignal> onApply) {
        super(parent, "Стратегии графика", false);
        this.strategies = StrategyEngine.evaluateAll(result);
        this.onApply = onApply;
        this.strategyList = new JList<>(new DefaultListModel<>());
        setSize(780, 560);
        setMinimumSize(new Dimension(680, 460));
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);
        setContentPane(buildRoot());
        fillList();
        if (!strategies.isEmpty()) strategyList.setSelectedIndex(0);
    }

    private JComponent buildRoot() {
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(PANEL_BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        JPanel header = new JPanel(new BorderLayout(10, 4));
        header.setOpaque(false);
        JLabel title = new JLabel("Стратегии к текущему графику");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(20f));
        JLabel subtitle = new JLabel("Выбери модель. Если текущий сетап не подходит, программа покажет причину.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));
        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(4));
        titleBox.add(subtitle);
        header.add(titleBox, BorderLayout.CENTER);

        strategyList.setCellRenderer(new StrategyRenderer());
        strategyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        strategyList.setBackground(BG);
        strategyList.setForeground(FG);
        strategyList.setFixedCellHeight(72);
        strategyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateDetails();
        });
        JScrollPane listScroll = new JScrollPane(strategyList);
        UIUtils.styleScrollPane(listScroll, GRID);
        listScroll.setPreferredSize(new Dimension(285, 0));

        details.setContentType("text/html");
        details.setEditable(false);
        details.setOpaque(true);
        details.setBackground(BG);
        details.setForeground(FG);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        details.setFont(UIUtils.bodyFont(12.5f));
        JScrollPane detailScroll = new JScrollPane(details);
        UIUtils.styleScrollPane(detailScroll, GRID);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setResizeWeight(0.36);
        split.setBorder(BorderFactory.createLineBorder(GRID));
        split.setDividerSize(1);
        split.setBackground(PANEL_BG);

        JButton clear = new JButton("Убрать слой");
        JButton close = new JButton("Закрыть");
        JButton apply = new JButton("Показать на графике");
        UIUtils.stylePrimaryButton(clear, FIELD_BG);
        UIUtils.stylePrimaryButton(close, FIELD_BG);
        UIUtils.stylePrimaryButton(apply, ACCENT);
        clear.addActionListener(e -> {
            if (onApply != null) onApply.accept(null);
            dispose();
        });
        close.addActionListener(e -> dispose());
        apply.addActionListener(e -> {
            StrategySignal selected = strategyList.getSelectedValue();
            if (selected != null && onApply != null) onApply.accept(selected);
            dispose();
        });

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(clear);
        footer.add(close);
        footer.add(apply);

        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void fillList() {
        DefaultListModel<StrategySignal> model = (DefaultListModel<StrategySignal>) strategyList.getModel();
        model.clear();
        for (StrategySignal strategy : strategies) model.addElement(strategy);
    }

    private void updateDetails() {
        StrategySignal signal = strategyList.getSelectedValue();
        details.setText(html(signal));
        details.setCaretPosition(0);
    }

    private String html(StrategySignal s) {
        if (s == null) {
            return wrap("<div class='empty'>Выбери стратегию слева.</div>");
        }
        StringBuilder b = new StringBuilder();
        String side = s.longSide ? "LONG" : "SHORT";
        String statusClass = s.suitable ? "good" : "warn";
        b.append("<div class='hero'>")
                .append("<div class='kicker'>").append(escape(s.statusText())).append("</div>")
                .append("<h2>").append(escape(s.name)).append("</h2>")
                .append("<p>").append(escape(s.description)).append("</p>")
                .append("<div class='verdict ").append(statusClass).append("'>")
                .append(escape(s.verdict)).append("</div>")
                .append("<div class='action'>").append(escape(s.action)).append(" · ").append(side).append("</div>")
                .append("</div>");

        appendList(b, "Что совпало", s.confirmations, "good");
        appendList(b, "Что мешает", s.blockers, "bad");
        b.append("<h3>Что будет на графике</h3>");
        if (s.suitable) {
            b.append("<p>Появится отдельная рамка стратегии и ключевые уровни: entry, stop, targets и дополнительные триггеры.</p>");
        } else {
            b.append("<p>Появится предупреждающая рамка без торгового оверлея уровней. Это сигнал не применять модель к текущему сетапу.</p>");
        }
        return wrap(b.toString());
    }

    private void appendList(StringBuilder b, String title, List<String> items, String cssClass) {
        b.append("<h3>").append(escape(title)).append("</h3>");
        if (items == null || items.isEmpty()) {
            b.append("<div class='empty'>Нет пунктов.</div>");
            return;
        }
        b.append("<ul>");
        for (String item : items) {
            b.append("<li class='").append(cssClass).append("'>").append(escape(item)).append("</li>");
        }
        b.append("</ul>");
    }

    private String wrap(String body) {
        return """
                <html><head><style>
                body { font-family: __FONT__; font-size: 12.5px; color: __FG__; background: __BG__; margin: 14px; line-height: 1.5; }
                h2 { color: __ACCENT__; font-size: 18px; margin: 2px 0 7px 0; }
                h3 { color: __YELLOW__; font-size: 13.5px; margin: 14px 0 6px 0; }
                p { color: __MUTED__; margin: 5px 0 8px 0; }
                ul { margin-top: 4px; padding-left: 18px; }
                li { margin-bottom: 5px; }
                .hero { background: __CARD__; border: 1px solid __GRID__; padding: 12px 14px; margin-bottom: 10px; }
                .kicker { color: __MUTED__; font-size: 10px; text-transform: uppercase; }
                .verdict { font-weight: 800; margin-top: 8px; }
                .action { color: __FG__; margin-top: 5px; font-weight: 700; }
                .good { color: __BULL__; }
                .bad { color: __BEAR__; }
                .warn { color: __YELLOW__; }
                .empty { color: __MUTED__; background: __CARD__; border: 1px solid __GRID__; padding: 9px 10px; }
                </style></head><body>
                """
                .replace("__FONT__", UIUtils.HTML_FONT_STACK)
                .replace("__FG__", hex(FG))
                .replace("__BG__", hex(BG))
                .replace("__CARD__", hex(CARD_BG))
                .replace("__GRID__", hex(GRID))
                .replace("__MUTED__", hex(MUTED))
                .replace("__ACCENT__", hex(ACCENT))
                .replace("__BULL__", hex(BULL))
                .replace("__BEAR__", hex(BEAR))
                .replace("__YELLOW__", hex(YELLOW))
                + body + "</body></html>";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static class StrategyRenderer extends JPanel implements ListCellRenderer<StrategySignal> {
        private final JLabel title = new JLabel();
        private final JLabel subtitle = new JLabel();
        private final JLabel badge = new JLabel();

        StrategyRenderer() {
            setLayout(new BorderLayout(8, 3));
            setBorder(new EmptyBorder(8, 10, 8, 10));
            setOpaque(true);
            title.setFont(UIUtils.labelFont(13f));
            subtitle.setFont(UIUtils.captionFont(11.6f));
            badge.setFont(UIUtils.labelFont(11.4f));
            badge.setBorder(new EmptyBorder(4, 7, 4, 7));
            badge.setOpaque(true);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(title);
            text.add(Box.createVerticalStrut(5));
            text.add(subtitle);
            add(text, BorderLayout.CENTER);
            add(badge, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends StrategySignal> list,
                                                      StrategySignal value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            setBackground(isSelected ? ACCENT.darker() : BG);
            Color tone = value.suitable ? (value.longSide ? BULL : BEAR) : YELLOW;
            title.setForeground(tone);
            subtitle.setForeground(MUTED);
            title.setText(value.name);
            subtitle.setText(value.suitable ? value.action : firstBlocker(value));
            badge.setText(value.score + "/100");
            badge.setBackground(tone);
            badge.setForeground(Color.BLACK);
            return this;
        }

        private String firstBlocker(StrategySignal value) {
            if (value.blockers.isEmpty()) return "не подходит";
            return value.blockers.get(0);
        }
    }
}
