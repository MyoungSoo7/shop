package github.lms.lemuel.user.application.port.out;

public interface PasswordHashPort {
    String hash(String rawPassword);
    boolean matches(String rawPassword, String hashedPassword);
}
