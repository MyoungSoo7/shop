package github.lms.lemuel.operation.incident.adapter.in.web;

import github.lms.lemuel.operation.incident.application.port.in.IncidentNotFoundException;
import github.lms.lemuel.operation.incident.domain.InvalidIncidentTransitionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 인시던트 API 예외 → HTTP 상태 매핑 (404 / 409 / 400). */
@RestControllerAdvice(basePackageClasses = OpsWebExceptionHandler.class)
public class OpsWebExceptionHandler {

    @ExceptionHandler(IncidentNotFoundException.class)
    public ProblemDetail handleNotFound(IncidentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidIncidentTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidIncidentTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("currentStatus", e.from().name());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "다른 운영자가 먼저 변경했습니다. 다시 조회 후 시도하세요.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
