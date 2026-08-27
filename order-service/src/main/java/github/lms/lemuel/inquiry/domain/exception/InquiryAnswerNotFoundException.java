package github.lms.lemuel.inquiry.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 그 문의에 달린 답변이 아니다.
 *
 * <p>문의를 못 찾은 것과 뭉치지 않는다. 레거시의 답변 삭제({@code deleteProductQnaAnswer})는
 * 조건이 답변 식별자 하나뿐이라 <b>어느 문의의 답변인지 대조하지 않았다</b> — 다른 문의의 답변
 * 번호를 넣으면 그것이 지워졌다. 여기서는 부모까지 함께 대조하고, 어긋나면 이 예외다.
 */
public class InquiryAnswerNotFoundException extends BusinessException {

    public InquiryAnswerNotFoundException(Long inquiryId, Long answerId) {
        super(ErrorCode.INQUIRY_ANSWER_NOT_FOUND,
                "그 문의에 달린 답변이 아닙니다: inquiryId=" + inquiryId + ", answerId=" + answerId);
    }
}
