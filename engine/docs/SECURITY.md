# Security Model v5

## Защищаемые активы

- Binance API key/secret;
- право отправки ордеров;
- execution state и risk ledger;
- strategy approvals;
- research datasets/reports;
- operator audit trail;
- команды halt/reconcile/flatten/mode/promotion.

## Границы доверия

```text
Browser (untrusted display/input)
    ↓ authenticated HTTPS/WebSocket
Operator API
    ↓ typed internal interfaces
Runtime / Research Service
    ↓ signed exchange requests
Binance
```

Браузер не получает Binance secret и не пишет в базы напрямую.

## API keys

- используйте отдельные ключи для Testnet и live, а в live — разные ключи для
  `TRADE`, `USER_DATA` и read-only monitoring;
- не включайте withdrawal permission;
- включите IP allowlist на каждом live-ключе;
- храните secrets вне Git и обычных environment files: systemd credentials,
  Docker secrets, Vault agent или эквивалент;
- `.env` содержит только несекретную конфигурацию и пути `*_FILE`;
- регулярно ротируйте ключи;
- при подозрении на компрометацию сначала halt/flatten по ситуации, затем отзывайте ключ.

Если ключ когда-либо находился в Git, архиве, логе или передавался через
небезопасный канал, простого удаления строки недостаточно: ключ необходимо отозвать
в Binance и выпустить заново.

## Live authorization

Текущая сборка имеет compile-time policy
`LIVE_CAPITAL_STATUS=PROHIBITED_PENDING_ACCEPTANCE`: любой startup с
`BINANCE_TESTNET=false` прекращается до создания exchange runtime. У этого запрета
нет environment override.

- статическая acknowledge-фраза не используется;
- authorization действует не более 24 часов;
- требуются подписи двух разных операторов разными Ed25519-ключами;
- private signing keys хранятся вне execution host;
- authorization JSON и approver public-key directory принадлежат root/отдельному
  deploy principal и не доступны execution process для записи;
- подписи связывают deployment, выделенный sub-account, symbols, approved runs,
  code/data/strategy/risk fingerprints и recovery evidence;
- любое несовпадение или истечение срока останавливает startup до подключения к
  торговому runtime;
- UI не может снять этот gate или включить live одной командой.
- signed authorization является необходимой проверкой будущего `CANARY_ONLY`, но
  сам по себе не разрешает капитал.

## Operator token

- минимум 32 случайных байта;
- передавать только через localhost/VPN/HTTPS;
- не публиковать порт 8080 напрямую в интернет;
- production proxy mode отклоняет HTTP/API/WebSocket без признака HTTPS от
  доверенного reverse proxy;
- actor не является отдельной аутентификацией, это audit label;
- для нескольких операторов нужен внешний identity-aware proxy/RBAC.

## Redis и сеть

- supplied `docker-compose.redis.yml` не публикует `6379` на host и подключает
  Redis только к internal Docker network `smc-execution-backend`;
- не добавляйте `ports: 6379:6379` в production;
- при выходе Redis за пределы одного закрытого Docker network обязательны TLS,
  аутентификация и отдельные сетевые ACL;
- порт Operator API маппится только на `127.0.0.1` и предназначен для HTTPS
  reverse proxy.

## Research integrity

- datasets immutable;
- reports immutable;
- code и data fingerprints входят в approval;
- governed signal обязан содержать provenance;
- profile, symbol и interval повторно проверяются перед execution;
- live promotion default deny.

## Process isolation

Walk-forward запускается отдельным subprocess, чтобы CPU/GIL/память исследования не блокировали supervisor. Shutdown завершает worker. Для максимальной изоляции production-инсталляция должна запускать research worker в отдельном cgroup/container с CPU/memory limits.

## Командная безопасность

- typed confirmation;
- idempotency;
- durable audit;
- backend state revalidation;
- двухфазный flatten;
- fail-closed admission;
- опасная отмена стопа выполняется только после доказательства новой защиты/flat state.

## Известные ограничения

- один shared bearer token не заменяет полноценный RBAC;
- текущий SQLite backend допустим для live только при подписанном свежем отчёте
  single-node recovery со всеми обязательными сценариями; переход на PostgreSQL и
  автоматические off-host backups остаются предпочтительным production-вариантом;
- браузерный UI не защищает от компрометации самого хоста;
- research code исполняется как доверенный код пакета;
- платформа не защищает от insolvency/outage биржи;
- отсутствие withdrawal permission не устраняет торговый ущерб от украденного ключа.
