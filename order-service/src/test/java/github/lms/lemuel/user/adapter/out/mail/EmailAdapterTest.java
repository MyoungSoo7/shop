package github.lms.lemuel.user.adapter.out.mail;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이메일 어댑터 단위 테스트 — MimeMessageHelper 로 HTML 메일을 조립·발송하는 happy path 와 발송 실패 처리.
 */
@ExtendWith(MockitoExtension.class)
class EmailAdapterTest {

    @Mock JavaMailSender mailSender;
    EmailAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmailAdapter(mailSender);
        ReflectionTestUtils.setField(adapter, "fromEmail", "noreply@lemuel.com");
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://lemuel.com");
    }

    @Test
    @DisplayName("sendPasswordResetEmail: 재설정 링크를 담은 HTML 메일을 발송한다")
    void sendPasswordResetEmail() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));

        adapter.sendPasswordResetEmail("user@b.com", "tok-123");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmail: 임의 제목/본문 메일 발송")
    void sendEmail() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));

        adapter.sendEmail("user@b.com", "제목", "<p>본문</p>");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmail: 전송 실패 시 RuntimeException 으로 감싸 전파")
    void sendEmail_failureWrapped() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        // MailSendException(RuntimeException) 은 그대로 전파된다 (catch 는 MessagingException 만)
        assertThatThrownBy(() -> adapter.sendEmail("user@b.com", "제목", "본문"))
                .isInstanceOf(RuntimeException.class);
    }
}
