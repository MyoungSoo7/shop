package github.lms.lemuel.operation.site.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.operation.site.application.service.PopupAdminService.PopupNotFoundException;
import github.lms.lemuel.operation.site.domain.exception.InvalidPopupStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * site 도메인 예외 → HTTP 번역. education 의 advice 와 같은 구조이고 같은 이유다 —
 * 매핑하지 않으면 {@link InvalidPopupStateException} 이 공통 catch-all 을 타고 <b>500</b> 이 되는데,
 * 실제로는 운영자가 고칠 수 있는 400 이다(구간이 뒤집혔다 / 이미 지운 팝업이다).
 *
 * <p>도메인이 {@code BusinessException} 을 상속하지 않는 것은 ArchUnit 이 도메인의 스프링 의존을
 * 금지하기 때문이다. 그래서 번역이 도메인이 아니라 이 어댑터에 있다.
 */
@RestControllerAdvice
public class SiteExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SiteExceptionHandler.class);

    @ExceptionHandler(PopupNotFoundException.class)
    public ResponseEntity<ErrorResponse> popupNotFound(PopupNotFoundException exception) {
        return translate(ErrorCode.POPUP_NOT_FOUND, exception);
    }

    @ExceptionHandler(InvalidPopupStateException.class)
    public ResponseEntity<ErrorResponse> invalidPopupState(InvalidPopupStateException exception) {
        return translate(ErrorCode.POPUP_INVALID_STATE, exception);
    }

    private ResponseEntity<ErrorResponse> translate(ErrorCode code, Exception exception) {
        log.warn("[{}] {}", code.code(), exception.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), exception.getMessage()));
    }
}
