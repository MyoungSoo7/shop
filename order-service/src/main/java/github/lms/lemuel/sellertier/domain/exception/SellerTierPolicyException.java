package github.lms.lemuel.sellertier.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 등급 정책 자체가 성립하지 않음(임계 누락·역전·비양수) — 미승인 상태로 자동 승급이 도는 것을 막는다. */
public class SellerTierPolicyException extends BusinessException {

    public SellerTierPolicyException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
