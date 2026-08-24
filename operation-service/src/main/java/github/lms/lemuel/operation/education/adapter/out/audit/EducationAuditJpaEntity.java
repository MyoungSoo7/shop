package github.lms.lemuel.operation.education.adapter.out.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "education_audit_logs", schema = "education")
public class EducationAuditJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String action;
    private String resourceType;
    private UUID resourceId;
    private String actor;
    private String detail;
    private Instant createdAt;
    protected EducationAuditJpaEntity() { }
    public EducationAuditJpaEntity(String action, String resourceType, UUID resourceId, String actor, String detail) {
        this.action = action; this.resourceType = resourceType; this.resourceId = resourceId;
        this.actor = actor; this.detail = detail; this.createdAt = Instant.now();
    }
}
