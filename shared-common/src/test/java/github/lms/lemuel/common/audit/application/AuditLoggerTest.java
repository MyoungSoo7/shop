package github.lms.lemuel.common.audit.application;

import github.lms.lemuel.common.audit.application.port.out.SaveAuditLogPort;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.audit.domain.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLoggerTest {

    @Mock SaveAuditLogPort saveAuditLogPort;

    AuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AuditLogger(saveAuditLogPort);
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    @DisplayName("AuditContext 에 설정된 actor 정보로 기록")
    void recordsWithContext() {
        AuditContext.set(new AuditContext.AuditActor(42L, "admin@x.com", "10.0.0.1", "curl/7"));
        when(saveAuditLogPort.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        logger.record(AuditAction.SETTLEMENT_CONFIRMED, "Settlement", "100", "{\"amount\":50000}");

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(saveAuditLogPort).save(cap.capture());
        AuditLog saved = cap.getValue();
        assertThat(saved.getAction()).isEqualTo(AuditAction.SETTLEMENT_CONFIRMED);
        assertThat(saved.getResourceType()).isEqualTo("Settlement");
        assertThat(saved.getResourceId()).isEqualTo("100");
        assertThat(saved.getActorId()).isEqualTo(42L);
        assertThat(saved.getActorEmail()).isEqualTo("admin@x.com");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("포트가 예외를 던져도 상위로 전파되지 않음 (감사 실패는 비즈니스 실패와 분리)")
    void swallowsSaveErrors() {
        when(saveAuditLogPort.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("DB down"));

        // 예외가 던져지지 않아야 함
        logger.record(AuditAction.LOGIN_FAILED, "User", "x", null);
    }

    @Test
    @DisplayName("context 미설정 시 system actor 로 기록")
    void fallsBackToSystemActor() {
        AuditContext.clear();
        when(saveAuditLogPort.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        logger.record(AuditAction.REFUND_COMPLETED, "Refund", "5", null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(saveAuditLogPort).save(cap.capture());
        assertThat(cap.getValue().getActorId()).isNull();
        assertThat(cap.getValue().getActorEmail()).isNull();
    }

    @Test
    @DisplayName("action null 이면 IllegalArgumentException (record 시 도메인 검증에서 걸림 → swallow 되어 save 호출 안 됨)")
    void rejectsNullAction() {
        logger.record(null, "X", "1", null);
        verifyNoInteractions(saveAuditLogPort);
    }
}
