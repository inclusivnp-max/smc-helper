FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN groupadd --system smc && useradd --system --gid smc --home /app smc
WORKDIR /app

COPY pyproject.toml README_RU.md ./
COPY smc_execution ./smc_execution
COPY examples ./examples
RUN python -m pip install --upgrade pip && \
    python -m pip install '.[operator,redis]'

RUN mkdir -p /var/lib/smc-execution && chown -R smc:smc /var/lib/smc-execution /app
USER smc

ENV STATE_DB=/var/lib/smc-execution/execution_state.db \
    OPERATOR_DB=/var/lib/smc-execution/operator.db \
    RESEARCH_DB=/var/lib/smc-execution/research.db \
    BINANCE_TESTNET=true \
    SYMBOLS=BTCUSDT \
    OPERATOR_HOST=0.0.0.0 \
    OPERATOR_PORT=8080 \
    OPERATOR_BEHIND_HTTPS_PROXY=false

VOLUME ["/var/lib/smc-execution"]
EXPOSE 8080
ENTRYPOINT ["python", "examples/run_operator_dashboard.py"]
