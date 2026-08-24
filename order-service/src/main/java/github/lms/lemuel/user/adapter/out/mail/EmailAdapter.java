package github.lms.lemuel.user.adapter.out.mail;

import github.lms.lemuel.user.application.port.out.SendEmailPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailAdapter implements SendEmailPort {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Value("${app.password-reset.base-url}")
    private String baseUrl;

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String subject = "[Lemuel] 비밀번호 재설정 안내";
        String resetLink = baseUrl + "/reset-password?token=" + resetToken;

        String content = buildPasswordResetEmailContent(resetLink);

        sendEmail(toEmail, subject, content);
    }

    @Override
    public void sendEmail(String to, String subject, String content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true); // HTML 포맷

            mailSender.send(message);
            log.info("이메일 발송 성공: to={}, subject={}", to, subject);
        } catch (MessagingException e) {
            log.error("이메일 발송 실패: to={}, subject={}", to, subject, e);
            throw new RuntimeException("이메일 발송에 실패했습니다.", e);
        }
    }

    private String buildPasswordResetEmailContent(String resetLink) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background-color: #3b82f6; color: white; padding: 20px; text-align: center; }
                        .content { background-color: #f9fafb; padding: 30px; }
                        .button { display: inline-block; padding: 12px 24px; background-color: #3b82f6;
                                  color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; }
                        .footer { text-align: center; padding: 20px; color: #6b7280; font-size: 12px; }
                        .warning { color: #dc2626; font-weight: bold; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>Lemuel 비밀번호 재설정</h1>
                        </div>
                        <div class="content">
                            <p>안녕하세요,</p>
                            <p>비밀번호 재설정을 요청하셨습니다. 아래 버튼을 클릭하여 새로운 비밀번호를 설정해주세요.</p>
                            <div style="text-align: center;">
                                <a href="%s" class="button">비밀번호 재설정하기</a>
                            </div>
                            <p>또는 아래 링크를 복사하여 브라우저에 붙여넣으세요:</p>
                            <p style="word-break: break-all; background-color: #e5e7eb; padding: 10px; border-radius: 5px;">
                                %s
                            </p>
                            <p class="warning">⚠️ 이 링크는 30분 동안만 유효합니다.</p>
                            <p>본인이 요청하지 않은 경우, 이 이메일을 무시하시기 바랍니다.</p>
                        </div>
                        <div class="footer">
                            <p>© 2024 Lemuel. All rights reserved.</p>
                            <p>이 이메일은 시스템에서 자동으로 발송되었습니다. 회신하지 마세요.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(resetLink, resetLink);
    }
}
