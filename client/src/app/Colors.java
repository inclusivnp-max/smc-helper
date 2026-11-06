package app;

import java.awt.*;

public class Colors {
    public static Color BG        = new Color(19, 23, 34);
    public static Color PANEL_BG  = new Color(30, 34, 45);
    public static Color CARD_BG   = new Color(23, 27, 38);
    public static Color FIELD_BG  = new Color(18, 22, 32);
    public static Color RAIL_BG   = new Color(18, 21, 30);
    public static Color GRID      = new Color(35, 36, 44);
    public static Color FG        = new Color(209, 212, 220);
    public static Color MUTED     = new Color(120, 123, 134);
    public static Color ACCENT    = new Color(41, 98, 255);

    public static final Color BULL      = new Color(38, 166, 154);
    public static final Color BEAR      = new Color(239, 83, 80);
    public static final Color BULL_SOFT = new Color(38, 166, 154, 55);
    public static final Color BEAR_SOFT = new Color(239, 83, 80, 55);
    public static final Color YELLOW    = new Color(255, 193, 7);
    public static final Color CYAN      = new Color(0, 188, 212);
    public static final Color PURPLE    = new Color(156, 39, 176);

    public enum Theme {
        MARINE("Морская",
                new Color(19, 23, 34), new Color(30, 34, 45), new Color(23, 27, 38),
                new Color(18, 22, 32), new Color(18, 21, 30), new Color(42, 46, 57),
                new Color(209, 212, 220), new Color(120, 123, 134), new Color(41, 98, 255)),
        TRADINGVIEW_DARK("TradingView dark",
                new Color(8, 8, 9), new Color(22, 22, 25), new Color(18, 18, 22),
                new Color(14, 14, 17), new Color(12, 12, 15), new Color(38, 38, 44),
                new Color(224, 224, 230), new Color(132, 136, 145), new Color(41, 98, 255)),
        GRAPHITE("Графит",
                new Color(14, 16, 20), new Color(26, 29, 35), new Color(21, 24, 30),
                new Color(17, 20, 25), new Color(16, 18, 23), new Color(48, 54, 64),
                new Color(225, 228, 235), new Color(142, 149, 160), new Color(66, 165, 245)),
        DEEP_TEAL("Темный teal",
                new Color(8, 18, 22), new Color(18, 32, 38), new Color(14, 27, 32),
                new Color(10, 23, 28), new Color(9, 21, 26), new Color(35, 62, 70),
                new Color(218, 232, 234), new Color(128, 156, 160), new Color(0, 188, 212)),
        BURGUNDY_GLASS("Бордовое стекло",
                new Color(13, 8, 11), new Color(31, 14, 20), new Color(24, 11, 17),
                new Color(19, 9, 14), new Color(16, 8, 12), new Color(80, 36, 48),
                new Color(236, 224, 228), new Color(165, 126, 136), new Color(214, 46, 80));

        public final String name;
        public final Color bg;
        public final Color panelBg;
        public final Color cardBg;
        public final Color fieldBg;
        public final Color railBg;
        public final Color grid;
        public final Color fg;
        public final Color muted;
        public final Color accent;

        Theme(String name, Color bg, Color panelBg, Color cardBg, Color fieldBg, Color railBg,
              Color grid, Color fg, Color muted, Color accent) {
            this.name = name;
            this.bg = bg;
            this.panelBg = panelBg;
            this.cardBg = cardBg;
            this.fieldBg = fieldBg;
            this.railBg = railBg;
            this.grid = grid;
            this.fg = fg;
            this.muted = muted;
            this.accent = accent;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static Theme currentTheme = Theme.MARINE;

    public static void applyTheme(Theme theme) {
        if (theme == null) theme = Theme.MARINE;
        currentTheme = theme;
        BG = theme.bg;
        PANEL_BG = theme.panelBg;
        CARD_BG = theme.cardBg;
        FIELD_BG = theme.fieldBg;
        RAIL_BG = theme.railBg;
        GRID = theme.grid;
        FG = theme.fg;
        MUTED = theme.muted;
        ACCENT = theme.accent;
    }

    public static Theme themeAt(int index) {
        Theme[] values = Theme.values();
        if (index < 0 || index >= values.length) return Theme.MARINE;
        return values[index];
    }
}
