# Validation Report — SMC Execution Platform v5.0.1

## Повторная проверка 5.0.1 (5 августа 2026 года)

Ниже перечислено только то, что фактически запускалось для 5.0.1. Остальные
пункты этого отчёта относятся к 5.0.0 и **не** перепроверялись.

Выполнено:

- `pytest` с `PYTHONWARNINGS='error::ResourceWarning'`: **96 passed, 2 skipped**,
  без предупреждений (было 81 passed, 2 skipped). Добавлено 15 тестов:
  согласованность агрегата, эквивалентность `_atr_tail`, notional-cap
  диагностика, отказ промоушена и router по версии метрик, и четыре теста,
  впервые получающие вердикт от самого `evaluate_alpha_gate`, а не от
  захардкоженного `GateDecision(True)`.
- `python -m compileall -q smc_execution examples tests` — без ошибок.
- Эквивалентность всех четырёх оптимизаций доказана сравнением SHA-256 по всем
  полям всех 404 сделок на 20 000 барах: digest прежнего и нового кода совпал
  (`1ef4a7d30064290514b95e79`). Отдельно проверена эквивалентность
  `shadow._atr(history[:-1], 14)` и `shadow._atr(history[-16:-1], 14)`.
- Производительность симулятора, чередующийся A/B по минимуму из трёх прогонов
  на 20 000 барах: **9,15 s → 3,00 s (×3,05)**. Абсолютные значения зависят от
  загрузки машины, поэтому сравнивать корректно только отношение при
  чередовании; ms/бар перестал расти с размером датасета.
- Полный `ResearchRunner` на синтетическом датасете (2 000 баров, 10 fold):
  `expectancy_r` не изменился, квантили просадки bootstrap пришли в соответствие
  с фактическим риском (p95 0,0484 → 0,0268), решение gate не изменилось.
- `SHA256SUMS.txt` перегенерирован; wheel пересобран как 5.0.1.

Не перепроверялось для 5.0.1: `ruff`, `node --check`, Chromium visual smoke,
установка wheel в чистое окружение, subprocess smoke research worker, ASGI-тесты
вне общего прогона `pytest`, а также всё из раздела «Не проверено в этой среде».

**Внимание:** `runtime_code_fingerprint()` изменился, поэтому любой подписанный
live authorization JSON нужно выпустить заново. Отчёты, сохранённые версией
5.0.0, посчитаны по прежней математике агрегата; промоушен и governed signal
router их теперь отклоняют, research нужно перезапустить. Изменения в
`strategy.py` меняют `StrategyManifest.code_fingerprint`, поэтому существующие
approvals недействительны и по этой причине тоже.

**Устранённая дыра в покрытии:** до 5.0.1 единственный сквозной тест
`ResearchRunner` (`test_research_runner_produces_oos_report`) работал на
синтетическом ряде, на котором стратегия не выдаёт ни одного сигнала. Проверено
измерением: 0 сделок на обоих фолдах, bootstrap с 0 сэмплов, все пять стрессов
`passed=False` — то есть весь агрегатный конвейер проверялся на пустых объектах.
Добавлен `test_research_runner_aggregates_non_empty_trades_end_to_end` на
детерминированном случайном блуждании: он требует непустых OOS-сделок, ненулевой
просадки, инварианта `gate.passed == (reasons == ())` и срабатывания
notional-cap предупреждения.

## Проверка 5.0.0

Дата проверки: **11 июля 2026 года**.

## Подтверждено в поставляемой среде

- **81 passed, 2 skipped** в `pytest`; skipped-тесты — opt-in Binance
  Demo/Testnet network/order contract без предоставленных credentials.
- Полный набор проходит с `ResourceWarning`, превращённым в ошибку.
- `python -m compileall` проходит для `smc_execution`, `examples` и `tests`.
- `node --check` проходит для встроенного dashboard JavaScript.
- `ruff check smc_execution examples tests` проходит без замечаний.
- Общий test coverage: **71%** по 6 354 исполняемым строкам.
- Ключевое покрытие: backtest 90%; research models 87%; research store 87%; strategy 81%; alpha gate 79%; validation 75%; engine 75%; Binance adapter 54%; live guard 66%; SQLite control plane 93%; runtime 90%; modes 90%; operator store 84%; shadow 84%.
- Wheel `smc_execution_engine-5.0.0-py3-none-any.whl` собирается стандартным PEP 517 pipeline.
- Wheel устанавливается с optional extra `operator` в чистое виртуальное окружение и импортируется из каталога вне исходного дерева.
- В wheel подтверждены SPA (`index.html`, `styles.css`, `app.js`) и четыре Redis Lua-скрипта (`claim_execution`, `transition_execution`, `reserve_risk`, `commit_risk`).
- CLI-парсеры research download/import/run загружаются корректно после установки пакета.
- Выполнен subprocess smoke Research Worker на синтетическом dataset: процесс завершился, сформировал report и корректно вернул fail-closed результат при недостаточном числе сделок.
- В Chromium выполнен visual smoke фактических поставляемых HTML/CSS/JS с тестовыми API/WebSocket-ответами: авторизация, dashboard, режимы, Research run, OOS folds, baseline, stress и shadow-представления отрисованы.
- ASGI-тесты проверяют загрузку встроенного интерфейса, Bearer auth, typed confirmations, command idempotency и audit trail.
- Проверены immutable dataset/report fingerprints и запрет подмены содержимого под существующим ID.
- Проверены purged rolling walk-forward, одинаковые OOS-окна для strategy/baseline/stress, fold gate и block bootstrap.
- Проверены directional adverse-slippage, gap-through-stop, cost-aware sizing, conservative same-bar SL/TP и baseline confidence decoupling.
- Проверены durable approvals `RESEARCH_PASSED → SHADOW → TESTNET`, exact binding к symbol/interval/profile/code/data и запрет live promotion по умолчанию.
- Проверены durable shadow state, restart continuation, funding accumulation, breakeven/trailing/time-exit и stop-gap handling.
- Сохраняются проверки execution v4: lost POST response, query-before-retry, partial fills, stop rejection, restart recovery, orphan state, private-stream loss, stale/out-of-order book ticker, compact `ALGO_UPDATE`, atomic portfolio risk и safe stop replacement.
- После неоднозначного POST автоматическая повторная отправка запрещена даже после
  нескольких `order not found`; HTTP `-1007`/5xx переводится в uncertain path.
- Проверены role-separated Binance credentials, pre-submit symbol/mode/tick/step/
  min-notional/leverage-bracket validation, REST-подтверждение protective stop и
  обязательный halt после отказа защиты.
- `.env.example` не содержит значений ключей; live принимает secrets только через
  защищённые credential files.
- Проверен короткоживущий live authorization: две независимые Ed25519-подписи,
  разные API-key roles, deployment/sub-account/code/data/risk binding и recovery
  evidence fingerprint.
- Текущая сборка имеет hard policy
  `LIVE_CAPITAL_STATUS=PROHIBITED_PENDING_ACCEPTANCE`: тест подтверждает, что
  `BINANCE_TESTNET=false` нельзя включить environment-переменной или signed
  authorization. Переход к canary требует отдельной reviewed сборки.
- Redis больше не публикует `6379`; Operator proxy mode отклоняет запросы без HTTPS
  forwarding marker.
- Dataset/research jobs сохраняются в `RESEARCH_DB`, восстанавливаются после restart
  и незавершённые состояния становятся `INTERRUPTED`.
- SQLite stores записывают schema version и отказываются открывать неизвестную более
  новую схему.

## Coverage caveat

Общий показатель намеренно снижен сетевыми и интеграционными ветвями, которые невозможно честно доказать unit-тестами без внешней инфраструктуры:

- Binance REST/WebSocket adapter: 54%;
- Binance public research downloader: 15%;
- Redis control plane: 25%;
- background automation: 29%;
- research subprocess entrypoint: 0% строкового coverage, при этом отдельно выполнен process-level smoke.

Это не следует маскировать искусственными mock-тестами: данные ветви требуют contract/integration/chaos gate на реальных сервисах.

## Не проверено в этой среде

- Реальные Binance API-ключи не использовались.
- Demo/Testnet ордера и настоящие private/public WebSocket-сессии не запускались.
- Opt-in Testnet contract suite добавлен, но оба теста пропущены без credentials и
  явных `RUN_BINANCE_TESTNET_*` флагов.
- Публичный downloader не проходил длительный сетевой contract test против всей истории символов.
- Redis server, persistence, Sentinel/Cluster и failover не поднимались.
- Docker CLI/daemon отсутствует; `docker compose config` и image build не выполнялись.
- `systemd-analyze verify` разобрал unit без синтаксических ошибок; ожидаемо сообщил,
  что `/opt/smc-execution/.venv/bin/python` отсутствует на этой рабочей машине.
- Browser-to-localhost сетевой E2E ограничен политикой среды; visual smoke использовал реальный frontend и контролируемые тестовые ответы API.
- Реальная latency, queue position, fill probability, комиссии, funding, liquidation tiers, ADL, market impact и implementation shortfall не измерялись.
- Адаптер предназначен для стандартного Binance USD-M Futures API (`/fapi`), а не Portfolio Margin (`/papi`).

## Обязательные gates до реального капитала

1. Contract tests публичного downloader для klines, mark-price klines, funding и OI payloads.
2. Contract tests Binance Demo/Testnet для regular/algo endpoints и всех user-stream events.
3. Полные lifecycle LONG/SHORT: maker, partial fill, IOC, stop, TP, breakeven replacement и flatten.
4. Fault injection и kill/restart в `ENTRY_SUBMITTING`, `ENTRY_OPEN`, `PROTECTION_PENDING` и research subprocess.
5. Private-stream disconnect/reconnect и startup recovery с orphan state.
6. Redis persistence/failover tests, если используется `RedisControlPlane`.
7. Frozen SHADOW run без изменения стратегии и профиля риска.
8. TESTNET execution report с p50/p95/p99 shortfall, fill ratio, missed fills и reject rate.
9. Независимый review данных, кода стратегии, model assumptions и operator runbook.
10. Минимальный live notional на отдельном sub-account с IP allowlist и без withdrawal permission.
11. Реальный signed recovery report; пример с `passed=false` не является evidence.
12. PostgreSQL, автоматические off-host backups и restore drill либо независимо
    подтверждённый single-node SQLite recovery для каждого релиза.

## Заключение

Поставка является fail-closed исследовательской и исполнительной платформой: она формализует проверку альфы, блокирует незадокументированную смену стратегии и уменьшает известные технические риски исполнения. Она **не доказывает будущую прибыль**, не гарантирует безубыточность и не заменяет реальные Testnet/Shadow/Live gates.
