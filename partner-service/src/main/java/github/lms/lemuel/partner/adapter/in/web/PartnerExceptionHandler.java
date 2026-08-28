package github.lms.lemuel.partner.adapter.in.web;

import github.lms.lemuel.partner.domain.exception.NoSalesScopeException;
import github.lms.lemuel.partner.domain.exception.PartnerScopeNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 파트너 콘솔 예외 → HTTP.
 *
 * <p>여기서 나가는 코드는 화면이 <b>어떤 문구를 띄울지</b> 고르는 데 쓰인다. 세 가지가 겉보기엔
 * 다 "못 봅니다" 지만 사용자가 해야 할 일이 서로 다르다.
 *
 * <ul>
 *   <li>{@code 403 NOT_A_PARTNER} — 어느 조직에도 안 속해 있다. 일반 회원이 콘솔 URL 을 직접
 *       연 경우가 대부분이지만, <b>방금 초대된 사람</b>도 잠깐 여기에 걸린다
 *       ({@code organization.member_joined} 가 도착하기 전 창). 그래서 화면 문구는 "권한 없음"
 *       으로 끝내지 말고 "초대 직후라면 잠시 뒤 다시" 를 같이 말해야 한다.</li>
 *   <li>{@code 422 NO_SALES_SCOPE} — 조직은 맞는데 매출을 볼 대상이 아니다. CORPORATE 조직이면
 *       정상이고(구매만 하는 기업 고객), SELLER 인데 나오면 {@code externalRef} 가 깨진 데이터라
 *       운영자가 봐야 한다. 403 을 쓰지 않는 이유는 <b>권한 문제가 아니기 때문</b>이다 — 403 으로
 *       내보내면 화면이 로그아웃을 유도하고, 다시 로그인해도 똑같이 실패한다.</li>
 *   <li>{@code 403 FORBIDDEN} — 인증 자체가 없다. 필터가 401 을 내는 경로와 달리 이건 컨트롤러
 *       진입 후 {@code AuthPrincipal} 이 비어 있는 경우다.</li>
 * </ul>
 *
 * <p>본문에 예외 메시지를 그대로 싣는 것은 이 서비스에서는 안전하다 — 파트너 예외 메시지에는
 * 조직 ID 와 사유만 들어가고, 그 조직 ID 는 이미 호출자 본인의 것이다. 다만 그 판단이 유효한
 * 것은 여기 <b>열거된 예외들뿐</b>이므로, 포괄 {@code Exception} 핸들러는 두지 않는다. 나머지는
 * 프레임워크 기본 처리로 보내 내부 메시지가 새지 않게 한다.
 */
@RestControllerAdvice(assignableTypes = {
        PartnerProfileController.class,
        PartnerSalesController.class,
        PartnerOrderController.class})
class PartnerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PartnerExceptionHandler.class);

    @ExceptionHandler(PartnerScopeNotFoundException.class)
    ResponseEntity<Map<String, String>> notPartner(PartnerScopeNotFoundException e) {
        return body(HttpStatus.FORBIDDEN, "NOT_A_PARTNER", e.getMessage());
    }

    @ExceptionHandler(NoSalesScopeException.class)
    ResponseEntity<Map<String, String>> noSalesScope(NoSalesScopeException e) {
        // SELLER 인데 셀러 ID 를 못 만든 경우는 데이터 문제다. 사용자는 아무것도 할 수 없고
        // 운영자만 고칠 수 있으므로, 조용히 422 만 내보내지 말고 흔적을 남긴다.
        log.info("매출 범위 없음: {}", e.getMessage());
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "NO_SALES_SCOPE", e.getMessage());
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
