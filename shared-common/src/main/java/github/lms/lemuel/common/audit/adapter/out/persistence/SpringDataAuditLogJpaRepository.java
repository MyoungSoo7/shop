package github.lms.lemuel.common.audit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, Long> {
}
