package github.lms.lemuel.user.application.port.out;

/**
 * 사용자 존재 여부 확인 Outbound Port
 */
public interface ExistsUserPort {

    boolean existsByEmail(String email);
}
