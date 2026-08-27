package github.lms.lemuel.inquiry.application.port.out;

import github.lms.lemuel.inquiry.domain.Inquiry;

/**
 * 문의 알림 아웃바운드 포트.
 *
 * <p><b>실패해도 문의 등록은 성공이다.</b> 레거시는 문의를 넣은 뒤 관리자 목록을 돌며 알림톡을
 * 보냈는데, 그 루프가 같은 {@code try} 안에 있었다. 발송이 한 번 실패하면 사용자에게는
 * {@code "8888"}("등록 실패")이 나갔고, 실제로는 행이 들어가 있었다. 사용자는 실패한 줄 알고
 * 같은 문의를 다시 남겼다.
 *
 * <p>그래서 구현체는 <b>예외를 밖으로 던지지 않는다</b>. 보내지 못한 사실은 로그로 남기고,
 * 호출자는 그 결과를 응답에 섞지 않는다.
 */
public interface NotifyInquiryPort {

    /** 새 문의가 등록됐음을 담당자에게 알린다. */
    void notifyAsked(Inquiry inquiry);

    /** 답변이 달렸음을 작성자에게 알린다. */
    void notifyAnswered(Inquiry inquiry);
}
