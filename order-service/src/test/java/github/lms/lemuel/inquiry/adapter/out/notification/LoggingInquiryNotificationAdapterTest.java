package github.lms.lemuel.inquiry.adapter.out.notification;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 알림 어댑터의 계약은 하나다 — <b>무슨 일이 있어도 예외를 밖으로 내지 않는다.</b>
 *
 * <p>레거시는 이 계약이 없어서 알림톡 발송 실패가 그대로 "문의 등록 실패"로 사용자에게 나갔다.
 * 행은 이미 들어가 있었고, 사용자는 같은 문의를 다시 남겼다.
 */
@DisplayName("문의 알림 어댑터")
class LoggingInquiryNotificationAdapterTest {

    private final LoggingInquiryNotificationAdapter adapter = new LoggingInquiryNotificationAdapter();

    private static Inquiry inquiry() {
        return Inquiry.ask(7L, InquiryType.PRODUCT, 100L, null, "제목", "본문", false,
                LocalDateTime.of(2026, 8, 27, 10, 0));
    }

    @Test
    @DisplayName("정상 문의는 조용히 통과한다")
    void notifiesQuietly() {
        assertThatCode(() -> adapter.notifyAsked(inquiry())).doesNotThrowAnyException();
        assertThatCode(() -> adapter.notifyAnswered(inquiry())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 이 들어와도 호출자에게 예외가 올라가지 않는다 — 실패 로그가 같은 참조를 다시 만지면 그 자리에서 다시 터진다")
    void nullDoesNotEscape() {
        assertThatCode(() -> adapter.notifyAsked(null)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.notifyAnswered(null)).doesNotThrowAnyException();
    }
}
