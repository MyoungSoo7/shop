package github.lms.lemuel.inquiry.domain;

import github.lms.lemuel.inquiry.domain.exception.InquiryAlreadyAnsweredException;
import github.lms.lemuel.inquiry.domain.exception.InquiryInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 문의 한 건 — 질문과 그에 달린 답변들.
 *
 * <p>레거시에서 이식하며 바꾼 것은 넷이다.
 *
 * <ol>
 *   <li><b>세 테이블을 하나로.</b> 상품문의·1:1문의·상품요청이 각각의 테이블이었고, 목록·상세·
 *       답변·삭제 쿼리가 세 벌씩 있었다. 같은 모양이라 한쪽만 고쳐진 곳이 실제로 생겼다 —
 *       답변 상태 판정 서브쿼리는 한 테이블에만 들어가 있다. 종류는 {@link InquiryType} 칼럼이다.
 *   <li><b>답변 상태를 저장하지 않는다.</b> 이유는 {@link InquiryStatus} 에 적었다.
 *   <li><b>답변이 달린 뒤에는 못 고친다.</b> 레거시 {@code updateOneToOneQna} 에는 이 검사가
 *       없어, 답을 받은 뒤 질문을 바꾸면 서로 맞지 않는 한 쌍이 남았다.
 *   <li><b>비밀글이 있다.</b> 상품 문의는 상품 페이지에 공개로 걸리는데 레거시에는 가리는 수단이
 *       없었다. 주문·1:1 문의는 애초에 공개 목록이 없으므로 {@link #publiclyListed()} 가 false 다.
 * </ol>
 *
 * @param id        문의 식별자. 아직 저장 전이면 {@code null}
 * @param userId    작성자
 * @param type      종류. 무엇을 함께 요구하는지가 여기서 갈린다
 * @param productId 상품 문의라면 대상 상품. 아니면 {@code null}
 * @param orderId   주문·배송 문의라면 대상 주문. 아니면 {@code null}
 * @param subject   제목
 * @param content   본문
 * @param secret    비밀글 여부. 상품 문의에서만 뜻이 있다
 * @param askedAt   작성 시각
 * @param answers   답변들. 오래된 순
 */
public record Inquiry(
        Long id,
        Long userId,
        InquiryType type,
        Long productId,
        Long orderId,
        String subject,
        String content,
        boolean secret,
        LocalDateTime askedAt,
        List<InquiryAnswer> answers) {

    public static final int SUBJECT_MAX = 100;
    public static final int CONTENT_MAX = 2000;

    /** 비밀글이거나 남의 문의일 때 목록에 대신 보여 줄 제목. */
    public static final String MASKED_SUBJECT = "비밀글입니다.";

    public Inquiry {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(askedAt, "askedAt");
        subject = requireText(subject, "제목", SUBJECT_MAX);
        content = requireText(content, "내용", CONTENT_MAX);
        requireTarget(type, productId, orderId);
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
    }

    /** 아직 저장되지 않은 새 문의. */
    public static Inquiry ask(Long userId, InquiryType type, Long productId, Long orderId,
                              String subject, String content, boolean secret, LocalDateTime askedAt) {
        return new Inquiry(null, userId, type, productId, orderId, subject, content, secret, askedAt, List.of());
    }

    /**
     * 답변이 하나라도 있으면 ANSWERED.
     *
     * <p>답변 목록에서 매번 계산하므로, 답변을 지우면 상태도 <b>같은 순간에</b> WAITING 으로 돌아온다.
     */
    public InquiryStatus status() {
        return answers.isEmpty() ? InquiryStatus.WAITING : InquiryStatus.ANSWERED;
    }

    public boolean isAnswered() {
        return !answers.isEmpty();
    }

    public boolean isOwnedBy(Long viewerId) {
        return Objects.equals(userId, viewerId);
    }

    /**
     * 상품 페이지의 공개 문의 목록에 본문까지 그대로 걸리는가.
     *
     * <p>공개 대상은 상품 문의뿐이고, 그중 비밀글은 빠진다. 목록에서 아예 감추는 대신 제목만
     * {@link #MASKED_SUBJECT} 로 바꿔 자리는 남긴다 — 감춰 버리면 "내 질문이 등록됐나"를 작성자가
     * 확인할 방법이 없다.
     */
    public boolean publiclyListed() {
        return type == InquiryType.PRODUCT && !secret;
    }

    /** 이 뷰어에게 본문을 보여도 되는가. 관리자와 작성자 본인, 그리고 공개 상품 문의만이다. */
    public boolean isReadableBy(Long viewerId, boolean admin) {
        return admin || isOwnedBy(viewerId) || publiclyListed();
    }

    /**
     * 답변 전에만 통과한다.
     *
     * @throws InquiryAlreadyAnsweredException 답변이 하나라도 달린 뒤
     */
    public void requireEditable() {
        if (isAnswered()) {
            throw new InquiryAlreadyAnsweredException(
                    "답변이 달린 문의는 수정하거나 삭제할 수 없습니다. 새 문의를 남겨 주세요.");
        }
    }

    /** 제목·본문·공개 여부만 바꾼 사본. 종류와 대상은 한 번 정해지면 바뀌지 않는다. */
    public Inquiry edit(String newSubject, String newContent, boolean newSecret) {
        requireEditable();
        return new Inquiry(id, userId, type, productId, orderId,
                newSubject, newContent, newSecret, askedAt, answers);
    }

    /** 답변 하나를 붙인 사본. 붙이는 순간 {@link #status()} 가 ANSWERED 로 바뀐다. */
    public Inquiry withAnswer(InquiryAnswer answer) {
        Objects.requireNonNull(answer, "answer");
        List<InquiryAnswer> appended = new ArrayList<>(answers);
        appended.add(answer);
        return new Inquiry(id, userId, type, productId, orderId,
                subject, content, secret, askedAt, appended);
    }

    private static String requireText(String raw, String label, int max) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            throw new InquiryInvariantViolationException("문의 " + label + "을(를) 입력해 주세요.");
        }
        if (trimmed.length() > max) {
            throw new InquiryInvariantViolationException(
                    "문의 " + label + "은(는) " + max + "자까지 쓸 수 있습니다. 현재 " + trimmed.length() + "자입니다.");
        }
        return trimmed;
    }

    /**
     * 종류가 요구하는 대상이 실제로 붙어 있는지.
     *
     * <p>레거시는 {@code PRID} 가 있으면 넣고 없으면 안 넣는 식이라, 상품 없는 "상품 문의"가
     * 그대로 저장됐다. 답변자는 무엇을 보고 답해야 할지 알 수 없고, 상품 페이지 목록에도 걸리지
     * 않아 사실상 사라진 문의가 된다.
     */
    private static void requireTarget(InquiryType type, Long productId, Long orderId) {
        if (type.requiresProduct() && productId == null) {
            throw new InquiryInvariantViolationException(type.label() + "에는 대상 상품이 있어야 합니다.");
        }
        if (type.requiresOrder() && orderId == null) {
            throw new InquiryInvariantViolationException(type.label() + "에는 대상 주문이 있어야 합니다.");
        }
    }
}
