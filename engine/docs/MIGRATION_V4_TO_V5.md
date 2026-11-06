# Миграция v4 → v5

## Что сохраняется

Execution backend v4 не заменён: saga, risk ledger, recovery, supervisor и Operator Layer продолжают использовать прежние интерфейсы.

`STATE_DB` и `OPERATOR_DB` можно перенести после резервного копирования. Сначала проверьте миграцию на копии и Testnet.

## Что добавлено

Создаётся отдельный:

```dotenv
RESEARCH_DB=./var/research.db
```

В нём хранятся datasets, reports, approvals и shadow trades. v5 автоматически добавляет `state_json` старой таблице shadow при необходимости.

## Порядок

1. Остановить v4 корректно.
2. Убедиться, что нет `FAILED_UNCERTAIN` и unresolved saga.
3. Создать резервные копии всех SQLite-файлов.
4. Распаковать v5 в новый каталог.
5. Перенести `.env`, затем добавить v5 research-переменные.
6. Запустить `pytest -q`.
7. Запустить preflight на Testnet.
8. Запустить dashboard с `AUTO_SIGNAL_GENERATOR=false`.
9. Проверить позиции, ордера, saga и режим.
10. Только затем создавать datasets и approvals.

Ни один v4 сигнал не считается автоматически доказанной альфой. Для автоматического запуска требуется новый v5 research run и governance chain.
