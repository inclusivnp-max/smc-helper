# Governance и допуск стратегии

## Состояния

```text
DRAFT
  ↓
RESEARCH_PASSED
  ↓
SHADOW
  ↓
TESTNET
  ↓
LIVE
```

Любой approval можно отозвать. Live promotion по умолчанию отключён.

## RESEARCH_PASSED

Разрешается только для завершённого run с `gate.passed=true`. Фиксируются:

- run ID;
- strategy ID/version;
- code fingerprint;
- dataset fingerprint;
- simulation profile;
- actor, reason и время.

## SHADOW

Требует `RESEARCH_PASSED` того же run. Сигналы отслеживаются на закрытых свечах без отправки ордеров. Shadow state durable: рестарт не сбрасывает trailing state, holding bars и funding.

## TESTNET

Требует:

- SHADOW approval того же run;
- минимальное число закрытых shadow trades;
- shadow expectancy не ниже policy threshold;
- точное совпадение symbol, interval, code и dataset.

## LIVE

Durable LIVE approval требует TESTNET approval того же run и создаётся только в
отдельном governance-процессе. В live execution service
`ALLOW_LIVE_RESEARCH_PROMOTION=false`. Фактический live startup дополнительно
требует короткоживущий authorization manifest с двумя независимыми
Ed25519-подписями и точной привязкой deployment/sub-account/code/data/risk.

## Runtime binding

Перед каждым governed signal проверяется:

- approval активен и не истёк;
- stage достаточен для environment;
- run ID совпадает;
- code fingerprint совпадает;
- immutable dataset существует;
- dataset fingerprint совпадает;
- symbol совпадает;
- interval в signal metadata совпадает;
- execution ATR timeframe совпадает;
- активный runtime risk profile совпадает с research profile.

Любое несоответствие приводит к отказу до входа в Execution Engine.

## Portfolio approvals

Один research run соответствует одному рынку. Портфель формируется списком independently approved run IDs:

```dotenv
APPROVED_RUN_IDS=btc-run-id,eth-run-id,sol-run-id
```

Два run для одной пары `symbol + interval` одновременно запрещены.

## Изменение режима

Смена режима разрешена только при отсутствии:

- открытых позиций;
- regular/algo orders;
- non-terminal execution saga.

`PAUSED` является admission overlay и не меняет защитную политику уже существующей позиции.

## Operator discipline

Ручные команды должны использоваться для уменьшения риска, recovery и формального продвижения. Не следует переключать режим на `PROFITABILITY` после серии убытков или подгонять approval под текущий рынок без нового frozen research run.
