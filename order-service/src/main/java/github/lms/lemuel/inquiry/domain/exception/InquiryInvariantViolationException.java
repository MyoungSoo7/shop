package github.lms.lemuel.inquiry.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 문의 도메인 불변식 위반 — 빈 제목·본문, 길이 초과, 종류가 요구하는 대상(상품·주문) 누락.
 *
 * <p>요청을 고치면 성공하므로 400 이다. "이미 답변된 문의를 고치려 했다"는 요청이 옳고 상태가
 * 다른 경우라 {@link InquiryAlreadyAnsweredException} 으로 따로 둔다 — 화면이 둘을 같은 오류로
 * 받으면 사용자에게 "입력을 고치세요"라고 잘못 안내하게 된다.
 */
public class InquiryInvariantViolationException extends BusinessException {

    public InquiryInvariantViolationException(String message) {
        super(ErrorCode.INQUIRY_INVARIANT, message);
    }
}
