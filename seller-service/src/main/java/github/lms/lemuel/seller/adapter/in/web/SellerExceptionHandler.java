package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.domain.exception.IllegalSubmissionStateException;
import github.lms.lemuel.seller.domain.exception.InsufficientSellerRoleException;
import github.lms.lemuel.seller.domain.exception.NotASellerException;
import github.lms.lemuel.seller.domain.exception.SellerScopeNotFoundException;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 셀러 콘솔 예외 → HTTP.
 *
 * <p>여기서 나가는 코드는 화면이 <b>어떤 문구를 띄우고 무엇을 다시 하게 할지</b> 고르는 데 쓰인다.
 * 겉보기엔 다 "안 됩니다" 지만 사용자가 해야 할 일이 서로 다르다.
 *
 * <ul>
 *   <li>{@code 403 NOT_A_SELLER_MEMBER} — 어느 조직에도 안 속해 있다. 일반 회원이 콘솔 URL 을
 *       직접 연 경우가 대부분이지만, <b>방금 초대된 사람</b>도 잠깐 여기에 걸린다
 *       ({@code organization.member_joined} 도착 전 창). 문구는 "권한 없음" 으로 끝내지 말고
 *       "초대 직후라면 잠시 뒤 다시" 를 같이 말해야 한다.</li>
 *   <li>{@code 422 NOT_A_SELLER_ORG} — 조직은 맞는데 파는 쪽이 아니다. CORPORATE 면 정상이고,
 *       SELLER 인데 나오면 {@code externalRef} 가 깨진 데이터라 운영자가 봐야 한다. 403 을 쓰지
 *       않는 이유는 <b>권한 문제가 아니기 때문</b>이다 — 403 이면 화면이 로그아웃을 유도하고,
 *       다시 로그인해도 똑같이 실패한다.</li>
 *   <li>{@code 403 INSUFFICIENT_ROLE} — 소속도 셀러도 맞는데 역할이 모자란다(STAFF 의 제출·송장).
 *       사용자가 할 일은 재로그인이 아니라 <b>조직 관리자에게 요청</b>이다. 그래서 위 둘과
 *       코드를 나눠 둔다 — 같은 403 이어도 화면 문구가 달라야 한다.</li>
 *   <li>{@code 409 ILLEGAL_STATE} — 지금 상태에서 할 수 없는 전이. 두 사람이 같은 신청서를
 *       열어 둔 경우가 대부분이라, 화면이 해야 할 일은 입력 수정이 아니라 <b>새로고침</b>이다.
 *       400 으로 내보내면 사용자는 자기 입력이 틀린 줄 알고 계속 고친다.</li>
 *   <li>{@code 404 SUBMISSION_NOT_FOUND} — 없거나 내 것이 아니거나. 둘을 구분하지 않는 이유는
 *       {@link SubmissionNotFoundException} 에 있다.</li>
 * </ul>
 *
 * <p>본문에 예외 메시지를 그대로 싣는 것은 여기 <b>열거된 예외들에 한해</b> 안전하다 —
 * 메시지에 조직 ID·역할·상태만 들어가고 그건 이미 호출자 본인의 것이다. 그래서 포괄
 * {@code Exception} 핸들러는 두지 않는다. 나머지는 프레임워크 기본 처리로 보내 내부 메시지가
 * 새지 않게 한다.
 */
@RestControllerAdvice(assignableTypes = {
        SellerProfileController.class,
        SellerProductController.class,
        SellerOrderController.class,
        SellerReviewController.class})
class SellerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SellerExceptionHandler.class);

    @ExceptionHandler(SellerScopeNotFoundException.class)
    ResponseEntity<Map<String, String>> notMember(SellerScopeNotFoundException e) {
        return body(HttpStatus.FORBIDDEN, "NOT_A_SELLER_MEMBER", e.getMessage());
    }

    @ExceptionHandler(NotASellerException.class)
    ResponseEntity<Map<String, String>> notSellerOrg(NotASellerException e) {
        // SELLER 인데 셀러 ID 를 못 만든 경우는 데이터 문제다. 사용자는 아무것도 할 수 없고
        // 운영자만 고칠 수 있으므로, 조용히 422 만 내보내지 말고 흔적을 남긴다.
        log.info("셀러 범위 없음: {}", e.getMessage());
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_A_SELLER_ORG", e.getMessage());
    }

    @ExceptionHandler(InsufficientSellerRoleException.class)
    ResponseEntity<Map<String, String>> insufficientRole(InsufficientSellerRoleException e) {
        return body(HttpStatus.FORBIDDEN, "INSUFFICIENT_ROLE", e.getMessage());
    }

    @ExceptionHandler(IllegalSubmissionStateException.class)
    ResponseEntity<Map<String, String>> illegalState(IllegalSubmissionStateException e) {
        return body(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.getMessage());
    }

    @ExceptionHandler(SubmissionNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(SubmissionNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message == null ? "" : message));
    }
}
