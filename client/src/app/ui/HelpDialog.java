package app.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import static app.Colors.*;

public class HelpDialog extends JDialog {

    public static void showHelp(JFrame parent) {
        HelpDialog dialog = new HelpDialog(parent);
        dialog.setVisible(true);
    }

    private HelpDialog(JFrame parent) {
        super(parent, "Руководство пользователя", true);
        setSize(940, 740);
        setMinimumSize(new Dimension(760, 580));
        setResizable(true);
        setLocationRelativeTo(parent);
        AppIcons.install(this);
        WindowsTitleBar.applyAppTheme(this);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(PANEL_BG);
        mainPanel.setBorder(BorderFactory.createLineBorder(GRID, 1));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(BG);
        header.setBorder(new EmptyBorder(16, 22, 14, 18));

        JLabel title = new JLabel("Руководство пользователя SMC Chart");
        title.setForeground(ACCENT);
        title.setFont(UIUtils.displayFont(21f));

        JLabel subtitle = new JLabel("Функции, алгоритмы и логика принятия торговых решений");
        subtitle.setForeground(MUTED);
        subtitle.setFont(UIUtils.captionFont(12.2f));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(4));
        titleBox.add(subtitle);
        header.add(titleBox, BorderLayout.WEST);

        JEditorPane textPane = new WrapEditorPane();
        textPane.setContentType("text/html");
        textPane.setEditable(false);
        textPane.setOpaque(true);
        textPane.setBackground(PANEL_BG);
        textPane.setForeground(FG);
        textPane.setFont(UIUtils.bodyFont(13f));
        textPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        textPane.setText(helpHtml());
        textPane.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(textPane);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(PANEL_BG);
        UIUtils.styleScrollPane(scroll, GRID);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JButton ok = new JButton("Понятно");
        UIUtils.stylePrimaryButton(ok, ACCENT);
        ok.addActionListener(e -> dispose());

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setBackground(PANEL_BG);
        footer.setBorder(new EmptyBorder(12, 20, 18, 20));
        footer.add(ok);

        mainPanel.add(header, BorderLayout.NORTH);
        mainPanel.add(scroll, BorderLayout.CENTER);
        mainPanel.add(footer, BorderLayout.SOUTH);
        add(mainPanel);
    }

    private String helpHtml() {
        return """
                <html><head><style>
                body {
                    font-family: __FONT__;
                    font-size: 12.5px;
                    color: __FG__;
                    margin: 18px 22px 22px 22px;
                    line-height: 1.5;
                    background: __PANEL__;
                }
                h2 {
                    color: __ACCENT__;
                    font-size: 17px;
                    margin: 18px 0 8px 0;
                }
                h3 {
                    color: __YELLOW__;
                    font-size: 13.5px;
                    margin: 12px 0 6px 0;
                }
                p { margin: 4px 0 9px 0; color: __MUTED__; }
                ul { margin-top: 4px; padding-left: 18px; }
                li { margin-bottom: 5px; }
                .card {
                    background: __CARD__;
                    border: 1px solid __GRID__;
                    padding: 12px 14px;
                    margin: 10px 0;
                }
                .good { color: __BULL__; font-weight: 700; }
                .bad { color: __BEAR__; font-weight: 700; }
                .accent { color: __ACCENT__; font-weight: 700; }
                .warn { color: __YELLOW__; font-weight: 700; }
                .muted { color: __MUTED__; }
                table { width: 100%; border-collapse: collapse; margin-top: 6px; }
                td { border-bottom: 1px solid __GRID__; padding: 6px 4px; vertical-align: top; }
                td:first-child { color: __MUTED__; width: 185px; font-weight: 700; }
                code { color: __YELLOW__; }
                </style></head><body>

                <div class='card'>
                    <h2>Назначение программы</h2>
                    <p><b>SMC Chart</b> - десктопный терминал для поиска и проверки smart-money сетапов на крипторынке.
                    Он объединяет график, SMC-разметку, радар, прогноз сделки, бэктест, order-flow, риск-контроль,
                    исполнение через биржевые API и серверную авторизацию доступа.</p>
                    <p>Идея программы: не входить от одного касания зоны. Сначала строится контекст HTF, затем ищется
                    ликвидность, sweep, displacement, CHoCH/BOS, возврат в OB/FVG/OTE и только после этого формируется
                    торговый план с entry, SL, TP и оценкой качества.</p>
                </div>

                <h2>Основной функционал</h2>
                <table>
                    <tr><td>График</td><td>Собственный Swing-рендер свечей, зон, ликвидности, CVD, footprint-полос, прогноза, TP/SL и текущей цены. Есть масштабирование, панорама, вкладки пар и режим фиксации соотношения цена/бар.</td></tr>
                    <tr><td>Multi-timeframe</td><td>Разделяет HTF и LTF: HTF задает направление и контекст, LTF ищет точку входа и подтверждение. При смене таймфрейма прогноз синхронизируется с активной парой.</td></tr>
                    <tr><td>SMC-анализ</td><td>Ищет BSL/SSL, sweep, inducement, displacement, CHoCH/BOS, OB, FVG, OTE, premium/discount и структуру рынка.</td></tr>
                    <tr><td>Радар</td><td>Сканирует список монет и связки HTF/LTF, применяет фильтры confluence, статистику бэктеста, режим рынка и расстояние до entry. Найденная пара открывает график с прогнозом.</td></tr>
                    <tr><td>Прогноз</td><td>Строит execution score, entry/SL/TP, примерное время до целей, предупреждение о позднем входе и сигнал “лучше закрыть”, если сценарий теряет смысл.</td></tr>
                    <tr><td>Бэктест</td><td>Проверяет сетапы на истории, считает winrate, expectancy, R-result, Profit Factor, drawdown, частичные TP и открытые сделки в конце истории.</td></tr>
                    <tr><td>Data Lake</td><td>Локально хранит тики, aggTrades, стакан и derivatives-контекст. Тиковый бэктест использует эти данные, когда они накоплены.</td></tr>
                    <tr><td>Order Flow</td><td>Показывает CVD, delta, footprint-уровни, imbalance, absorption и источник данных: реальные тики или OHLCV proxy.</td></tr>
                    <tr><td>Derivatives</td><td>Учитывает funding, OI, ликвидационный контекст и crowding, если данные доступны для выбранного рынка.</td></tr>
                    <tr><td>RiskGate</td><td>Блокирует опасное исполнение по дневному убытку, числу сделок, серии убытков, дублирующим ордерам, отклонению entry и размеру позиции.</td></tr>
                    <tr><td>Исполнение</td><td>Поддерживает Paper, Testnet и Live-режимы биржевых аккаунтов. Live Binance защищен дополнительным подтверждением и настройками риска.</td></tr>
                    <tr><td>Лицензия</td><td>При запуске проверяет HWID, пароль и срок доступа на VPS. HWID копируется кнопкой, пароль можно сохранить через Windows DPAPI.</td></tr>
                </table>

                <h2>Логическая цепочка алгоритма</h2>
                <table>
                    <tr><td>1. Загрузка данных</td><td>Программа получает свечи Binance Spot/Futures, подгружает историю, live-цену, тики Data Lake и дополнительные контексты, если они доступны.</td></tr>
                    <tr><td>2. HTF bias</td><td>На старшем таймфрейме определяется направление: структура HH/HL или SMA-метод, свежие BOS/CHoCH, premium/discount и зона интереса.</td></tr>
                    <tr><td>3. Liquidity map</td><td>Строятся BSL/SSL, equal highs/lows, session liquidity, internal/external liquidity и уровни, где вероятны стопы участников.</td></tr>
                    <tr><td>4. Sweep validation</td><td>Пробой уровня не считается сигналом сам по себе. Важны фитиль, возврат, закрытие свечи и последующий impulse/displacement.</td></tr>
                    <tr><td>5. Displacement</td><td>Алгоритм ищет сильную свечу или серию свечей с ATR/momentum, которая подтверждает, что после снятия ликвидности появился агрессивный поток.</td></tr>
                    <tr><td>6. Structure shift</td><td>CHoCH/BOS проверяется по body close, protected high/low и силе движения. Это снижает ложные сигналы от тонких фитилей.</td></tr>
                    <tr><td>7. Зона входа</td><td>Выбирается OB, FVG или OTE. Зоны получают score по свежести, размеру, proximity, mitigation, совпадению с HTF и наличию sweep.</td></tr>
                    <tr><td>8. Confluence</td><td>Сигнал усиливается или режется по HTF, sweep, displacement, структуре, качеству зоны, event-risk, imbalance, deviation и статистике бэктеста.</td></tr>
                    <tr><td>9. Торговый план</td><td>Entry строится в зоне интереса, SL за invalidation-уровнем, TP по R:R и ближайшей ликвидности. Частичное закрытие по умолчанию 40/40/20.</td></tr>
                    <tr><td>10. Execution decision</td><td>Итоговый прогноз объединяет SMC score, статистику, свежесть сценария, расстояние до entry, риск возврата к среднему, DOM/OI и текущий импульс.</td></tr>
                </table>

                <h2>Механики из smart-money моделей</h2>
                <p>После базового поиска OB/FVG программа дополнительно проверяет цепочку из обучающего материала:
                ликвидность -> displacement -> структура -> возврат в зону -> цель по следующей ликвидности. Эти модели влияют на score, радар и выбор TradePlan.</p>
                <table>
                    <tr><td>A+</td><td>Полная модель: перед зоной есть sweep, после него появился displacement, затем CHoCH/BOS с body close и есть понятная цель ликвидности.</td></tr>
                    <tr><td>BRK</td><td>Breaker Block. После снятия ликвидности цена ломает структуру в обратную сторону; зона трактуется как область, где застряли участники неправильного направления.</td></tr>
                    <tr><td>MIT</td><td>Mitigation Block. Цена возвращается в активную зону после структурного сдвига. Такой сценарий лучше обычного касания, но слабее полного sweep-моделя.</td></tr>
                    <tr><td>PROP</td><td>Propulsion Block. Внутри более крупного OB появляется вложенная зона, которая может дать быстрый импульс при совпадении с HTF.</td></tr>
                    <tr><td>IFVG</td><td>Inverted FVG. Разрыв был полностью пересечен и перевернулся: бывшая поддержка становится сопротивлением или наоборот.</td></tr>
                    <tr><td>L2L</td><td>Liquidity-to-liquidity. У зоны есть ближайшая не снятая BSL/SSL цель; TP старается не быть случайной линией в пустоте.</td></tr>
                </table>

                <h2>Как читать график</h2>
                <table>
                    <tr><td>Бирюзовые свечи</td><td>Рост. Красные свечи - снижение. Текущая live-свеча и линия цены обновляются чаще, чем полный пересчет анализа.</td></tr>
                    <tr><td>BSL / SSL</td><td>Buy-side и sell-side liquidity. BSL обычно над максимумами, SSL под минимумами. Это места, где стоят стопы и где возможен sweep.</td></tr>
                    <tr><td>Снятие</td><td>Метка sweep/stop hunt. Важна не сама метка, а реакция после нее: возврат, displacement и структура.</td></tr>
                    <tr><td>IDM</td><td>Inducement. Ловушка или слабая структура, которая часто появляется перед настоящим движением.</td></tr>
                    <tr><td>OB</td><td>Order block. Зеленые зоны для long-контекста, красные для short-контекста. Чем свежее и чище зона, тем выше ее score.</td></tr>
                    <tr><td>FVG</td><td>Fair Value Gap. Разрыв эффективности цены. Синий/оранжевый фон показывает область, где цена может вернуться для mitigation.</td></tr>
                    <tr><td>OTE</td><td>Optimal Trade Entry. Зона отката по структуре, обычно используется только как часть общего confluence.</td></tr>
                    <tr><td>DEV</td><td>Deviation/smart-money trap. Показывает ложный выход за range/уровень и возврат внутрь.</td></tr>
                    <tr><td>CVD</td><td>Кривая cumulative volume delta. Помогает увидеть, покупают или продают агрессивнее относительно цены.</td></tr>
                    <tr><td>Footprint-полосы</td><td>Вертикальные микро-полосы у свечей показывают агрессивные покупки/продажи по уровням. Если тиков нет, включается приближенный OHLCV proxy.</td></tr>
                    <tr><td>TP/SL справа</td><td>Ценовые метки целей и стопа. Рядом может отображаться примерное время достижения, статус “взято” или предупреждение “лучше закрыть”.</td></tr>
                </table>

                <h2>Радар входов</h2>
                <p>Радар не просто ищет любую монету с зоной. Он пропускает сетап через фильтры качества и показывает только те пары,
                где совпали выбранные confluence-факторы.</p>
                <table>
                    <tr><td>SMC minimum</td><td>Минимальный score. Чем выше порог, тем меньше сигналов и тем строже отбор.</td></tr>
                    <tr><td>Факторы confluence</td><td>HTF по направлению, sweep, displacement, свежий CHoCH/BOS, качественная зона, отсутствие choppy-рынка, distance to entry, deviation, imbalance и event risk.</td></tr>
                    <tr><td>Статистика бэктеста</td><td>Если включена, радар учитывает похожие исторические сетапы: количество сделок, expectancy, winrate и PF.</td></tr>
                    <tr><td>Открытие пары</td><td>Клик по найденной строке открывает график на нужной монете и таймфреймах, переносит торговый план и включает прогноз.</td></tr>
                    <tr><td>Дубли монет</td><td>Одна монета может появляться на разных HTF/LTF. Это нормально: сетапы на 1m, 5m, 1h и 4h не равны друг другу.</td></tr>
                </table>

                <h2>Прогноз сделки</h2>
                <table>
                    <tr><td>Entry</td><td>Расчетная точка входа в зоне интереса. Вход не строится от одного касания, а требует связку sweep -> displacement -> structure -> return.</td></tr>
                    <tr><td>Stop Loss</td><td>Ставится за invalidation: структурный high/low, край зоны, sweep-extreme или расчетный ATR-buffer.</td></tr>
                    <tr><td>TP1/TP2/TP3</td><td>Цели строятся по R:R и ликвидности. Базовый профиль частичного закрытия: 40%, 40%, 20%.</td></tr>
                    <tr><td>Risk:Reward</td><td>Минимальный рабочий профиль около 1:1, средний 1:2, сильный 1:3. Слишком дальние цели ограничиваются, чтобы не превращать план в фантазию.</td></tr>
                    <tr><td>Время до TP/SL</td><td>Оценка строится по ATR, скорости последних свечей, направленному импульсу и истории похожих сетапов, если она есть.</td></tr>
                    <tr><td>Лучше закрыть</td><td>Появляется, когда расчетное время сценария выдыхается, цена уходит от entry, momentum ломает идею или цель уже статистически маловероятна.</td></tr>
                    <tr><td>Plan state</td><td>VALID - идея еще логична. CHECK ENTRY - надо дождаться подтверждения. LATE - вход запоздал. BROKEN/STOP HIT - план потерял смысл.</td></tr>
                </table>

                <h2>Бэктест и обучение статистикой</h2>
                <table>
                    <tr><td>Быстрый бэктест</td><td>Идет по свечам и conservative OHLC path. Быстро фильтрует плохие идеи, но не всегда точно моделирует внутрисвечное исполнение.</td></tr>
                    <tr><td>Tick/DataLake</td><td>Использует реальные aggTrades, если они накоплены. Лучше для проверки касаний TP/SL, slippage и последовательности событий внутри свечи.</td></tr>
                    <tr><td>Backtest Intelligence</td><td>Собирает результаты CSV из папки backtests и показывает статистику по монетам, таймфреймам, направлениям, зонам и режимам.</td></tr>
                    <tr><td>Expectancy</td><td>Математическое ожидание в R. Положительная expectancy важнее красивого единичного сетапа.</td></tr>
                    <tr><td>Winrate + R:R</td><td>Средний winrate может быть нормальным, если R:R покрывает стопы. Одна сделка 1:2 или 1:3 должна перекрывать несколько малых ошибок.</td></tr>
                    <tr><td>Сохраненные результаты</td><td>Результаты бэктеста используются как evidence: фильтры радара и прогноз могут учитывать похожие исторические сетапы.</td></tr>
                </table>

                <h2>Исполнение и риск</h2>
                <p>Кнопки открытия сделок не заменяют риск-менеджмент. Перед отправкой ордера программа нормализует количество,
                проверяет аккаунт, режим, дневные лимиты и геометрию сделки.</p>
                <table>
                    <tr><td>Paper</td><td>Локальная симуляция без биржи. Подходит для проверки логики и журналов.</td></tr>
                    <tr><td>Testnet</td><td>Биржевой тестовый режим. Нужен перед любым Live-исполнением.</td></tr>
                    <tr><td>Live</td><td>Реальные ордера. Требует отдельного включения, корректных API-ключей и прохождения RiskGate.</td></tr>
                    <tr><td>Order state</td><td>Локальный ledger и reconcile сверяют открытые ордера/позиции, чтобы не отправить второй market-order при задержке ответа биржи.</td></tr>
                    <tr><td>Kill switch</td><td>Блокирует новые сделки при дневной просадке, лимите сделок, серии убытков, превышении размера или активных позициях.</td></tr>
                    <tr><td>Журналы</td><td>Ошибки, успехи, блокировки и биржевые ответы пишутся подробнее, чтобы было понятно, почему сделка не открылась.</td></tr>
                </table>

                <h2>Инфраструктура</h2>
                <table>
                    <tr><td>ML service</td><td>Python-мост для адаптивных весов и статистики признаков. Новая модель сначала становится candidate и только потом может быть принята.</td></tr>
                    <tr><td>Ticker plant</td><td>Фоновый сборщик тиков, стакана и derivatives-контекста. Без него Data Lake не пополняется.</td></tr>
                    <tr><td>WebSocket</td><td>Live-данные принимаются в фоне, UI обновляется через throttle, чтобы не забивать EDT и не фризить интерфейс.</td></tr>
                    <tr><td>Ring buffers</td><td>Потоковые данные ограничиваются буферами, чтобы память не росла бесконечно при высокой волатильности.</td></tr>
                    <tr><td>Лицензия</td><td>VPS хранит HWID, хэш пароля и expires_at. Клиент отправляет пароль и HWID, сервер возвращает разрешение или отказ.</td></tr>
                </table>

                <h2>Настройки интерфейса</h2>
                <table>
                    <tr><td>Темы</td><td>Цветовая схема применяется к основному окну, диалогам, скроллбарам и title bar Windows.</td></tr>
                    <tr><td>Прозрачность зон</td><td>OB/FVG/OTE, подписи, ликвидность и события регулируются отдельно, чтобы зоны не исчезали вместе с текстом.</td></tr>
                    <tr><td>Левая панель</td><td>Содержит детали монеты, план, анализ графика, позиции, ликвидность, поток, справку и настройки. Есть компактный режим.</td></tr>
                    <tr><td>Цена/бар lock</td><td>Фиксирует соотношение цена/бар по аналогии с TradingView, чтобы свободно двигать график без автоцентрирования.</td></tr>
                </table>

                <h2>Рекомендуемый порядок работы</h2>
                <ul>
                    <li>Выбери рынок, монету, HTF и график.</li>
                    <li>Нажми <b>Обновить</b> или запусти <b>Радар</b>.</li>
                    <li>Проверь HTF bias, зоны, sweep, displacement и состояние плана.</li>
                    <li>Открой <b>План торговли</b>, запусти проверку и бэктест.</li>
                    <li>Смотри не только SMC score, но и expectancy, R:R, свежесть entry и предупреждения прогноза.</li>
                    <li>Для Live сначала используй Paper/Testnet и только потом маленький реальный объем.</li>
                </ul>

                <h2>Ограничения</h2>
                <p><span class='bad'>Программа не гарантирует прибыль.</span> Она помогает структурировать анализ,
                отсеивать слабые сетапы и контролировать риск, но не знает будущих новостей, каскадов ликвидаций,
                задержек биржи и внезапных гэпов.</p>
                <p>Если данные неполные, часть расчетов честно переходит в fallback: OHLCV proxy вместо тиков,
                свечной бэктест вместо tick replay, приближенное время до TP/SL вместо статистически доказанного.</p>
                <p>Лучшее применение SMC Chart - дисциплинированный top-down фильтр, а не кнопка “угадать рынок”.</p>

                </body></html>
                """
                .replace("__FONT__", UIUtils.HTML_FONT_STACK)
                .replace("__FG__", hex(FG))
                .replace("__PANEL__", hex(PANEL_BG))
                .replace("__CARD__", hex(CARD_BG))
                .replace("__GRID__", hex(GRID))
                .replace("__MUTED__", hex(MUTED))
                .replace("__ACCENT__", hex(ACCENT))
                .replace("__YELLOW__", hex(YELLOW))
                .replace("__BULL__", hex(BULL))
                .replace("__BEAR__", hex(BEAR));
    }

    private String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static class WrapEditorPane extends JEditorPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    }
}
