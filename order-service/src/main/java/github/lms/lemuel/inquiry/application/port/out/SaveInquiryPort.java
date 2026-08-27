package github.lms.lemuel.inquiry.application.port.out;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;

/** 문의 저장 아웃바운드 포트. */
public interface SaveInquiryPort {

    /**
     * 새 문의를 저장하고 식별자가 채워진 모습을 돌려준다.
     *
     * <p>식별자는 DB 가 정한다. 레거시는 {@code (SELECT NVL(MAX(ID)+1, 1) FROM TBL_PRODUCT_ONE)}
     * 로 다음 번호를 <b>읽어서</b> 넣었다 — 두 요청이 같은 순간에 읽으면 같은 번호를 쓴다.
     * 질문과 답변을 잇는 {@code ID_NUM} 도 그 값의 음수라, 번호가 겹치면 남의 문의에 답변이 붙는다.
     */
    Inquiry save(Inquiry inquiry);

    /** 제목·본문·공개 여부만 갱신한다. 종류·대상·작성자는 건드리지 않는다. */
    Inquiry update(Inquiry inquiry);

    /** 문의를 지운다. 달린 답변도 함께 사라진다. */
    void delete(Long inquiryId);

    /** 답변을 붙이고 식별자가 채워진 모습을 돌려준다. */
    InquiryAnswer addAnswer(Long inquiryId, InquiryAnswer answer);

    /**
     * 답변 하나를 지운다.
     *
     * @return 실제로 지웠으면 true. 그 문의의 답변이 아니었으면 false
     */
    boolean deleteAnswer(Long inquiryId, Long answerId);
}
