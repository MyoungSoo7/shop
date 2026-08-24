package github.lms.lemuel.point.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPointUsageLimitPolicyRepository
        extends JpaRepository<PointUsageLimitPolicyJpaEntity, Short> {
}
