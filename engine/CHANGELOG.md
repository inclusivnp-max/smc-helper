# Changelog

## 5.0.1

### Согласованность агрегированных OOS-метрик

- `_rebase_trades` больше не суммирует абсолютный per-window PnL в одну кривую.
  Каждое walk-forward окно симулируется от `initial_equity`, поэтому сложение
  сырых сумм описывало путь, которым не мог пройти ни один счёт. Теперь размер
  каждой сделки пересчитывается воспроизведением правила самого симулятора на
  текущем equity — сначала риск-бюджет, затем `max_position_notional`, — и все
  денежные поля масштабируются одним коэффициентом, так что `r_multiple`
  инвариантен. Важно именно пересчитывать размер, а не сохранять фактическую
  долю риска: доля cap-ограниченной сделки сама уже урезана, и её сохранение
  уменьшало бы позицию на каждой просадке, занижая ту самую просадку, которую
  читает gate.
- `max_drawdown_pct` и `return_pct`, которые проверяет Alpha Gate, стали
  просадкой и доходностью действительно компаундящегося счёта и теперь сравнимы
  с просадкой из `bootstrap_trade_distribution`.
- `bootstrap_trade_distribution` получает фактическую долю риска
  (`realized_risk_fraction`) вместо конфигурационной `risk_per_trade_pct`.
- В `SimulatedTrade` добавлено поле `initial_risk` — знаменатель `r_multiple`,
  без которого R-множитель нельзя было проверить.
- Новый `sizing_diagnostic` добавляет в report warning, когда размер позиции
  определил `max_position_notional`, а не risk-профиль, и показывает фактический
  риск на сделку. На дефолтном профиле это происходит регулярно.

### Провенанс отчётов

- В `ResearchReport` добавлено поле `metrics_algorithm_version`
  (константа `METRICS_ALGORITHM_VERSION`). Отчёт — это кэшированное решение:
  `evaluate_alpha_gate` вызывается один раз, per-trade строки не сохраняются, а
  строка отчёта неизменяема, поэтому отчёт старой версии нельзя ни пересчитать,
  ни корректно сравнить с новым.
- Промоушен (`_promote_sync`), `GovernedSignalRouter` и
  `LiveAuthorization.validate_research` отклоняют отчёт с версией ниже текущей.
  Это закрывает главный побочный эффект переработки агрегата: approvals durable,
  и без такой проверки апгрейд продолжал бы исполнять сигналы по доказательствам,
  посчитанным по прежним правилам.

### Производительность симулятора

Четыре побитово эквивалентные оптимизации. Суммарно на 20 000 барах **×3,05**
(чередующийся A/B, минимум из трёх прогонов: 9,15 s → 3,00 s) при совпадающем
SHA-256 по всем полям всех 404 сделок:

- `_atr_tail` берёт только нужный хвост баров вместо полного префикса
  `bars[:i+1]` — симулятор перестал быть квадратичным;
- `_ema` выносит инвариант `1 - alpha` из цикла накопления;
- `_zscore` считает среднее один раз вместо двух (`_std` принимает его
  параметром);
- `_PrefixView` использует нативный срез при `step == 1` вместо
  интерпретируемого цикла по `range`.

Отдельно: `shadow._evaluate_open` больше не копирует всю историю ради последних
15 баров — тот же дефект, что был в симуляторе.

### Fail-closed конфигурация

- `WalkForwardConfig` отклоняет `step_bars + embargo_bars < test_bars`: иначе
  OOS-окна перекрываются и одни и те же бары попадают в агрегированные метрики
  gate несколько раз.
- Уточнён docstring `walk_forward_windows`: не перекрываются только тестовые
  окна; обучающие окна намеренно включают OOS предыдущих fold.

### Исправлено по результатам аудита самой 5.0.1

- `_promote_sync` проверял версию метрик до разбора stage, поэтому одобрение,
  выданное до апгрейда, нельзя было и **отозвать**: `REVOKED` падал с той же
  ошибкой, что и продвижение вперёд. Проверки версии и gate теперь применяются
  только к авторизующим стадиям. Деавторизация не может зависеть от качества
  доказательств, которые она отзывает.
- `sizing_diagnostic` получал уже пересчитанные сделки, поэтому считал
  cap-ограниченными лишь часть из них (7 из 27 вместо 27 из 27), а на другом
  датасете предупреждение могло не появиться вовсе. `WalkForwardValidator.run`
  теперь возвращает и сырые per-window сделки, и диагностика читает их.

### Тесты

- 81 → 94 passed. Четыре теста впервые получают вердикт от самого
  `evaluate_alpha_gate`, а не от захардкоженного `GateDecision(True)`, включая
  ветвь max drawdown, которую как раз двигает переработка агрегата.
- Добавлен сквозной прогон `ResearchRunner` на ряде, который стратегия реально
  торгует. Прежний единственный сквозной тест шёл по ряду с нулём сигналов, то
  есть агрегатный конвейер проверялся на пустых объектах.

### Внимание при обновлении

- `runtime_code_fingerprint()` изменился, поэтому любой подписанный live
  authorization JSON нужно выпустить заново.
- Отчёты, сохранённые версией 5.0.0, посчитаны по прежней математике агрегата.
  Они не просто несравнимы с новыми — промоушен и router их теперь отклоняют,
  research нужно перезапустить.
- `_ema` и `_zscore` находятся в `strategy.py`, который целиком входит в
  `StrategyManifest.code_fingerprint`, поэтому существующие approvals в любом
  случае становятся недействительными. Перезапуск research обязателен по обеим
  причинам сразу.

## 5.0.0

### Alpha Research

- immutable research datasets с SHA-256;
- загрузчик Binance public kline/mark/funding/OI data;
- строгая data-quality проверка;
- SMC Composite Strategy без look-ahead;
- purged rolling walk-forward;
- ограниченная parameter grid;
- OOS fold metrics;
- одинаковые OOS-окна для strategy/baseline/stress;
- trend, mean-reversion и deterministic-random baselines;
- block bootstrap;
- fail-closed Alpha Gate;
- отдельный subprocess research worker.

### Execution simulation

- maker wait и taker fallback;
- directional adverse-slippage check;
- maker/taker fees и funding;
- missed fills и latency;
- cost-aware position sizing;
- stop execution slippage;
- gap-through-stop model;
- conservative same-bar ambiguity;
- breakeven, ATR trailing и time exit;
- стресс-сценарии с удвоенным stop slippage.

### Governance

- stages `RESEARCH_PASSED → SHADOW → TESTNET → LIVE`;
- live promotion default deny;
- exact binding к symbol/interval/profile/code/data;
- governed signal provenance;
- один approval на один рынок;
- multi-run approved portfolio automation;
- durable shadow state и evidence gate.

### Interface

- вкладка Research;
- dataset download jobs;
- run/fold/baseline/stress views;
- gate reasons;
- approvals;
- shadow metrics/trades;
- status approved automation.

### Safety

- immutable dataset/report IDs;
- subprocess cancellation on shutdown;
- profile/timeframe mismatch rejection;
- baseline confidence scale decoupled from SMC threshold;
- missing historical features не forward-filled из будущего.

### P0 hardening

- удалены значения ключей из environment templates; добавлены credential-file
  interfaces и раздельные Binance roles `TRADE`/`USER_DATA`/monitor;
- статическая live acknowledge-фраза заменена двухоператорским Ed25519 live
  authorization с полным fingerprint binding;
- Redis убран с host port, Operator API закреплён за loopback + HTTPS proxy gate;
- research/download jobs стали durable и получают `INTERRUPTED` после restart;
- uncertain submit больше никогда не повторяется автоматически;
- добавлены pre-submit exchange-filter/mode/leverage checks и REST-подтверждение
  protective orders;
- добавлены schema versions, opt-in Binance Testnet contract smoke и P0 tests.
- текущая сборка жёстко зафиксирована в
  `LIVE: PROHIBITED_PENDING_ACCEPTANCE`; environment и signed manifest не снимают
  запрет, а будущий canary требует отдельной reviewed build.

### Compatibility

Execution Engine v4 API сохранён. Добавлены отдельные ResearchStore, ResearchService и GovernedSignalRouter.
