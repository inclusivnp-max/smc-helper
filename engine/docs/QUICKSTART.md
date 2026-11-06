# Быстрый запуск SMC Execution Platform v5

## 1. Установка

```bash
unzip smc_execution_platform_v5.zip
cd smc_execution_engine_v5
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e '.[operator,dev]'
pytest -q
```

Windows PowerShell:

```powershell
py -3.12 -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e ".[operator,dev]"
pytest -q
```

## 2. Testnet-конфигурация

```bash
cp .env.example .env
install -d -m 700 credentials
for name in binance_trade_api_key binance_trade_api_secret \
  binance_user_data_api_key binance_user_data_api_secret \
  binance_monitor_api_key binance_monitor_api_secret operator_token; do
  install -m 600 /dev/null "credentials/$name"
done
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Запишите Testnet credentials и сгенерированный operator token в соответствующие
файлы. Минимальный локальный `.env`:

```dotenv
BINANCE_TESTNET=true
BINANCE_TRADE_API_KEY_FILE=./credentials/binance_trade_api_key
BINANCE_TRADE_API_SECRET_FILE=./credentials/binance_trade_api_secret
BINANCE_USER_DATA_API_KEY_FILE=./credentials/binance_user_data_api_key
BINANCE_USER_DATA_API_SECRET_FILE=./credentials/binance_user_data_api_secret
BINANCE_MONITOR_API_KEY_FILE=./credentials/binance_monitor_api_key
BINANCE_MONITOR_API_SECRET_FILE=./credentials/binance_monitor_api_secret

SYMBOLS=BTCUSDT
EXECUTION_TIMEFRAME=5m
STATE_DB=./var/execution_state.db
OPERATOR_DB=./var/operator.db
RESEARCH_DB=./var/research.db

OPERATOR_TOKEN_FILE=./credentials/operator_token
OPERATOR_HOST=127.0.0.1
OPERATOR_PORT=8080

AUTO_SIGNAL_GENERATOR=false
ALLOW_LIVE_RESEARCH_PROMOTION=false
```

Файл загружается автоматически примером `run_operator_dashboard.py`. Для live
прямые значения секретов в environment запрещены; обязательны credential files.

## 3. Preflight

```bash
python examples/preflight_binance.py
```

До запуска убедитесь:

- `user_stream_healthy=true`;
- нет неизвестных позиций;
- нет orphan regular/algo orders;
- `unresolved=0`;
- используется Demo/Testnet аккаунт.

## 4. Dashboard

```bash
python examples/run_operator_dashboard.py
```

Открыть `http://127.0.0.1:8080` и ввести `OPERATOR_TOKEN`.

## 5. Загрузка данных

В интерфейсе:

1. **Research → Получение данных**;
2. выбрать `BTCUSDT`, `5m`;
3. для первого полноценного исследования взять не менее 90 дней и 10 000 свечей;
4. нажать «Загрузить dataset»;
5. ввести фразу подтверждения.

CLI:

```bash
python examples/download_binance_research.py \
  --symbol BTCUSDT \
  --interval 5m \
  --start 2026-01-01T00:00:00Z \
  --end 2026-07-01T00:00:00Z \
  --db ./var/research.db
```

Текущая незакрытая свеча автоматически исключается. Исторические OI/funding могут быть частично недоступны; missing не заменяется будущими значениями.

## 6. Walk-forward

В интерфейсе:

1. выбрать dataset;
2. выбрать режим `CAPITAL_PROTECTION`;
3. нажать «Запустить walk-forward»;
4. дождаться `COMPLETED`;
5. открыть run и проверить folds, stress, baselines и причины gate.

Research выполняется в отдельном процессе и не должен блокировать protection supervisor.

## 7. Governance

При `Alpha gate = PASS`:

```text
PROMOTE RESEARCH_PASSED
→ PROMOTE SHADOW
→ накопить shadow evidence
→ PROMOTE TESTNET
```

`TESTNET` не будет создан без предыдущего SHADOW approval и минимального числа закрытых shadow-сделок.

## 8. Автоматический shadow

```dotenv
AUTO_SIGNAL_GENERATOR=true
APPROVED_RUN_IDS=<run-id>
SIGNAL_ENVIRONMENT=shadow
```

Перезапустить dashboard. На странице Research появятся shadow signals/trades.

## 9. Testnet execution

После допуска `TESTNET`:

```dotenv
BINANCE_TESTNET=true
AUTO_SIGNAL_GENERATOR=true
APPROVED_RUN_IDS=<тот же run-id>
SIGNAL_ENVIRONMENT=testnet
EXECUTION_TIMEFRAME=5m
```

Активный режим runtime обязан совпадать с профилем research run. Иначе сигнал будет отклонён Alpha Gate.

Опциональный сетевой contract smoke:

```bash
RUN_BINANCE_TESTNET_CONTRACT=1 pytest -m binance_contract -q
RUN_BINANCE_TESTNET_CONTRACT=1 \
RUN_BINANCE_TESTNET_ORDER_CONTRACT=1 \
pytest -m binance_contract -q
```

Второй запуск создаёт и отменяет минимальную заявку только на Binance Testnet.
Используйте отдельный чистый Testnet-аккаунт; live endpoint в этих тестах жёстко
отключён.

## 10. При любой неопределённости

Если появились `FAILED_UNCERTAIN`, unhealthy private stream или unresolved recovery:

1. нажать **Остановить входы**;
2. не повторять сигнал вручную;
3. запустить **Сверить**;
4. проверить Binance позиции и regular/algo orders;
5. возобновлять admission только после `unresolved=0`.
