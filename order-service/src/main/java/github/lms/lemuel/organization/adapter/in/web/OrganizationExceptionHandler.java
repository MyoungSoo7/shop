package github.lms.lemuel.organization.adapter.in.web;

import github.lms.lemuel.organization.application.exception.DuplicateMembershipException;
import github.lms.lemuel.organization.application.exception.ForbiddenOrgAccessException;
import github.lms.lemuel.organization.application.exception.MembershipNotFoundException;
import github.lms.lemuel.organization.application.exception.OrganizationNotFoundException;
import github.lms.lemuel.organization.domain.InvalidMembershipTransitionException;
import github.lms.lemuel.organization.domain.InvalidOrganizationTransitionException;
import github.lms.lemuel.organization.domain.LastOwnerException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * organization 도메인·애플리케이션 예외 → HTTP 상태 매핑. shared-common 공통 핸들러(LOWEST_PRECEDENCE)보다
 * 먼저(HIGHEST_PRECEDENCE) 잡아 NotFound→404, 인가→403, 중복/상태전이/락→409, 규칙위반(마지막OWNER)→422 를 확정한다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrganizationExceptionHandler {

    @ExceptionHandler({OrganizationNotFoundException.class, MembershipNotFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ForbiddenOrgAccessException.class, AccessDeniedException.class})
    public ResponseEntity<Map<String, Object>> forbidden(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** 중복 멤버십(활성 슬롯 점유)·상태머신 위반·동시성 경합 → 409. */
    @ExceptionHandler({DuplicateMembershipException.class, InvalidOrganizationTransitionException.class,
            InvalidMembershipTransitionException.class, DataIntegrityViolationException.class,
            OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 마지막 OWNER 강등/제거 등 규칙 위반 → 422. */
    @ExceptionHandler(LastOwnerException.class)
    public ResponseEntity<Map<String, Object>> unprocessable(LastOwnerException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "message", message == null ? "" : message));
    }
}
