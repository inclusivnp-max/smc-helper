from __future__ import annotations

import asyncio
import hmac
from contextlib import asynccontextmanager
from importlib.resources import files
from typing import Annotated, Any

try:
    from fastapi import (
        Depends,
        FastAPI,
        Header,
        HTTPException,
        Query,
        Request,
        WebSocket,
        WebSocketDisconnect,
        status,
    )
    from fastapi.responses import JSONResponse
    from fastapi.staticfiles import StaticFiles
    from pydantic import BaseModel, Field
except ImportError as exc:  # pragma: no cover - clear optional-dependency message
    raise RuntimeError(
        "Operator dashboard requires: pip install 'smc-execution-engine[operator]'"
    ) from exc

from .modes import ModeSwitchRejected, TradingMode
from .models import OrderKind
from .research_models import ApprovalStage
from .research_service import ResearchCommandError
from .operator_service import OperatorCommandError, OperatorService


class HaltRequest(BaseModel):
    reason: str = Field(min_length=3, max_length=300)
    confirmation: str


class ReconcileRequest(BaseModel):
    resume_on_success: bool = False
    confirmation: str


class ModeRequest(BaseModel):
    mode: TradingMode
    confirmation: str


class FlattenPlanRequest(BaseModel):
    symbol: str | None = Field(default=None, max_length=30)


class FlattenConfirmRequest(BaseModel):
    plan_id: str = Field(min_length=10, max_length=100)
    confirmation_phrase: str = Field(min_length=5, max_length=100)


class AuthContext(BaseModel):
    actor: str


class ResearchDownloadRequest(BaseModel):
    symbol: str = Field(min_length=3, max_length=30, pattern=r"^[A-Za-z0-9]+$")
    interval: str = Field(min_length=2, max_length=4)
    start_time_ms: int = Field(ge=0)
    end_time_ms: int = Field(gt=0)
    include_mark_price: bool = True
    include_funding: bool = True
    include_open_interest: bool = True
    dataset_id: str | None = Field(default=None, min_length=3, max_length=200)
    confirmation: str


class ResearchRunRequest(BaseModel):
    dataset_id: str = Field(min_length=3, max_length=200)
    mode: TradingMode
    confirmation: str


class ResearchPromoteRequest(BaseModel):
    stage: ApprovalStage
    reason: str = Field(min_length=5, max_length=500)
    confirmation: str
    expires_at_ms: int | None = Field(default=None, ge=0)


def create_operator_app(
    service: OperatorService,
    *,
    start_runtime: bool = True,
    require_https_proxy: bool = False,
) -> FastAPI:
    telemetry_task: asyncio.Task[Any] | None = None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        nonlocal telemetry_task
        await service.initialize()
        if start_runtime:
            await service.runtime.start(service.settings.symbols)
            if service.mode_controller.current.mode is TradingMode.PAUSED:
                await service.control.halt_admission("trading mode is PAUSED")
        if service.automation is not None:
            try:
                await service.automation.start()
                service.automation_start_error = None
            except Exception as exc:
                service.automation_start_error = repr(exc)
                await service.control.halt_admission(
                    f"strategy automation failed to start: {exc!r}"
                )
        telemetry_task = asyncio.create_task(
            service.telemetry_loop(), name="operator-telemetry"
        )
        try:
            yield
        finally:
            service.stop_telemetry()
            if telemetry_task is not None:
                telemetry_task.cancel()
                await asyncio.gather(telemetry_task, return_exceptions=True)
            if service.automation is not None:
                await service.automation.stop()
            if service.research is not None:
                await service.research.shutdown()
            if start_runtime:
                await service.runtime.stop()

    app = FastAPI(
        title="SMC Execution Operator API",
        version="5.0.1",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        if (
            require_https_proxy
            and request.url.path != "/healthz"
            and request.headers.get("x-forwarded-proto", "").split(",", 1)[0].strip().lower()
            != "https"
        ):
            return JSONResponse(
                status_code=426,
                content={"detail": "HTTPS reverse proxy is required"},
            )
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Cache-Control"] = "no-store"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; "
            "img-src 'self' data:; connect-src 'self' ws: wss:; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
        )
        return response

    async def require_auth(
        authorization: Annotated[str | None, Header()] = None,
        x_operator_actor: Annotated[str | None, Header()] = None,
    ) -> AuthContext:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Missing bearer token",
            )
        supplied = authorization[7:]
        if not hmac.compare_digest(supplied, service.settings.token):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid bearer token",
            )
        actor = (x_operator_actor or service.settings.actor_name).strip()[:80]
        return AuthContext(actor=actor or "operator")

    def require_idempotency(value: str | None) -> str:
        if value is None or len(value.strip()) < 8:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Idempotency-Key header is required",
            )
        return value.strip()[:200]

    @app.exception_handler(OperatorCommandError)
    async def operator_error(_: Request, exc: OperatorCommandError):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.exception_handler(ModeSwitchRejected)
    async def mode_error(_: Request, exc: ModeSwitchRejected):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.exception_handler(ResearchCommandError)
    async def research_error(_: Request, exc: ResearchCommandError):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    def require_research():
        if service.research is None:
            raise HTTPException(status_code=503, detail="Research service is disabled")
        return service.research

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/v1/status")
    async def get_status(_: AuthContext = Depends(require_auth)):
        return await service.status_snapshot()

    @app.get("/api/v1/positions")
    async def get_positions(_: AuthContext = Depends(require_auth)):
        return {"items": await service.list_positions()}

    @app.get("/api/v1/orders")
    async def get_orders(
        kind: OrderKind | None = Query(default=None),
        _: AuthContext = Depends(require_auth),
    ):
        return {"items": await service.list_orders(kind)}

    @app.get("/api/v1/executions")
    async def get_executions(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        return {"items": await service.list_executions(limit)}

    @app.get("/api/v1/executions/{execution_id}")
    async def get_execution(
        execution_id: str,
        _: AuthContext = Depends(require_auth),
    ):
        item = await service.get_execution(execution_id)
        if item is None:
            raise HTTPException(status_code=404, detail="Execution not found")
        return item

    @app.get("/api/v1/modes")
    async def get_modes(_: AuthContext = Depends(require_auth)):
        return {
            "current": service.mode_controller.current.to_dict(),
            "items": [item.to_dict() for item in service.mode_controller.list_profiles()],
        }

    @app.get("/api/v1/telemetry")
    async def get_telemetry(
        limit: int = Query(default=720, ge=1, le=10000),
        since_ms: int | None = Query(default=None, ge=0),
        _: AuthContext = Depends(require_auth),
    ):
        return {
            "items": await service.store.telemetry_history(
                limit=limit, since_ms=since_ms
            )
        }

    @app.get("/api/v1/audit")
    async def get_audit(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        return {"items": await service.store.list_audit(limit)}

    @app.post("/api/v1/commands/halt")
    async def halt(
        body: HaltRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        if body.confirmation != "HALT":
            raise HTTPException(status_code=400, detail="Type HALT to confirm")
        return await service.halt(
            actor=auth.actor,
            reason=body.reason,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.post("/api/v1/commands/reconcile")
    async def reconcile(
        body: ReconcileRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        expected = "RESUME" if body.resume_on_success else "RECONCILE"
        if body.confirmation != expected:
            raise HTTPException(status_code=400, detail=f"Type {expected} to confirm")
        return await service.reconcile(
            actor=auth.actor,
            resume_on_success=body.resume_on_success,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.post("/api/v1/commands/mode")
    async def switch_mode(
        body: ModeRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        if body.confirmation != body.mode.value:
            raise HTTPException(
                status_code=400,
                detail=f"Type {body.mode.value} to confirm",
            )
        return await service.switch_mode(
            actor=auth.actor,
            mode=body.mode,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.post("/api/v1/commands/flatten/plan")
    async def flatten_plan(
        body: FlattenPlanRequest,
        auth: AuthContext = Depends(require_auth),
    ):
        return (
            await service.create_flatten_plan(actor=auth.actor, symbol=body.symbol)
        ).to_dict()

    @app.post("/api/v1/commands/flatten/confirm")
    async def flatten_confirm(
        body: FlattenConfirmRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        return await service.confirm_flatten(
            actor=auth.actor,
            plan_id=body.plan_id,
            confirmation_phrase=body.confirmation_phrase,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.get("/api/v1/research/datasets")
    async def research_datasets(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return {"items": await research.list_datasets(limit)}

    @app.get("/api/v1/research/data-jobs")
    async def research_data_jobs(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return {"items": await research.list_data_jobs(limit)}

    @app.post("/api/v1/research/datasets/download")
    async def research_download_dataset(
        body: ResearchDownloadRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        symbol = body.symbol.upper()
        expected = f"DOWNLOAD {symbol} {body.interval}"
        if body.confirmation != expected:
            raise HTTPException(status_code=400, detail=f"Type {expected} to confirm")
        research = require_research()
        return await research.start_dataset_download(
            symbol=symbol,
            interval=body.interval,
            start_time_ms=body.start_time_ms,
            end_time_ms=body.end_time_ms,
            include_mark_price=body.include_mark_price,
            include_funding=body.include_funding,
            include_open_interest=body.include_open_interest,
            dataset_id=body.dataset_id,
            actor=auth.actor,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.get("/api/v1/research/runs")
    async def research_runs(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return {"items": await research.list_runs(limit)}

    @app.get("/api/v1/research/runs/{run_id}")
    async def research_run(
        run_id: str,
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        report = await research.get_report(run_id)
        if report is None:
            raise HTTPException(status_code=404, detail="Research run not found")
        return report

    @app.get("/api/v1/research/shadow")
    async def research_shadow(
        run_id: str | None = Query(default=None),
        limit: int = Query(default=200, ge=1, le=2000),
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return {"items": await research.list_shadow(run_id, limit)}

    @app.get("/api/v1/research/shadow/{run_id}/summary")
    async def research_shadow_summary(
        run_id: str,
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return await research.shadow_summary(run_id)

    @app.get("/api/v1/research/approvals")
    async def research_approvals(
        limit: int = Query(default=100, ge=1, le=1000),
        _: AuthContext = Depends(require_auth),
    ):
        research = require_research()
        return {"items": await research.list_approvals(limit)}

    @app.post("/api/v1/research/runs")
    async def start_research_run(
        body: ResearchRunRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        if body.mode is TradingMode.PAUSED:
            raise HTTPException(status_code=400, detail="PAUSED is not a research mode")
        expected = f"RUN {body.dataset_id}"
        if body.confirmation != expected:
            raise HTTPException(status_code=400, detail=f"Type {expected} to confirm")
        research = require_research()
        return await research.start_run(
            dataset_id=body.dataset_id,
            mode=body.mode,
            actor=auth.actor,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.post("/api/v1/research/runs/{run_id}/promote")
    async def promote_research_run(
        run_id: str,
        body: ResearchPromoteRequest,
        auth: AuthContext = Depends(require_auth),
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        expected = f"PROMOTE {body.stage.value}"
        if body.confirmation != expected:
            raise HTTPException(status_code=400, detail=f"Type {expected} to confirm")
        research = require_research()
        return await research.promote(
            run_id=run_id,
            stage=body.stage,
            actor=auth.actor,
            reason=body.reason,
            expires_at_ms=body.expires_at_ms,
            idempotency_key=require_idempotency(idempotency_key),
        )

    @app.websocket("/api/v1/ws")
    async def websocket_status(websocket: WebSocket):
        if (
            require_https_proxy
            and websocket.headers.get("x-forwarded-proto", "")
            .split(",", 1)[0]
            .strip()
            .lower()
            != "https"
        ):
            await websocket.close(code=4403, reason="HTTPS reverse proxy is required")
            return
        await websocket.accept()
        try:
            async with asyncio.timeout(5):
                hello = await websocket.receive_json()
            supplied = str(hello.get("token", ""))
            if not hmac.compare_digest(supplied, service.settings.token):
                await websocket.close(code=4401, reason="Unauthorized")
                return
            await websocket.send_json({"type": "authenticated"})
            while True:
                await websocket.send_json(
                    {"type": "status", "payload": await service.status_snapshot()}
                )
                await asyncio.sleep(1.5)
        except (WebSocketDisconnect, TimeoutError):
            return
        except Exception:
            await websocket.close(code=1011, reason="Internal error")

    ui_dir = files("smc_execution").joinpath("operator_ui")
    app.mount("/", StaticFiles(directory=str(ui_dir), html=True), name="operator-ui")
    return app
