package github.lms.lemuel.marketing.adapter.in.web;

import github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.domain.exception.DayNotEligibleException;
import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 프로모션 예외 → HTTP.
 *
 * <p>상태 코드를 나누는 기준은 <b>사용자가 다시 시도해서 될 일인가</b>다.
 *
 * <ul>
 *   <li>{@code 404} — 없는 캠페인. 링크가 죽었거나 종료돼 사라진 것이다.</li>
 *   <li>{@code 409} — 이미 참여했다. 다시 눌러도 결과가 같다는 뜻이라 409 다. 화면은 이걸 받고
 *       "오늘은 이미 출석했어요" 를 보여 주면 된다.</li>
 *   <li>{@code 422} — 요청은 멀쩡한데 지금 조건이 아니다(기간 밖, 오늘은 출석 대상 요일이 아님,
 *       남은 경품 없음). 400 을 쓰지 않는 이유는 클라이언트가 잘못 보낸 게 아니기 때문이다 —
 *       400 으로 내보내면 프론트가 "버그" 로 오해해 재시도 로직을 넣는다.</li>
 * </ul>
 *
 * <p>본문에 스택트레이스나 내부 메시지를 담지 않는다. 이 API 는 로그인한 일반 사용자가 직접
 * 호출하는 자리라, 여기서 흘린 내부 정보는 그대로 공개된다.
 */
@RestControllerAdvice(assignableTypes = {PromotionController.class, AdminPromotionController.class})
class MarketingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketingExceptionHandler.class);

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(CampaignNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AlreadyParticipatedException.class)
    ResponseEntity<Map<String, String>> alreadyParticipated(AlreadyParticipatedException e) {
        return body(HttpStatus.CONFLICT, "ALREADY_PARTICIPATED", e.getMessage());
    }

    @ExceptionHandler(CampaignNotOpenException.class)
    ResponseEntity<Map<String, String>> notOpen(CampaignNotOpenException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "CAMPAIGN_NOT_OPEN", e.getMessage());
    }

    @ExceptionHandler(DayNotEligibleException.class)
    ResponseEntity<Map<String, String>> notEligible(DayNotEligibleException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "DAY_NOT_ELIGIBLE", e.getMessage());
    }

    @ExceptionHandler(NoPrizeAvailableException.class)
    ResponseEntity<Map<String, String>> noPrize(NoPrizeAvailableException e) {
        // 경품 소진은 운영자가 알아야 하는 사건이다 — 이벤트는 열려 있는데 줄 게 없는 상태다.
        log.info("럭키박스 경품 소진: {}", e.getMessage());
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "NO_PRIZE_AVAILABLE", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /**
     * 운영자가 만든 잘못된 상태 — 경품 없는 이벤트를 열려는 시도 등.
     *
     * <p>고객 경로에서는 나오지 않는다. 나온다면 그건 진짜 버그이므로 메시지를 그대로 노출하지
     * 않고 로그로만 남긴다.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflictState(IllegalStateException e) {
        log.warn("프로모션 상태 오류", e);
        return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message == null ? "" : message));
    }
}
