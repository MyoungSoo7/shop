package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 송장 CSV 자체가 읽을 수 없는 형식 — 행별로 알릴 수 없어 파일 전체를 거절한다.
 * (행 단위 오류는 거절하지 않고 사유와 함께 미리보기에 싣는다.)
 */
public class InvalidTrackingCsvException extends BusinessException {

    public InvalidTrackingCsvException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
