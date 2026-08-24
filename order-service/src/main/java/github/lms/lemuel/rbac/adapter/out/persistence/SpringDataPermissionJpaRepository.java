package github.lms.lemuel.rbac.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPermissionJpaRepository extends JpaRepository<PermissionJpaEntity, Long> {

    List<PermissionJpaEntity> findAllByIdIn(List<Long> ids);
}
