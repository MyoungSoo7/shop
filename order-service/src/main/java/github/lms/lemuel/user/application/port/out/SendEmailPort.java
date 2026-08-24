package github.lms.lemuel.user.application.port.out;

public interface SendEmailPort {

    void sendPasswordResetEmail(String toEmail, String resetToken);

    void sendEmail(String to, String subject, String content);
}
