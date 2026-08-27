package github.lms.lemuel.inquiry.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 답변이 달린 뒤의 수정·철회 시도.
 *
 * <p>레거시의 {@code updateOneToOneQna} 에는 이 검사가 아예 없었다. 답변자가 본문을 읽고 답을 단
 * 뒤에 질문자가 본문을 바꾸면, 남는 것은 <b>서로 맞지 않는 질문과 답</b> 한 쌍이다. 답변은 그대로
 * 있으니 목록은 "답변 완료"라 말하고, 상세를 열면 엉뚱한 답이 붙어 있다.
 */
public class InquiryAlreadyAnsweredException extends BusinessException {

    public InquiryAlreadyAnsweredException(String message) {
        super(ErrorCode.INQUIRY_ALREADY_ANSWERED, message);
    }
}
