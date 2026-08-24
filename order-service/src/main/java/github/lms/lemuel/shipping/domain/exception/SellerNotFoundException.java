package github.lms.lemuel.shipping.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 배송비 정책을 걸려는 셀러가 없다 — 404.
 *
 * <p>불변식 위반(400)과 구분한다. 값이 잘못된 것이 아니라 <b>대상이 없는</b> 것이고,
 * 운영자가 할 일도 다르다(값을 고치는 게 아니라 셀러 ID 를 다시 찾는다).
 */
public class SellerNotFoundException extends BusinessException {

    public SellerNotFoundException(Long sellerId) {
        super(ErrorCode.USER_NOT_FOUND, "셀러를 찾을 수 없습니다: sellerId=" + sellerId);
    }
}
