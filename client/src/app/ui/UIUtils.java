package app.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import java.awt.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.event.ChangeListener;
import java.io.InputStream;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class UIUtils {
    private static final Map<String, Font> FONT_CACHE = new HashMap<>();
    private static final String SANS_RESOURCE = "/app/fonts/Roboto.ttf";
    private static final String MONO_REGULAR_RESOURCE = "/app/fonts/RobotoMono-Regular.ttf";
    private static final String MONO_MEDIUM_RESOURCE = "/app/fonts/RobotoMono-Medium.ttf";
    private static final String MONO_BOLD_RESOURCE = "/app/fonts/RobotoMono-Bold.ttf";
    public static final String HTML_FONT_STACK = "'Roboto','Segoe UI Variable Text','Segoe UI',Arial,sans-serif";
    public static final String HTML_MONO_FONT_STACK = "'Roboto Mono','Cascadia Mono','Consolas',monospace";

    public static void installLookAndFeel() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("flatlaf.useWindowDecorations", "false");
        try {
            FlatLaf.setup(new FlatDarkLaf());
        } catch (Exception ignored) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignoredToo) {
                // Swing will keep the default cross-platform Look&Feel.
            }
        }
        applyGlobalUi();
    }

    public static void applyGlobalUi() {
        applyThemeDefaults();
        Font ui = bodyFont(13f);
        java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, new javax.swing.plaf.FontUIResource(ui));
            }
        }
        putFont("Label.font", bodyFont(13f));
        putFont("Button.font", buttonFont(13f));
        putFont("ToggleButton.font", buttonFont(13f));
        putFont("ComboBox.font", labelFont(12.4f));
        putFont("TabbedPane.font", labelFont(12.4f));
        putFont("TextField.font", bodyFont(13f));
        putFont("PasswordField.font", bodyFont(13f));
        putFont("TextArea.font", bodyFont(13f));
        putFont("TextPane.font", bodyFont(13f));
        putFont("EditorPane.font", bodyFont(13f));
        putFont("List.font", bodyFont(13f));
        putFont("Table.font", bodyFont(12.6f));
        putFont("TableHeader.font", labelFont(11.8f));
        putFont("ToolTip.font", captionFont(11.8f));
    }

    private static void putFont(String key, Font font) {
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
    }

    private static void applyThemeDefaults() {
        UIManager.put("Panel.background", app.Colors.PANEL_BG);
        UIManager.put("Viewport.background", app.Colors.BG);
        UIManager.put("ScrollPane.background", app.Colors.BG);
        UIManager.put("ScrollBar.track", app.Colors.BG);
        UIManager.put("ScrollBar.thumb", app.Colors.MUTED);
        UIManager.put("Button.background", app.Colors.ACCENT);
        UIManager.put("Button.foreground", Color.WHITE);
        UIManager.put("Button.disabledText", new Color(232, 236, 244));
        UIManager.put("Button.arc", 6);
        UIManager.put("Component.arc", 6);
        UIManager.put("TextComponent.arc", 4);
        UIManager.put("ComboBox.background", app.Colors.FIELD_BG);
        UIManager.put("ComboBox.foreground", app.Colors.FG);
        UIManager.put("ComboBox.selectionBackground", app.Colors.ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("TextField.background", app.Colors.FIELD_BG);
        UIManager.put("TextField.foreground", app.Colors.FG);
        UIManager.put("TextField.caretForeground", app.Colors.FG);
        UIManager.put("Label.foreground", app.Colors.FG);
        UIManager.put("TabbedPane.background", app.Colors.PANEL_BG);
        UIManager.put("TabbedPane.foreground", app.Colors.FG);
        UIManager.put("TabbedPane.selectedBackground", app.Colors.BG);
        UIManager.put("TabbedPane.underlineColor", app.Colors.ACCENT);
        UIManager.put("OptionPane.background", app.Colors.PANEL_BG);
        UIManager.put("OptionPane.messageForeground", app.Colors.FG);
    }

    public static Font pickUiFont(float size) {
        Font bundled = bundledFont(SANS_RESOURCE, Font.PLAIN, softenSize(size));
        if (bundled != null) return withEmojiFallback(bundled);
        String[] candidates = {"Roboto", "Segoe UI Variable Text", "Segoe UI Variable", "Inter", "Segoe UI", "SF Pro Text", "Helvetica Neue", "Noto Sans", "Arial"};
        return withEmojiFallback(firstAvailable(candidates, Font.PLAIN, softenSize(size)));
    }

    public static Font tradingFont(float size, int style) {
        Font bundled = bundledFont(SANS_RESOURCE, style, softenSize(size));
        if (bundled != null) return withEmojiFallback(bundled);
        String[] candidates = {"Roboto", "Segoe UI Variable Text", "Segoe UI Variable", "Inter", "Segoe UI", "SF Pro Text", "Arial"};
        return withEmojiFallback(firstAvailable(candidates, style, softenSize(size)));
    }

    public static Font tradingMonoFont(float size, int style) {
        boolean bold = (style & Font.BOLD) != 0;
        String resource = bold
                ? (size < 12.5f ? MONO_MEDIUM_RESOURCE : MONO_BOLD_RESOURCE)
                : MONO_REGULAR_RESOURCE;
        int renderStyle = bold && size < 12.5f ? (style & ~Font.BOLD) : style;
        Font bundled = bundledFont(resource, renderStyle, softenSize(size));
        if (bundled != null) return bundled;
        String[] candidates = {"Roboto Mono", "Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Consolas", "Menlo", "Monospaced"};
        return firstAvailable(candidates, style, softenSize(size));
    }

    public static Font displayFont(float size) {
        return tradingFont(size, Font.BOLD);
    }

    public static Font titleFont(float size) {
        return tradingFont(size, Font.BOLD);
    }

    public static Font bodyFont(float size) {
        return tradingFont(size, Font.PLAIN);
    }

    public static Font labelFont(float size) {
        return tradingFont(size, Font.BOLD);
    }

    public static Font captionFont(float size) {
        return tradingFont(size, Font.PLAIN);
    }

    public static Font buttonFont(float size) {
        return tradingFont(size, Font.BOLD);
    }

    public static Font monoFont(float size, int style) {
        return tradingMonoFont(size, style);
    }

    public static void applyTextRendering(Graphics2D g) {
        if (g == null) return;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static Font firstAvailable(String[] names, int style, float size) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Set<String> available = new HashSet<>(Arrays.asList(ge.getAvailableFontFamilyNames()));
        for (String n : names) {
            if (available.contains(n)) {
                Font base = new Font(n, Font.PLAIN, Math.round(size));
                String lower = n.toLowerCase(java.util.Locale.ROOT);
                return deriveReadableFont(base, style, size, lower.contains("mono") || lower.contains("code") || lower.contains("consolas"));
            }
        }
        return deriveReadableFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size)), style, size, false);
    }

    private static Font bundledFont(String resource, int style, float size) {
        Font base = FONT_CACHE.computeIfAbsent(resource, UIUtils::loadBundledFont);
        if (base == null) return null;
        return deriveReadableFont(base, style, size, resource.contains("Mono"));
    }

    private static Font loadBundledFont(String resource) {
        try (InputStream in = UIUtils.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static float softenSize(float size) {
        if (size < 10.0f) return 10.8f;
        if (size <= 10.5f) return 11.0f;
        if (size < 12.0f) return size + 0.25f;
        return size;
    }

    private static Font withEmojiFallback(Font base) {
        return base;
    }

    private static Font deriveReadableFont(Font base, int style, float size, boolean mono) {
        Map<TextAttribute, Object> attrs = new HashMap<>(base.getAttributes());
        attrs.put(TextAttribute.SIZE, size);
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attrs.put(TextAttribute.POSTURE, (style & Font.ITALIC) != 0
                ? TextAttribute.POSTURE_OBLIQUE
                : TextAttribute.POSTURE_REGULAR);
        attrs.put(TextAttribute.WEIGHT, weightFor(style, size, mono));
        return base.deriveFont(attrs);
    }

    private static Float weightFor(int style, float size, boolean mono) {
        if ((style & Font.BOLD) == 0) return TextAttribute.WEIGHT_REGULAR;
        if (mono && size < 12.5f) return TextAttribute.WEIGHT_MEDIUM;
        if (size < 11.5f) return TextAttribute.WEIGHT_SEMIBOLD;
        if (size < 14.0f) return TextAttribute.WEIGHT_MEDIUM;
        return TextAttribute.WEIGHT_BOLD;
    }

    public static void stylePrimaryButton(AbstractButton b, Color base) {
        if (b == null) return;
        Object oldListener = b.getClientProperty("uiutils.primaryButton.listener");
        if (oldListener instanceof ChangeListener listener) {
            b.removeChangeListener(listener);
        }
        b.setForeground(Color.WHITE);
        b.setBackground(base);
        b.setDisabledIcon(null);
        b.setUI(new BasicButtonUI() {
            @Override
            protected void paintText(Graphics g, JComponent c, Rectangle textRect, String text) {
                if (g instanceof Graphics2D g2) applyTextRendering(g2);
                AbstractButton button = (AbstractButton) c;
                FontMetrics fm = g.getFontMetrics();
                g.setColor(button.isEnabled() ? button.getForeground() : new Color(232, 236, 244));
                g.drawString(text,
                        textRect.x + getTextShiftOffset(),
                        textRect.y + fm.getAscent() + getTextShiftOffset());
            }
        });
        b.setFont(buttonFont(13f));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setMargin(new Insets(6, 14, 6, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(base.darker(), 1, true), BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        ChangeListener listener = e -> {
            ButtonModel m = b.getModel();
            if (!b.isEnabled()) {
                b.setForeground(new Color(232, 236, 244));
                b.setBackground(disabledButtonColor(base));
                return;
            }
            b.setForeground(Color.WHITE);
            if (m.isPressed() || m.isSelected()) b.setBackground(base.darker());
            else if (m.isRollover()) b.setBackground(base.brighter());
            else b.setBackground(base);
        };
        b.addChangeListener(listener);
        b.putClientProperty("uiutils.primaryButton.listener", listener);
    }

    private static Color disabledButtonColor(Color base) {
        return new Color(
                Math.max(58, (int) (base.getRed() * 0.62)),
                Math.max(64, (int) (base.getGreen() * 0.62)),
                Math.max(74, (int) (base.getBlue() * 0.62))
        );
    }

    public static void styleComboBox(JComboBox<?> box, Color bg, Color fg, Color border) {
        box.setBackground(bg);
        box.setForeground(fg);
        box.setFont(labelFont(12.4f));
        box.setFocusable(false);
        box.setOpaque(true);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        box.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                return new JButton() {
                    {
                        setBorder(BorderFactory.createEmptyBorder());
                        setFocusPainted(false);
                        setFocusable(false);
                        setContentAreaFilled(false);
                        setOpaque(true);
                        setPreferredSize(new Dimension(28, 24));
                    }

                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        applyTextRendering(g2);
                        g2.setColor(bg);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(border);
                        g2.drawLine(0, 5, 0, getHeight() - 6);
                        g2.setColor(fg);
                        int cx = getWidth() / 2;
                        int cy = getHeight() / 2 + 1;
                        int[] xs = {cx - 4, cx + 4, cx};
                        int[] ys = {cy - 2, cy - 2, cy + 3};
                        g2.fillPolygon(xs, ys, 3);
                        g2.dispose();
                    }
                };
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(bg);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            protected ComboPopup createPopup() {
                BasicComboPopup popup = new BasicComboPopup(comboBox) {
                    @Override
                    protected JScrollPane createScroller() {
                        JScrollPane scroller = new JScrollPane(list,
                                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                        scroller.setBorder(BorderFactory.createEmptyBorder());
                        scroller.setBackground(bg);
                        scroller.getViewport().setBackground(bg);
                        scroller.getVerticalScrollBar().setUI(new ModernScrollBarUI());
                        scroller.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
                        scroller.getVerticalScrollBar().setUnitIncrement(18);
                        scroller.getHorizontalScrollBar().setUI(new ModernScrollBarUI());
                        scroller.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));
                        return scroller;
                    }
                };
                popup.getList().setBackground(bg);
                popup.getList().setForeground(fg);
                popup.getList().setSelectionBackground(border);
                popup.getList().setSelectionForeground(Color.WHITE);
                popup.setBackground(bg);
                popup.setBorder(BorderFactory.createLineBorder(border));
                return popup;
            }

            @Override
            public void configureEditor() {
                super.configureEditor();
                if (editor != null) {
                    Component c = editor;
                    c.setBackground(bg);
                    c.setForeground(fg);
                    c.setFont(labelFont(12.4f));
                    if (c instanceof JComponent jc) {
                        jc.setOpaque(true);
                        jc.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                    }
                }
            }
        });
        box.setBackground(bg);
        box.setForeground(fg);
        if (box.isEditable() && box.getEditor() != null) {
            Component editor = box.getEditor().getEditorComponent();
            editor.setBackground(bg);
            editor.setForeground(fg);
            editor.setFont(labelFont(12.4f));
            if (editor instanceof JComponent jc) {
                jc.setOpaque(true);
                jc.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
            }
        }
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setFont(labelFont(12.4f));
                label.setOpaque(true);
                label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                label.setBackground(isSelected ? border : bg);
                label.setForeground(fg);
                return label;
            }
        });
    }

    public static void styleTabbedPane(JTabbedPane tabs, Color bg, Color selectedBg,
                                       Color fg, Color muted, Color accent) {
        tabs.setOpaque(true);
        tabs.setBackground(bg);
        tabs.setForeground(fg);
        tabs.setFocusable(false);
        tabs.setFont(labelFont(12.4f));
        tabs.setUI(new BasicTabbedPaneUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                tabAreaInsets = new Insets(0, 0, 0, 0);
                tabInsets = new Insets(7, 10, 7, 10);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                contentBorderInsets = new Insets(0, 0, 0, 0);
            }

            @Override
            protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                              int x, int y, int w, int h, boolean isSelected) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(isSelected ? selectedBg : bg);
                g2.fillRect(x, y, w, h);
                if (isSelected) {
                    g2.setColor(accent);
                    g2.fillRect(x, y + h - 2, w, 2);
                }
                g2.dispose();
            }

            @Override
            protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                          int x, int y, int w, int h, boolean isSelected) {
                g.setColor(borderColor(bg, accent));
                g.drawRect(x, y, w, h);
            }

            @Override
            protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                if (tabs.getTabCount() == 0 || selectedIndex < 0) return;
                g.setColor(borderColor(bg, accent));
                Rectangle selected = tabs.getBoundsAt(selectedIndex);
                if (selected == null) return;
                int y = selected.y + selected.height;
                g.drawLine(0, y, tabs.getWidth(), y);
            }

            @Override
            protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics,
                                     int tabIndex, String title, Rectangle textRect, boolean isSelected) {
                if (g instanceof Graphics2D g2) applyTextRendering(g2);
                g.setFont(font);
                g.setColor(isSelected ? fg : muted);
                g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
            }
        });
    }

    private static Color borderColor(Color bg, Color accent) {
        if (app.Colors.GRID != null) return app.Colors.GRID;
        return accent == null ? bg : accent;
    }

    public static void styleScrollPane(JScrollPane sp, Color gridColor) {
        sp.setBorder(BorderFactory.createLineBorder(gridColor));
        sp.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        sp.getHorizontalScrollBar().setUI(new ModernScrollBarUI());
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
    }
}
