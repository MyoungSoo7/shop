package github.lms.lemuel.common.audit.application.port.out;

import github.lms.lemuel.common.audit.domain.AuditLog;

public interface SaveAuditLogPort {

    AuditLog save(AuditLog log);
}
