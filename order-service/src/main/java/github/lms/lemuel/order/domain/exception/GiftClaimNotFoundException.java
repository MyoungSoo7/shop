package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 그 토큰의 선물이 없다.
 *
 * <p><b>메시지에 사유를 담지 않는다.</b> "없는 토큰"과 "이미 폐기된 토큰"을 구분해 주면, 무작위로
 * 토큰을 던지는 쪽이 그 차이를 신호로 삼아 유효한 값에 접근한다. 받는 사람에게는 둘 다 같은
 * 결과이기도 하다 — 이 링크로는 아무것도 할 수 없다.
 */
public class GiftClaimNotFoundException extends BusinessException {

    public GiftClaimNotFoundException() {
        super(ErrorCode.GIFT_CLAIM_NOT_FOUND);
    }
}
