package github.lms.lemuel.inquiry.domain;

import github.lms.lemuel.inquiry.domain.exception.InquiryInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 문의에 달린 답변 하나.
 *
 * <p>레거시는 답변을 <b>질문과 같은 테이블의 형제 행</b>으로 넣었다. 질문 행은 {@code ID_NUM} 이
 * 음수, 답변 행은 양수이고, 이어 주는 것은 {@code ABS(ID_NUM) = 질문ID AND ID_DEPTH != 0} 이라는
 * 관례뿐이었다. 관례라서 DB 가 지켜 주지 않는다 — 답변만 남고 질문이 지워져도, {@code ID_DEPTH} 를
 * 안 채운 행이 질문으로 둔갑해도 아무 제약에 걸리지 않는다. 여기서는 진짜 자식 관계로 두고,
 * 부모가 사라지면 답변도 함께 사라진다.
 *
 * @param id         답변 식별자. 아직 저장 전이면 {@code null}
 * @param answeredBy 답변을 단 관리자
 * @param content    답변 본문
 * @param answeredAt 답변 시각
 */
public record InquiryAnswer(Long id, Long answeredBy, String content, LocalDateTime answeredAt) {

    /** 답변 본문 상한. 질문({@link Inquiry#CONTENT_MAX})과 같은 값을 쓴다. */
    public static final int CONTENT_MAX = Inquiry.CONTENT_MAX;

    public InquiryAnswer {
        Objects.requireNonNull(answeredBy, "answeredBy");
        Objects.requireNonNull(answeredAt, "answeredAt");
        content = requireContent(content);
    }

    /** 아직 저장되지 않은 새 답변. */
    public static InquiryAnswer of(Long answeredBy, String content, LocalDateTime answeredAt) {
        return new InquiryAnswer(null, answeredBy, content, answeredAt);
    }

    private static String requireContent(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            throw new InquiryInvariantViolationException("답변 내용을 입력해 주세요.");
        }
        if (trimmed.length() > CONTENT_MAX) {
            throw new InquiryInvariantViolationException(
                    "답변은 " + CONTENT_MAX + "자까지 쓸 수 있습니다. 현재 " + trimmed.length() + "자입니다.");
        }
        return trimmed;
    }
}
