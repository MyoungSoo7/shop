package github.lms.lemuel.common.audit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogTest {

    @Test
    @DisplayName("of() 는 감사 주체와 리소스 정보를 보존하고 생성 시각을 채운다")
    void ofCreatesAuditLog() {
        AuditLog log = AuditLog.of(
                AuditAction.USER_ROLE_CHANGED,
                "User",
                "42",
                "{\"role\":\"ADMIN\"}",
                7L,
                "admin@example.com",
                "127.0.0.1",
                "JUnit"
        );

        assertThat(log.getAction()).isEqualTo(AuditAction.USER_ROLE_CHANGED);
        assertThat(log.getResourceType()).isEqualTo("User");
        assertThat(log.getResourceId()).isEqualTo("42");
        assertThat(log.getDetailJson()).isEqualTo("{\"role\":\"ADMIN\"}");
        assertThat(log.getActorId()).isEqualTo(7L);
        assertThat(log.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(log.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(log.getUserAgent()).isEqualTo("JUnit");
        assertThat(log.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("of() 는 action 누락을 거부한다")
    void ofRequiresAction() {
        assertThatThrownBy(() -> AuditLog.of(null, "User", "42", "{}", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("action required");
    }

    @Test
    @DisplayName("setter 는 영속화 계층에서 재구성한 값을 반영한다")
    void settersUpdateFields() {
        AuditLog log = new AuditLog();
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 8, 12, 0);

        log.setId(1L);
        log.setActorId(2L);
        log.setActorEmail("user@example.com");
        log.setAction(AuditAction.LOGIN_SUCCESS);
        log.setResourceType("Session");
        log.setResourceId("abc");
        log.setDetailJson("{}");
        log.setIpAddress("10.0.0.1");
        log.setUserAgent("agent");
        log.setCreatedAt(createdAt);

        assertThat(log.getId()).isEqualTo(1L);
        assertThat(log.getActorId()).isEqualTo(2L);
        assertThat(log.getActorEmail()).isEqualTo("user@example.com");
        assertThat(log.getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
        assertThat(log.getResourceType()).isEqualTo("Session");
        assertThat(log.getResourceId()).isEqualTo("abc");
        assertThat(log.getDetailJson()).isEqualTo("{}");
        assertThat(log.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(log.getUserAgent()).isEqualTo("agent");
        assertThat(log.getCreatedAt()).isEqualTo(createdAt);
    }
}
