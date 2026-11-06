.PHONY: install test validate dashboard preflight wheel clean

install:
	python -m pip install -e '.[operator,dev]'

test:
	pytest -q

validate:
	python -m compileall -q smc_execution examples tests
	node --check smc_execution/operator_ui/app.js
	pytest -q

dashboard:
	python examples/run_operator_dashboard.py

preflight:
	python examples/preflight_binance.py

wheel:
	python -m pip wheel . --no-deps -w dist

clean:
	rm -rf build dist *.egg-info .pytest_cache .coverage htmlcov
