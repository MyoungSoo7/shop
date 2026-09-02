package github.lms.lemuel.expirynotice.domain;

import github.lms.lemuel.expirynotice.domain.exception.ExpiryNoticeInvariantViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 곧 만료되는 한 건 — 세 애그리것(포인트 로트·기프트카드·선물 수령권)을 통보 관점에서 같은 모양으로 본다.
 *
 * @param subject     무엇인가
 * @param subjectId   그 표에서의 식별자
 * @param userId      통보를 받을 회원. 선물 수령권은 <b>보낸 사람</b>이 들어온다({@link ExpirySubject#GIFT_CLAIM} 참고)
 * @param amount      남은 금액. 선물 수령권처럼 금액이 없는 대상은 {@code null}
 * @param expiresAt   만료 예정 시각
 * @param contactHint 회원 식별자로 닿을 수 없는 대상의 발송 힌트(수령자 전화번호). 없으면 {@code null}
 */
public record ExpiringItem(ExpirySubject subject,
                           long subjectId,
                           Long userId,
                           BigDecimal amount,
                           OffsetDateTime expiresAt,
                           String contactHint) {

    public ExpiringItem {
        if (subject == null) {
            throw new ExpiryNoticeInvariantViolationException("subject 는 필수다");
        }
        if (expiresAt == null) {
            throw new ExpiryNoticeInvariantViolationException(
                    "expiresAt 은 필수다 — 만료 시각을 모르면 예고할 것도 없다");
        }
    }
}
