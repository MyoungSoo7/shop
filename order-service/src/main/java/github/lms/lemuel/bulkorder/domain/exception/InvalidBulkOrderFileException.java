package github.lms.lemuel.bulkorder.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 업로드 파일 자체가 읽히지 않음(헤더 없음·빈 파일). */
public class InvalidBulkOrderFileException extends BusinessException {
    public InvalidBulkOrderFileException(String message) {
        super(ErrorCode.INVALID_BULK_ORDER_FILE, message);
    }
}
