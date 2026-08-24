package github.lms.lemuel.common.audit.application;

import github.lms.lemuel.common.audit.domain.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 감사 로그 대상 유스케이스 표시.
 *
 * <p>detail/resourceId 는 SpEL 로 평가한다. 기본값은 인자를 기록하지 않으므로,
 * 비밀번호·토큰 같은 민감값은 명시적으로 선택하지 않는 한 audit_logs 에 남지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    AuditAction action();

    /**
     * 실패 시 별도 action 으로 기록해야 할 때 enum 이름을 지정한다.
     * 비워두면 성공 action 과 같은 action 으로 outcome=FAILURE 를 기록한다.
     */
    String failureAction() default "";

    String resourceType();

    String resourceId() default "";

    String detail() default "";

    boolean recordOnSuccess() default true;

    boolean recordOnFailure() default true;
}
