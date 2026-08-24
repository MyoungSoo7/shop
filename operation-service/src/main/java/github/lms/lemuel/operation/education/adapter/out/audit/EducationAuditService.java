package github.lms.lemuel.operation.education.adapter.out.audit;

import github.lms.lemuel.operation.education.application.port.out.EducationAuditPort;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class EducationAuditService implements EducationAuditPort {
    private final EducationAuditRepository repository;
    public EducationAuditService(EducationAuditRepository repository) { this.repository = repository; }
    @Override
    public void record(String action, String resourceType, UUID resourceId, String actor, String detail) {
        repository.save(new EducationAuditJpaEntity(action, resourceType, resourceId, actor, detail));
    }
}
