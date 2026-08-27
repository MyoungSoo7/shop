package github.lms.lemuel.inquiry.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 그런 문의가 없다.
 *
 * <p>레거시는 상세를 읽은 뒤 결과를 <b>확인하지 않고</b> 곧바로 필드를 꺼냈다
 * ({@code detailVO.getFile_attach_idx()}). 없는 문의거나 남의 문의면 상세 쿼리가 아무것도
 * 돌려주지 않으므로, 사용자는 403·404 대신 NPE 로 만들어진 500 을 받았다.
 */
public class InquiryNotFoundException extends BusinessException {

    public InquiryNotFoundException(Long inquiryId) {
        super(ErrorCode.INQUIRY_NOT_FOUND, "문의를 찾을 수 없습니다: inquiryId=" + inquiryId);
    }
}
