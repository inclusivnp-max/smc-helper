# SMC Execution Platform v5.0.1

SMC Execution Platform — fail-closed платформа для исполнения, защиты портфеля, исследования торговых сигналов и управляемого допуска стратегии от backtest до live.

> Платформа не гарантирует прибыль. Execution Engine уменьшает технические и операционные потери, а Research Layer пытается отклонить стратегии без доказанного out-of-sample преимущества. Рыночный, модельный, инфраструктурный и контрагентский риски остаются.

## Что входит в v5

- production-oriented Execution Engine с durable saga;
- атомарный портфельный risk ledger;
- Binance USD-M regular/algo order routing;
- startup recovery и reconciliation;
- protection supervisor и portfolio governor;
- режимы `CAPITAL_PROTECTION`, `BALANCED`, `PROFITABILITY`, `PAUSED`;
- отдельный FastAPI Operator Layer и адаптивный браузерный интерфейс;
- immutable datasets и SHA-256 fingerprints;
- SMC Composite Strategy без look-ahead;
- event-driven симулятор исполнения;
- purged rolling walk-forward;
- одинаковые OOS-окна для стратегии, baseline и stress tests;
- block bootstrap и fail-closed Alpha Gate;
- durable shadow-forward tracker;
- durable статусы dataset-download и research jobs с явным `INTERRUPTED` после restart;
- governed signal router;
- поэтапный допуск `RESEARCH_PASSED → SHADOW → TESTNET → LIVE`;
- один независимый approval на каждый `symbol + interval + strategy + code + dataset + risk profile`;
- изолированный дочерний процесс для тяжёлого research.

## Архитектура

```text
Historical/Public Data
        ↓
Immutable ResearchDataset + SHA-256
        ↓
SMC Strategy → Purged Walk-Forward → OOS Metrics
        ↓               ↓              ↓
   Baselines        Stress Suite    Block Bootstrap
        └───────────────┬──────────────┘
                        ↓
                  Fail-Closed Alpha Gate
                        ↓
 RESEARCH_PASSED → SHADOW → TESTNET → LIVE
                        ↓
              GovernedSignalRouter
                        ↓
                 TradingRuntime
                        ↓
 ExecutionEngine + Risk Ledger + Recovery + Supervisor
                        ↓
                 Binance USD-M

Browser → Operator API → Runtime / Research Service
Browser никогда не обращается к Binance или SQLite напрямую.
```

## Что означает «прибыльность» и «безубыток»

### Защита капитала / безубыток

- риск на сделку: 0,15% equity;
- максимальный открытый портфельный риск: 0,75%;
- ранний breakeven после 0,8R;
- более строгий confidence и spread filter;
- max notional: 500 USDT.

«Безубыток» означает алгоритмическое уменьшение риска после благоприятного движения. Гэп, комиссия, funding и slippage способны дать отрицательный результат даже после переноса стопа.

### Сбалансированный

- риск на сделку: 0,30%;
- портфельный риск: 1,50%;
- breakeven после 1R;
- умеренный trailing;
- max notional: 2 000 USDT.

### Прибыльность

- риск на сделку: 0,50%;
- портфельный риск: 2,50%;
- более поздний breakeven и широкий trailing;
- повышенная целевая доходность сделки;
- max notional: 5 000 USDT.

Это профиль повышенного риска, а не режим гарантированной прибыли.

### Риск на сделку — верхняя граница, а не факт

Указанные проценты риска действуют только пока раньше не срабатывает
`max notional`. Если notional cap ограничивает размер первым, фактический риск на
сделку оказывается меньше заявленного профилем, и профиль исследуется не при том
риске, который в нём написан. На дефолтной конфигурации это происходит регулярно,
поэтому research report содержит warning с фактической долей риска, а bootstrap
компаундит именно её. Проверяйте это предупреждение перед любым promotion.

### Пауза

Запрещает новые входы, но не останавливает supervisor, recovery и защиту существующих позиций. Активная политика уже открытой сделки не меняется.

## Быстрый запуск

```bash
unzip smc_execution_platform_v5.zip
cd smc_execution_engine_v5

python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e '.[operator,dev]'

cp .env.example .env
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Заполните `.env` Demo/Testnet ключами и сгенерированным `OPERATOR_TOKEN`.

```bash
pytest -q
python examples/preflight_binance.py
python examples/run_operator_dashboard.py
```

Открыть:

```text
http://127.0.0.1:8080
```

Подробности: `docs/QUICKSTART.md` и `docs/OPERATIONS.md`.

## Research workflow

1. Загрузить закрытые свечи Binance через вкладку **Research** или CLI.
2. Проверить data-quality и immutable fingerprint.
3. Выбрать тот же risk-profile, который будет использоваться в runtime.
4. Запустить purged walk-forward.
5. Изучить OOS folds, baseline, stress, bootstrap и причины gate.
6. При `PASS` вручную создать `RESEARCH_PASSED`.
7. Продвинуть тот же run в `SHADOW`.
8. Накопить минимум закрытых shadow-сделок и положительное ожидание.
9. Продвинуть в `TESTNET` и провести contract/failure tests.
10. Live promotion по умолчанию заблокирован.

Research run нельзя использовать:

- для другого символа;
- для другого интервала;
- после изменения кода стратегии;
- после изменения dataset;
- с другим активным risk-profile;
- с execution ATR timeframe, не совпадающим с исследованным interval.

Полная методология: `docs/ALPHA_RESEARCH.md` и `docs/GOVERNANCE.md`.

## Автоматический генератор сигналов

После допуска в SHADOW или TESTNET:

```dotenv
AUTO_SIGNAL_GENERATOR=true
APPROVED_RUN_IDS=<run-id-BTC>,<run-id-ETH>
SIGNAL_ENVIRONMENT=shadow
SIGNAL_POLL_SECONDS=5
SIGNAL_HISTORY_BARS=500
```

Каждый run привязан ровно к одному рынку. Два активных run для одной пары `symbol + interval` одновременно запрещены.

Сигнал содержит provenance:

```json
{
  "strategy_id": "smc_composite",
  "strategy_version": "...",
  "research_run_id": "...",
  "code_fingerprint": "...",
  "metadata": {"interval": "5m"}
}
```

`GovernedSignalRouter` сверяет эти поля с immutable approval до передачи сигнала в Execution Engine.

## Alpha Gate по умолчанию

Стратегия отклоняется, если не выполнено хотя бы одно требование:

- минимум 2 независимых OOS fold;
- не менее 60% fold с положительным expectancy;
- минимум 80 OOS-сделок;
- expectancy не ниже 0,05R;
- profit factor не ниже 1,15;
- max drawdown не выше 15%;
- bootstrap probability `P(E > 0)` не ниже 90%;
- минимум 60% adverse stress scenarios проходят;
- стратегия превосходит лучший baseline минимум на 0,02R;
- OOS net return положителен.

Gate — инженерный фильтр, а не доказательство будущей прибыли.

## Консервативная модель исполнения

Симулятор:

- принимает решение только после закрытия свечи;
- входит не раньше следующей свечи;
- моделирует maker wait и taker fallback;
- отклоняет только направленное adverse slippage;
- учитывает maker/taker fees и funding;
- резервирует комиссии и stop slippage в размере позиции;
- при гэпе через стоп исполняет по open-through цене с дополнительным slippage;
- при одновременном касании SL и TP по умолчанию выбирает стоп;
- воспроизводит breakeven, trailing и time exit выбранного режима;
- считает baseline и stress на тех же OOS-окнах.

Это всё равно приближение: OHLC не восстанавливает очередь заявок и истинный intrabar path.

## Хранилища

- `STATE_DB` — execution saga и risk ledger;
- `OPERATOR_DB` — telemetry, audit и idempotency operator commands;
- `RESEARCH_DB` — immutable datasets, reports, approvals, shadow trades и durable jobs.

Не удаляйте базы, пока существуют позиции, активные approvals или незавершённые saga. Делайте согласованные резервные копии при остановленном процессе либо через SQLite backup API.

## Безопасный переход к live

> **Статус текущей сборки: `LIVE: PROHIBITED_PENDING_ACCEPTANCE`.**
> `BINANCE_TESTNET=false` безусловно отклоняется кодом. Ни environment variable,
> ни API-ключи, ни signed authorization не могут включить реальный капитал.

Live нельзя включать только потому, что backtest показал PASS. Минимальный порядок:

```text
OOS PASS
→ frozen SHADOW
→ TESTNET contract tests
→ live shadow на реальных данных без ордеров
→ минимальный live notional
→ наблюдение и независимый review
→ постепенное масштабирование
```

Live нельзя открыть одной переменной или статической фразой. Одновременно требуются:

- `BINANCE_TESTNET=false`, `SIGNAL_ENVIRONMENT=live` и автоматизация только по
  явно перечисленным `APPROVED_RUN_IDS`;
- три отдельных credential-file пары: `TRADE`, `USER_DATA` и read-only monitoring;
- отдельный live authorization JSON со сроком не более 24 часов;
- две Ed25519-подписи разных операторов и разные public key IDs;
- совпадение deployment/sub-account/symbol/run IDs, engine code, strategy code,
  dataset и risk fingerprints;
- активный durable `LIVE` approval каждого run;
- подписанный свежий recovery report с обязательными kill/restart и backup-restore
  сценариями для фактического state backend;
- отдельный live sub-account, IP allowlist и отключённый withdrawal permission.

Перечисленные механизмы подготавливают доказательства, но не снимают текущий hard
gate. После реальных Demo/Testnet contracts, chaos/kill/restart, restore drill,
frozen shadow, Testnet execution report и независимого review требуется отдельное
проверяемое изменение кода в режим `CANARY_ONLY` с жёстким минимальным notional.
Полный live может рассматриваться только после завершённого canary и следующего
независимого review.

В live execution service `ALLOW_LIVE_RESEARCH_PROMOTION` обязан оставаться `false`.
Fingerprint текущей сборки и risk profile выводит команда:

```bash
python examples/live_authorization.py fingerprint \
  --risk-profile CAPITAL_PROTECTION \
  --execution-timeframe 5m \
  --recovery-report /var/lib/smc-execution/recovery-report.json
```

Шаблоны находятся в `deploy/live-authorization.example.json` и
`deploy/recovery-report.example.json`. Подписи создаются независимо каждым
оператором командой `examples/live_authorization.py sign`; private signing keys не
должны находиться на execution host. Перед deployment итоговый JSON и каталог
public keys должны стать read-only для пользователя execution service.

Operator API публикуется только на loopback и доступен через HTTPS reverse proxy.
В proxy-режиме backend отклоняет запросы без `X-Forwarded-Proto: https`.

## Проверка

```bash
PYTHONWARNINGS='error::ResourceWarning' pytest -q
python -m compileall -q smc_execution examples tests
node --check smc_execution/operator_ui/app.js
```

Фактические результаты поставки описаны в `docs/VALIDATION_REPORT.md`.
