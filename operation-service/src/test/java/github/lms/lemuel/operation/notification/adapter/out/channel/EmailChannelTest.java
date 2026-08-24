package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 살아 있는 SMTP 서버 없이도 고정되는 이메일 채널의 두 계약: 자격증명이 <b>둘 다</b> 있기 전까지
 * 비활성이어야 하고(반쯤 설정돼 매 발송을 실패하는 채널은 그냥 꺼진 채널보다 나쁘다),
 * 전송 실패는 던져진 오류로 드러나야 디스패처가 셀 수 있다.
 */
class EmailChannelTest {

    private static EmailChannel channel(String username, String password) {
        return new EmailChannel("127.0.0.1", 1, username, password, "no-reply@lemuel.co.kr");
    }

    private static Notification notification() {
        return new Notification(NotificationType.GENERIC, "seller@lemuel.co.kr", "정산 확정", "본문", null);
    }

    @Test
    @DisplayName("활성이 되려면 자격증명 둘 다 필요하다")
    void bothCredentialsAreRequiredBeforeTheChannelCountsAsEnabled() {
        assertFalse(channel("", "").isEnabled(), "자격증명 없음");
        assertFalse(channel("u", "").isEnabled(), "username 만으로는 부족");
        assertFalse(channel("", "p").isEnabled(), "password 만으로는 부족");
        assertFalse(channel(null, null).isEnabled(), "null 도 미설정으로 취급");
        assertFalse(channel("  ", "  ").isEnabled(), "공백은 미설정으로 취급");
        assertTrue(channel("u", "p").isEnabled());
        assertEquals("email", channel("", "").name());
    }

    @Test
    @DisplayName("닿지 않는 SMTP 호스트는 성공 보고 대신 전송을 실패시킨다")
    void unreachableSmtpHostFailsTheSend() {
        // 루프백 1번 포트는 즉시 거부하므로, 메시지 조립 전 경로를 모두 태운 뒤 전송 실패에 도달한다
        // — 실제 MTA 없이.
        assertThrows(Exception.class, () -> channel("u", "p").send(notification()));
    }

    @Test
    @DisplayName("형식이 깨진 수신자 주소는 무엇을 보내기 전에 거부된다")
    void malformedRecipientAddressIsRejectedBeforeAnythingIsSent() {
        Notification broken = new Notification(NotificationType.GENERIC, "not an address", "s", "b", null);

        assertThrows(Exception.class, () -> channel("u", "p").send(broken));
    }
}
