class ExecutionError(RuntimeError):
    """A known, bounded failure that does not imply unknown exchange exposure."""


class CriticalExecutionError(RuntimeError):
    """The engine cannot prove that exposure is flat or protected."""


class DeadlineExceeded(ExecutionError):
    pass


class StateConflict(ExecutionError):
    pass


class ExchangeReject(ExecutionError):
    def __init__(self, message: str, *, code: int | None = None) -> None:
        super().__init__(message)
        self.code = code


class OrderNotFound(ExecutionError):
    pass


class AmbiguousSubmitError(CriticalExecutionError):
    """The transport failed and the adapter cannot prove whether the order exists."""


class AdmissionHalted(ExecutionError):
    pass
