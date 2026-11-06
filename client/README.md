# SMC Chart & Trading Terminal (Client)

High-performance desktop trading terminal and Smart Money Concepts (SMC) analyzer built with Java 21, Swing, and Gradle.

## Features

- **Smart Money Concepts (SMC) Analysis**: Real-time detection of Order Blocks, Fair Value Gaps (FVG), Liquidity Sweeps, Market Structure Shifts (BOS/CHoCH), and Imbalance zones.
- **Multi-Exchange Execution**: Direct REST and FIX gateways for Binance USD-M Futures, Bybit, and BingX with pre-trade risk normalization and safety gates.
- **Order Flow & Footprint**: Real-time volume delta, footprint candles, and market depth analytics.
- **Adaptive Factor Weights**: Local ML integration for dynamic signal scoring and expectancy tracking.
- **Comprehensive Backtesting**: Event-driven backtester supporting tick-level simulation, fee accounting, and walk-forward validation.

## Prerequisites

- JDK 21 or higher
- Gradle (wrapper provided)

## Getting Started

### Build

```bash
./gradlew build -x test
```

### Run Application

```bash
./gradlew run
```

### Configuration

Copy the example configuration files and set your preferences:

```bash
cp settings/account.properties.example settings/account.properties
```

Edit `settings/account.properties` to set your exchange keys (supports paper and live modes).
