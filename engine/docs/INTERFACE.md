# Operator Interface v5

## Принцип

Интерфейс — отдельный operator layer. Он не обращается к Binance, Redis или SQLite напрямую и не повторяет торговые расчёты. Источник истины — backend.

## Авторизация

- Bearer token;
- actor передаётся в `X-Operator-Actor`;
- token хранится только в `sessionStorage` вкладки;
- OpenAPI/Swagger отключены;
- CSP, frame denial, no-store и nosniff включены.

Для удалённого доступа используйте VPN или HTTPS reverse proxy. Прямой remote bind без TLS по умолчанию отклоняется.

## Обзор

Показывает:

- equity и available balance;
- unrealized P&L;
- фактический и зарезервированный риск;
- admission state;
- private stream health;
- позиции, regular/algo orders;
- non-terminal saga;
- daily loss и peak drawdown;
- signal automation и approved runs;
- telemetry equity curve.

## Режимы

Доступны `CAPITAL_PROTECTION`, `BALANCED`, `PROFITABILITY`, `PAUSED`. Backend повторно проверяет flat state перед сменой; UI не может обойти проверку.

## Research

Вкладка позволяет:

- создать background data-download job;
- видеть immutable datasets и fingerprints;
- запускать walk-forward в отдельном процессе;
- просматривать OOS metrics;
- видеть fold-by-fold результаты;
- сравнивать baseline;
- просматривать stress suite;
- читать причины PASS/FAIL;
- создавать governance approvals;
- контролировать shadow signals/trades.

## Команды

### Halt

Закрывает admission. Supervisor и recovery продолжают работать.

### Reconcile

Сверяет позиции, regular/algo orders и saga. Вариант Resume открывает admission только при доказанном безопасном состоянии.

### Flatten

Двухфазная команда:

```text
создать план
→ проверить symbol/quantity
→ ввести одноразовую confirmation phrase
→ отменить entry orders
→ закрыть exposure
→ доказать flat
→ отменить остаточные protection orders
```

### Research promotion

Требует typed confirmation и `Idempotency-Key`. Backend проверяет предыдущую ступень и evidence.

## API

Основные read-only endpoints:

```text
GET /api/v1/status
GET /api/v1/positions
GET /api/v1/orders
GET /api/v1/executions
GET /api/v1/modes
GET /api/v1/telemetry
GET /api/v1/audit
GET /api/v1/research/datasets
GET /api/v1/research/data-jobs
GET /api/v1/research/runs
GET /api/v1/research/approvals
GET /api/v1/research/shadow
```

Status snapshot содержит `live_capital_status`. Для текущей сборки UI постоянно
показывает `BINANCE TESTNET — LIVE ЗАПРЕЩЁН`; это информационное отражение backend
policy, а не переключатель режима.

Команды:

```text
POST /api/v1/commands/halt
POST /api/v1/commands/reconcile
POST /api/v1/commands/mode
POST /api/v1/commands/flatten/plan
POST /api/v1/commands/flatten/confirm
POST /api/v1/research/datasets/download
POST /api/v1/research/runs
POST /api/v1/research/runs/{run_id}/promote
```

Каждая mutating-команда, кроме создания flatten plan, требует уникальный `Idempotency-Key`.

## WebSocket

`/api/v1/ws` принимает token первым сообщением и отправляет status snapshots. При разрыве UI помечает соединение как недоступное; последнее состояние не следует считать актуальным.
