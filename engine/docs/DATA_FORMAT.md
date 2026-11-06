# Формат исследовательских данных

## CSV

Обязательные колонки:

```text
symbol,interval,open_time_ms,close_time_ms,open,high,low,close,volume
```

Поддерживаемые дополнительные колонки:

```text
quote_volume
trades
taker_buy_base
taker_buy_quote
mark_close
open_interest
funding_rate
open_interest_available_at_ms
funding_available_at_ms
```

Числа передавайте десятичными строками без локальных разделителей. Времена — Unix milliseconds UTC.

Пример:

```csv
symbol,interval,open_time_ms,close_time_ms,open,high,low,close,volume,quote_volume,trades
BTCUSDT,5m,1700000000000,1700000299999,36000.1,36120.0,35980.2,36080.4,123.45,4450000.2,8120
```

## Импорт

```bash
python examples/import_research_csv.py data/BTCUSDT_5m.csv \
  --db ./var/research.db \
  --dataset-id BTCUSDT-5m-vendor-2026 \
  --symbol BTCUSDT \
  --interval 5m
```

Без `--allow-gaps` dataset с пропусками будет отклонён. Для governable research используйте строгий режим.

## Availability timestamps

`open_interest` и `funding_rate` могут применяться только если соответствующий `*_available_at_ms` не позже времени закрытия свечи. Это предотвращает использование информации, появившейся после торгового решения.
