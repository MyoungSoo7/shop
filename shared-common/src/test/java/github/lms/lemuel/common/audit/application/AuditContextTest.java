package github.lms.lemuel.common.audit.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditContextTest {

    @Test
    @DisplayName("set/get/clear 생명주기")
    void lifecycle() {
        AuditContext.AuditActor actor = new AuditContext.AuditActor(1L, "u@x.com", "1.2.3.4", "ua");
        AuditContext.set(actor);
        try {
            assertThat(AuditContext.get()).isEqualTo(actor);
        } finally {
            AuditContext.clear();
        }
        // clear 후 SYSTEM 액터 반환
        assertThat(AuditContext.get().actorId()).isNull();
        assertThat(AuditContext.get().actorEmail()).isNull();
    }

    @Test
    @DisplayName("미설정 시 system() fallback")
    void systemFallback() {
        AuditContext.clear();
        AuditContext.AuditActor actor = AuditContext.get();
        assertThat(actor.actorId()).isNull();
        assertThat(actor.actorEmail()).isNull();
        assertThat(actor.ipAddress()).isNull();
        assertThat(actor.userAgent()).isNull();
    }
}
