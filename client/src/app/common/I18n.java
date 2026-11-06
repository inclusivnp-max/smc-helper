package app.common;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class I18n {
    private static final String PREF_LANGUAGE = "ui.language";
    private static final String KEY_PROPERTY = "i18n.source";
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> REVERSE_EN = new HashMap<>();

    static {
        put("Русский", "Russian");
        put("English", "English");
        put("Монета", "Symbol");
        put("Инструмент:", "Symbol:");
        put("Рынок:", "Market:");
        put("Метод:", "Method:");
        put("График:", "Chart:");
        put("Бэктест:", "Backtest:");
        put("История:", "History:");
        put("Действия:", "Actions:");
        put("Справка", "Help");
        put("Прогноз", "Forecast");
        put("Обновить", "Refresh");
        put("ИИ Прогноз", "AI Forecast");
        put("Бэктест", "Backtest");
        put("Радар", "Radar");
        put("Инфра", "Infra");
        put("Аккаунт", "Account");
        put("План торговли", "Trade Plan");
        put("Стратегии", "Strategies");
        put("Открыть лонг", "Open Long");
        put("Открыть шорт", "Open Short");
        put("Закрыть позицию", "Close Position");
        put("Монеты", "Symbols");
        put("Итог стратегий: ждем график", "Strategy summary: waiting for chart");
        put("Колесо — зум  •  ЛКМ + перетаскивание — панорама  •  2× клик по зоне — детали",
                "Wheel - zoom  •  Left drag - pan  •  Double-click a zone - details");
        put("План", "Plan");
        put("Анализ", "Analysis");
        put("Позиции", "Positions");
        put("Ликвидность", "Liquidity");
        put("Поток", "Flow");
        put("Детали", "Details");
        put("Настройки", "Settings");
        put("Тема оформления", "Theme");
        put("Цветовая схема приложения.", "Application color scheme.");
        put("Язык", "Language");
        put("Язык интерфейса. Изменения применяются сразу.", "Interface language. Changes apply immediately.");
        put("График", "Chart");
        put("Ручной масштаб без автоцентрирования.", "Manual scale without auto-centering.");
        put("Зафиксировать цена/бар", "Lock price/bar ratio");
        put("Колесо - zoom, drag - панорама, Shift+колесо - вертикальный шаг.", "Wheel - zoom, drag - pan, Shift+wheel - vertical step.");
        put("Свечи", "Candles");
        put("Цвет тела и фитилей японских свечей.", "Body and wick colors for candlesticks.");
        put("Бычья свеча", "Bullish candle");
        put("Медвежья свеча", "Bearish candle");
        put("Нейтральная свеча", "Neutral candle");
        put("Фитили", "Wicks");
        put("Фитили цветом тела", "Wicks follow body color");
        put("Сбросить цвета свечей", "Reset candle colors");
        put("Слои графика", "Chart Layers");
        put("FVG, OB, OTE, ликвидность, вход и ценовая линия.", "FVG, OB, OTE, liquidity, entry and price line.");
        put("FVG long", "FVG long");
        put("FVG short", "FVG short");
        put("OB long", "OB long");
        put("OB short", "OB short");
        put("OTE", "OTE");
        put("Entry line", "Entry line");
        put("Stop line", "Stop line");
        put("Target/TP line", "Target/TP line");
        put("Current price line", "Current price line");
        put("BSL", "BSL");
        put("SSL", "SSL");
        put("Displacement long", "Displacement long");
        put("Displacement short", "Displacement short");
        put("Sweep marker", "Sweep marker");
        put("IDM marker", "IDM marker");
        put("Сбросить цвета слоев", "Reset layer colors");
        put("Зоны", "Zones");
        put("Заливка отдельно от подписей.", "Fill opacity is separate from labels.");
        put("FVG: ценовой разрыв", "FVG: price gap");
        put("OB: order block", "OB: order block");
        put("OTE: зона отката", "OTE: retracement zone");
        put("Подписи OB/FVG/OTE", "OB/FVG/OTE labels");
        put("События", "Events");
        put("Линии и маркеры без влияния на зоны.", "Lines and markers independent from zones.");
        put("BSL / SSL линии", "BSL / SSL lines");
        put("Sweep / IDM метки", "Sweep / IDM markers");
        put("Если график перегружен: FVG/OB 20-35%, подписи 45-70%. Подпись OTE тоже регулируется этим слайдером.",
                "If the chart is crowded: FVG/OB 20-35%, labels 45-70%. OTE labels use the same slider.");
        put("Радар входов", "Entry Radar");
        put("Фильтры", "Filters");
        put("Запустить", "Start");
        put("Остановить", "Stop");
        put("Открыть сетап", "Open Setup");
        put("Обновить текущий список", "Refresh Current List");
        put("Фильтры радара", "Radar Filters");
        put("Фильтры качества входа", "Entry Quality Filters");
        put("Радар покажет только сетапы, которые проходят SMC score и выбранные confluence-факторы.",
                "Radar shows only setups that pass SMC score and selected confluence factors.");
        put("Минимальный SMC", "Minimum SMC");
        put("Факторы confluence", "Confluence Factors");
        put("Smart-money models", "Smart-money Models");
        put("Статистика бэктеста", "Backtest Statistics");
        put("Минимум похожих сделок", "Minimum similar trades");
        put("Минимальное expectancy, R", "Minimum expectancy, R");
        put("Отмена", "Cancel");
        put("Применить", "Apply");
        put("HTF по направлению сделки", "HTF aligned with trade direction");
        put("Снятие ликвидности перед входом", "Liquidity sweep before entry");
        put("Displacement 60+ после импульса", "Displacement 60+ after impulse");
        put("Свежий CHoCH/BOS body close", "Fresh CHoCH/BOS body close");
        put("Качественная OB/FVG зона 70+", "Quality OB/FVG zone 70+");
        put("Объем / delta confirmation 45+", "Volume / delta confirmation 45+");
        put("Не торговать низковолатильный chop", "Avoid low-volatility chop");
        put("Осталось до входа не больше 0.85%", "Entry distance no more than 0.85%");
        put("Только активная killzone/session", "Only active killzone/session");
        put("Deviation / smart-money trap", "Deviation / smart-money trap");
        put("Imbalance по направлению сделки", "Imbalance aligned with trade");
        put("Фильтр фундаментального/event risk", "Fundamental/event risk filter");
        put("Отсекать новостные/аномальные импульсы", "Reject news/abnormal impulses");
        put("Только smart-money model (BRK/IFVG/PROP/MIT/A+/L2L)", "Only smart-money model (BRK/IFVG/PROP/MIT/A+/L2L)");
        put("Только A+ полная цепочка", "Only full A+ chain");
        put("Требовать цель ликвидности L2L", "Require L2L liquidity target");
        put("Требовать premium/discount alignment", "Require premium/discount alignment");
        put("Использовать статистику похожих бэктест-сетапов", "Use similar backtest setup statistics");
        put("Ожидание запуска...", "Waiting to start...");
        put("Список монет", "Symbol List");
        put("+ Добавить", "+ Add");
        put("✕ Удалить", "✕ Remove");
        put("Сбросить", "Reset");
        put("Инструменты для анализа и радара", "Symbols for analysis and radar");
        put("Изменения применятся сразу и сохранятся при перезапуске.", "Changes apply immediately and persist after restart.");
    }

    private I18n() {
    }

    public enum Language {
        RU("ru", "Русский"),
        ENGLISH("en", "English");

        public final String code;
        private final String nativeName;

        Language(String code, String nativeName) {
            this.code = code;
            this.nativeName = nativeName;
        }

        public String displayName() {
            if (this == RU) return english() ? "Russian" : "Русский";
            return "English";
        }

        @Override
        public String toString() {
            return displayName();
        }

        static Language from(String value) {
            String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return text.startsWith("en") ? ENGLISH : RU;
        }
    }

    public static Language language() {
        return Language.from(UiSettings.get().getString(PREF_LANGUAGE, Language.RU.code));
    }

    public static void setLanguage(Language language) {
        UiSettings.get().putString(PREF_LANGUAGE, (language == null ? Language.RU : language).code);
    }

    public static boolean english() {
        return language() == Language.ENGLISH;
    }

    public static String t(String source) {
        if (source == null) return "";
        if (!english()) return source;
        return EN.getOrDefault(source, source);
    }

    public static void bind(AbstractButton button, String source) {
        if (button == null) return;
        button.putClientProperty(KEY_PROPERTY, source);
        button.setText(t(source));
    }

    public static void bind(JLabel label, String source) {
        if (label == null) return;
        label.putClientProperty(KEY_PROPERTY, source);
        label.setText(t(source));
    }

    public static void refreshTree(Component component) {
        if (component == null) return;
        if (component instanceof AbstractButton button) {
            String source = source(button, button.getText());
            if (source != null) button.setText(t(source));
        } else if (component instanceof JLabel label) {
            String source = source(label, label.getText());
            if (source != null) label.setText(t(source));
        } else if (component instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String source = source(tabs, "tab." + i, tabs.getTitleAt(i));
                if (source != null) tabs.setTitleAt(i, t(source));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) refreshTree(child);
        }
    }

    private static String source(JComponent component, String current) {
        Object value = component.getClientProperty(KEY_PROPERTY);
        if (value instanceof String text) return text;
        String source = sourceText(current);
        if (source != null) component.putClientProperty(KEY_PROPERTY, source);
        return source;
    }

    private static String source(JComponent component, String property, String current) {
        Object value = component.getClientProperty(KEY_PROPERTY + "." + property);
        if (value instanceof String text) return text;
        String source = sourceText(current);
        if (source != null) component.putClientProperty(KEY_PROPERTY + "." + property, source);
        return source;
    }

    private static String sourceText(String current) {
        if (current == null || current.isBlank()) return null;
        if (EN.containsKey(current)) return current;
        return REVERSE_EN.get(current);
    }

    private static void put(String ru, String en) {
        EN.put(ru, en);
        REVERSE_EN.put(en, ru);
    }
}
