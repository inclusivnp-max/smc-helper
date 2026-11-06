# SMC Trading Platform & Helper

<p align="left">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Python-3.12-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python 3.12" />
  <img src="https://img.shields.io/badge/FastAPI-009688?style=for-the-badge&logo=fastapi&logoColor=white" alt="FastAPI" />
  <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis" />
  <img src="https://img.shields.io/badge/Architecture-Event--Driven%20Saga-blue?style=for-the-badge" alt="Event-Driven Saga" />
  <img src="https://img.shields.io/badge/Trading-Smart%20Money%20Concepts-success?style=for-the-badge" alt="SMC Trading" />
</p>

Unified high-performance Smart Money Concepts (SMC) quantitative trading ecosystem.

The platform consists of two specialized components:
1. **[Client (`client/`)](client/README.md)**: A high-performance desktop trading terminal (Java 21, Swing) featuring real-time SMC pattern detection, orderflow footprint charts, multi-timeframe radar, and FIX/REST direct execution.
2. **[Engine (`engine/`)](engine/README.md)**: A fail-closed algorithmic execution engine & research suite (Python 3.12+, FastAPI, Redis) with durable saga recovery, atomic risk ledgers, and purged walk-forward validation.

---

## Architecture Overview

```text
                     +---------------------------------------+
                     |         Desktop Trading GUI           |
                     |         (client/ - Java 21)           |
                     |  - SMC Structure, OB, FVG, Liquidity  |
                     |  - Order Flow & Footprint Delta       |
                     |  - Multi-Timeframe Scoring Radar      |
                     +-------------------+-------------------+
                                         |
                       Signals / Trades  |  Direct FIX / REST
                                         v
                     +---------------------------------------+
                     |          Execution Engine             |
                     |        (engine/ - Python 3)           |
                     |  - Atomic Portfolio Risk Ledger       |
                     |  - Redis Saga State & Recovery        |
                     |  - Purged Walk-Forward Alpha Gate     |
                     |  - Operator Dashboard & REST API      |
                     +-------------------+-------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |       Exchange / Broker Gateways      |
                     |   (Binance USD-M, Bybit, BingX)       |
                     +---------------------------------------+
```

---

## Components

### 1. Terminal Client (`client/`)

A standalone desktop application designed for low-latency analysis and manual/automated execution.

- **Smart Money Mechanics**: Algorithmic detection of Liquidity Sweeps, Change of Character (CHoCH), Break of Structure (BOS), Fair Value Gaps (FVG), and Imbalance zones.
- **Direct Broker Gateways**: Embedded gateways for Binance USD-M Futures, Bybit, and BingX.
- **Local Machine Learning**: Adaptive factor weight optimizer (`tools/train_ml_weights.py`).

**Quick Run:**
```bash
cd client
./gradlew run
```

### 2. Execution Engine (`engine/`)

A production-grade algorithmic execution engine focused on risk control and verifiable alpha validation.

- **Fail-Closed Execution**: Durable saga transaction management for order routing and position reconciliation.
- **Alpha Governance**: Walk-forward analysis with block bootstrap stress testing and automated alpha gate verdict before live routing.
- **Operator Dashboard**: Built-in FastAPI service with real-time web dashboard for telemetry, risk controls, and emergency stops.

**Quick Run:**
```bash
cd engine
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[operator,dev]'
pytest
```

---

## Configuration & Security

All local configurations and credentials are decoupled from version control:
- Client configuration template: `client/settings/account.properties.example`
- Engine environment template: `engine/.env.example`

Credentials should be provided via environment variables or protected credential files.

---

## License

MIT License. See individual subdirectories for third-party notices.
