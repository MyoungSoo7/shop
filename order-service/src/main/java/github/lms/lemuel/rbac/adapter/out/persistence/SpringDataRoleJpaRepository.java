package github.lms.lemuel.rbac.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringDataRoleJpaRepository extends JpaRepository<RoleJpaEntity, Long> {

    @Query("SELECT DISTINCT r FROM RoleJpaEntity r LEFT JOIN FETCH r.permissions")
    List<RoleJpaEntity> findAllWithPermissions();

    boolean existsByCode(String code);
}
