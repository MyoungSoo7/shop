package github.lms.lemuel.organization.application.exception;

/**
 * 조직 리소스에 대한 권한 없는 접근 — 웹 어댑터에서 403 으로 매핑된다.
 *
 * <p>인가는 JWT 주체(userId)의 조직 내 활성 역할로 판정한다. 요청 파라미터의 조직/역할을 신뢰하지 않는다(IDOR).
 */
public class ForbiddenOrgAccessException extends RuntimeException {

    public ForbiddenOrgAccessException(String message) {
        super(message);
    }
}
