package github.lms.lemuel.inquiry.application.port.in;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryType;

import java.util.List;

/**
 * 문의 인바운드 포트 — 상품 문의·주문 문의·1:1 문의의 단일 진입점.
 *
 * <p>이식 대상이던 레거시는 같은 일을 <b>세 벌</b>로 갖고 있었다({@code insertProductQna} /
 * {@code insertOneToOneQna} / 상품요청). 세 벌이 각각 목록·상세·수정·삭제를 갖고 있었고 서로
 * 조금씩 달랐다 — 예컨대 답변 상태 판정은 한쪽에만 있었다. 여기서는 하나로 두고,
 * 종류마다 달라지는 것은 {@link InquiryType} 이 요구하는 대상(상품·주문) 하나뿐이다.
 *
 * <p><b>결과 코드를 돌려주지 않는다.</b> 레거시는 {@code "0000"}/{@code "9999"}/{@code "8888"}
 * 세 문자열로 성공·실패·부분실패를 구분했고, 알림톡 발송 실패가 {@code "8888"} 로 나가면서
 * 사용자에게는 "등록 실패"로 보였다. 실제로는 문의 행이 들어가 있었고, 사용자는 같은 문의를 다시
 * 남겼다. 여기서는 <b>등록의 성패와 알림의 성패를 섞지 않는다</b> — 알림은 커밋 뒤 best-effort 다.
 */
public interface InquiryUseCase {

    /** 한 사용자가 자기 목록에서 한 번에 볼 수 있는 최대 건수. */
    int MAX_LIST_SIZE = 200;

    /** 상품 페이지의 공개 문의 목록에 한 번에 실리는 최대 건수. */
    int MAX_PRODUCT_LIST_SIZE = 100;

    /** 문의를 남긴다. 알림 실패는 이 호출의 성패에 영향을 주지 않는다. */
    Inquiry ask(AskCommand command);

    /**
     * 내 문의 목록. 최신순.
     *
     * @param type {@code null} 이면 종류를 가리지 않는다
     */
    List<Inquiry> listMine(Long userId, InquiryType type);

    /**
     * 상세.
     *
     * @param viewerId 요청자. 공개 상품 문의가 아니면 본인이어야 한다
     * @param admin    관리자·매니저인가. 그러면 전부 볼 수 있다
     */
    Inquiry get(Long inquiryId, Long viewerId, boolean admin);

    /**
     * 상품 페이지의 공개 문의 목록. 최신순.
     *
     * <p>비밀글도 <b>줄은 남는다</b> — 제목이 {@link Inquiry#MASKED_SUBJECT} 로, 본문이 빈 문자열로
     * 바뀐 채 온다. 작성자 본인과 관리자에게는 원문 그대로 온다.
     */
    List<Inquiry> listForProduct(Long productId, Long viewerId, boolean admin);

    /** 제목·본문·공개 여부 수정. 답변이 달린 뒤에는 거부한다. */
    Inquiry edit(Long inquiryId, Long requesterId, String subject, String content, boolean secret);

    /** 철회(삭제). 답변이 달린 뒤에는 거부한다. */
    void withdraw(Long inquiryId, Long requesterId);

    /** 답변 대기 중인 문의들. 관리자 화면용, 오래된 순(먼저 물어본 사람이 먼저다). */
    List<Inquiry> listWaiting();

    /** 답변을 단다. 관리자 전용. */
    Inquiry answer(Long inquiryId, Long answererId, String content);

    /**
     * 답변을 지운다. 관리자 전용.
     *
     * <p>지우는 순간 {@link Inquiry#status()} 는 다시 WAITING 이다. 레거시는 상태를 칼럼으로 들고
     * 있었고 이 경로가 그 칼럼을 되돌리지 않아, 답변이 사라진 뒤에도 목록은 "답변 완료"였다.
     */
    Inquiry deleteAnswer(Long inquiryId, Long answerId);

    /**
     * 문의 등록 요청.
     *
     * @param userId    작성자
     * @param type      종류
     * @param productId 상품 문의라면 대상 상품
     * @param orderId   주문·배송 문의라면 대상 주문
     * @param subject   제목
     * @param content   본문
     * @param secret    비밀글 여부(상품 문의에서만 뜻이 있다)
     */
    record AskCommand(Long userId,
                      InquiryType type,
                      Long productId,
                      Long orderId,
                      String subject,
                      String content,
                      boolean secret) {}
}
