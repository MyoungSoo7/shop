package github.lms.lemuel.common.audit.application;

import github.lms.lemuel.common.audit.application.port.out.SaveAuditLogPort;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.audit.domain.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit Log 직접 기록용 퍼사드.
 *
 * <p>본 클래스는 서비스가 직접 호출할 수 있도록 제공되며, AOP(@Auditable) 경로가 아닌
 * 부분 성공·실패 같은 복잡 경로에서도 감사 추적을 남기기 위해 사용한다.
 *
 * <p>REQUIRES_NEW — 비즈니스 트랜잭션이 롤백되어도 감사 기록은 남게 한다.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final SaveAuditLogPort saveAuditLogPort;

    public AuditLogger(SaveAuditLogPort saveAuditLogPort) {
        this.saveAuditLogPort = saveAuditLogPort;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String resourceType, String resourceId, String detailJson) {
        try {
            AuditContext.AuditActor actor = AuditContext.get();
            AuditLog entry = AuditLog.of(action, resourceType, resourceId, detailJson,
                    actor.actorId(), actor.actorEmail(), actor.ipAddress(), actor.userAgent());
            saveAuditLogPort.save(entry);
        } catch (Exception e) {
            // 감사 기록 실패가 비즈니스 트랜잭션을 깨뜨리면 안 됨.
            log.error("Audit log save failed. action={}, resourceId={}", action, resourceId, e);
        }
    }
}
