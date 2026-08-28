package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 살아 있는 SMTP 서버 없이도 고정되는 이메일 채널의 두 계약: 자격증명이 <b>둘 다</b> 있기 전까지
 * 비활성이어야 하고(반쯤 설정돼 매 발송을 실패하는 채널은 그냥 꺼진 채널보다 나쁘다),
 * 전송 실패는 던져진 오류로 드러나야 디스패처가 셀 수 있다.
 */
class EmailChannelTest {

    private static EmailChannel channel(String username, String password) {
        return new EmailChannel("127.0.0.1", 1, username, password, "no-reply@lemuel.co.kr", 3000);
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
    @DisplayName("연결·읽기·쓰기 타임아웃이 모두 유한한 값으로 걸려 있다")
    void everyBlockingPhaseCarriesAFiniteTimeout() {
        // 이 세 키가 없으면 jakarta.mail 은 무한 대기가 기본이다. 죽은 SMTP 서버 하나에
        // 연결이 상한 없이 쌓이는데, 증상은 "알림이 느리다"로만 보여 원인에 닿기 어렵다.
        // 디스패처의 채널별 상한은 호출자만 풀어줄 뿐 남은 시도를 끝내지 못하므로(인터럽트는
        // 블로킹 소켓 읽기를 깨우지 못한다) 시도를 실제로 끝내는 마감선은 여기뿐이다.
        Properties props = channel("u", "p").smtpProperties();

        for (String key : List.of("mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout")) {
            String value = props.getProperty(key);
            assertNotNull(value, key + " 미설정 = 무한 대기");
            assertTrue(Integer.parseInt(value) > 0, key + " 은 양수여야 한다");
        }
        assertEquals("3000", props.getProperty("mail.smtp.timeout"), "설정값이 그대로 반영돼야 한다");
    }

    @Test
    @DisplayName("형식이 깨진 수신자 주소는 무엇을 보내기 전에 거부된다")
    void malformedRecipientAddressIsRejectedBeforeAnythingIsSent() {
        Notification broken = new Notification(NotificationType.GENERIC, "not an address", "s", "b", null);

        assertThrows(Exception.class, () -> channel("u", "p").send(broken));
    }
}
