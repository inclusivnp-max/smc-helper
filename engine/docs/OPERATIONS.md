# Operations Runbook v5

## Нормальный startup

```text
load non-secret environment + credential files
→ for LIVE verify two signatures, expiry and all fingerprints
→ initialize SQLite stores
→ mark abandoned PENDING/RUNNING research jobs as INTERRUPTED
→ initialize mode
→ connect exchange REST/public/private streams
→ close admission
→ startup reconciliation
→ protection verification
→ governor baseline
→ start operator telemetry
→ optional approved signal automation
→ open admission only if safe
```

## Ежедневные проверки

- private stream healthy;
- admission reason ожидаемый;
- unresolved saga = 0;
- нет orphan regular/algo orders;
- protection quantity соответствует position quantity;
- risk ledger соответствует фактическому exposure;
- дневной loss/drawdown ниже limits;
- approved run не истёк;
- strategy automation использует ожидаемый environment;
- свободное место на диске и резервные копии баз.

## Research operations

- research job должен выполняться в отдельном процессе;
- одновременно по умолчанию разрешён один run;
- dataset download — отдельная background task;
- все переходы job сохраняются в `RESEARCH_DB`; после restart незавершённый job
  остаётся видимым как `INTERRUPTED` и автоматически не повторяется;
- для тяжёлых исследований ограничьте CPU/RAM через cgroup/container;
- не запускайте массовый parameter search внутри торгового runtime;
- сохраняйте каждый FAIL, не удаляйте неудобные runs.

## Инцидент: private stream unhealthy

1. admission автоматически/вручную закрыть;
2. не отправлять новые входы;
3. проверить REST positions;
4. проверить regular orders;
5. проверить algo orders;
6. восстановить stream;
7. запустить reconciliation;
8. открывать admission только при `unresolved=0`.

## Инцидент: FAILED_UNCERTAIN

1. halt;
2. не повторять client order вручную;
3. query by client order ID;
4. сверить position и fills;
5. проверить protection;
6. перезапустить runtime для startup recovery;
7. сохранить incident report.

## Инцидент: research worker завис

Research task можно отменить остановкой operator service. Runtime protection должен
продолжать работать независимо. Статус job сохраняется; после аварийного restart
`PENDING/RUNNING` становится `INTERRUPTED`. Run без immutable report не может быть
promoted и запускается повторно только новой операторской командой.

## Инцидент: data job завершился FAILED

Проверьте:

- диапазон дат;
- поддержку interval;
- network/rate limit;
- доступность исторического OI;
- свободное место;
- ошибку в `/api/v1/research/data-jobs`.

Не подменяйте отсутствующие OI/funding будущими значениями.

## Backup

Минимально резервируйте:

```text
STATE_DB
OPERATOR_DB
RESEARCH_DB
несекретную environment-конфигурацию
зашифрованный/внешний backup credential store
конфигурацию reverse proxy/systemd
live authorization, approver public keys и recovery report
SHA256SUMS.txt текущей сборки
```

Для SQLite используйте backup API или остановите writers перед копированием. Простое копирование активного `.db` без WAL-файлов может дать несогласованную резервную копию.

## Schema migration и rollback

Каждая SQLite DB хранит `PRAGMA user_version`; процесс отказывает в запуске, если
видит схему новее поддерживаемой. Перед обновлением остановите writers, создайте и
проверьте согласованный snapshot всех трёх DB. Миграции выполняются только вперёд.
Rollback приложения означает остановку сервиса и восстановление полного набора DB
из предмиграционного snapshot; частичный downgrade одной базы запрещён.

## Обновление

1. halt admission;
2. дождаться terminal execution saga;
3. убедиться в server-side protection;
4. сделать backup;
5. развернуть новую версию рядом;
6. прогнать unit/contract tests;
7. preflight на Testnet;
8. startup recovery;
9. только затем переключить traffic.

## Live gate

Live не разрешается только по PASS backtest. Требуются signed review, shadow
evidence, Testnet failure tests и ограниченный initial notional.

**Текущий статус: `LIVE: PROHIBITED_PENDING_ACCEPTANCE`.** Запуск с
`BINANCE_TESTNET=false` всегда отклоняется кодом. Не пытайтесь обходить это
изменением environment или manifest: для перехода к canary требуется отдельное
reviewed изменение исходного кода, новая сборка и новый code fingerprint.

Перед каждым live startup проверьте:

1. три role-separated API key, IP allowlist, withdrawal disabled;
2. credentials поступают через `*_FILE`, а не прямые environment values;
3. отдельный sub-account совпадает с подписанным `subaccount_id`;
4. recovery report не старше 30 дней и его SHA-256 включён в authorization;
5. authorization подписан двумя разными Ed25519-ключами и действует не более 24 часов;
6. deployment/code/data/strategy/risk fingerprints и approved run IDs совпадают;
7. Operator API доступен только через HTTPS reverse proxy;
8. `ALLOW_LIVE_RESEARCH_PROMOTION=false` в execution service.

`examples/runtime_stdin.py` всегда отказывает в live. Для live используется только
установленный operator service с указанным gate.

Порядок снятия запрета:

```text
PROHIBITED_PENDING_ACCEPTANCE
→ реальные contracts/chaos/restore/shadow/Testnet report/review
→ отдельная CANARY_ONLY build с hard max notional
→ завершённый canary + независимый review
→ отдельное решение о production live
```
