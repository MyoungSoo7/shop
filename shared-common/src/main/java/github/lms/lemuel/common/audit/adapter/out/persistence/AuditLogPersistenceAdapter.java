package github.lms.lemuel.common.audit.adapter.out.persistence;

import github.lms.lemuel.common.audit.application.port.out.SaveAuditLogPort;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.audit.domain.AuditLog;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogPersistenceAdapter implements SaveAuditLogPort {

    private final SpringDataAuditLogJpaRepository repository;

    public AuditLogPersistenceAdapter(SpringDataAuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuditLog save(AuditLog log) {
        AuditLogJpaEntity entity = toEntity(log);
        AuditLogJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    private AuditLogJpaEntity toEntity(AuditLog log) {
        AuditLogJpaEntity e = new AuditLogJpaEntity();
        e.setId(log.getId());
        e.setActorId(log.getActorId());
        e.setActorEmail(log.getActorEmail());
        e.setAction(log.getAction().name());
        e.setResourceType(log.getResourceType());
        e.setResourceId(log.getResourceId());
        e.setDetailJson(log.getDetailJson());
        e.setIpAddress(log.getIpAddress());
        e.setUserAgent(log.getUserAgent());
        e.setCreatedAt(log.getCreatedAt());
        return e;
    }

    private AuditLog toDomain(AuditLogJpaEntity e) {
        AuditLog log = new AuditLog();
        log.setId(e.getId());
        log.setActorId(e.getActorId());
        log.setActorEmail(e.getActorEmail());
        log.setAction(AuditAction.valueOf(e.getAction()));
        log.setResourceType(e.getResourceType());
        log.setResourceId(e.getResourceId());
        log.setDetailJson(e.getDetailJson());
        log.setIpAddress(e.getIpAddress());
        log.setUserAgent(e.getUserAgent());
        log.setCreatedAt(e.getCreatedAt());
        return log;
    }
}
