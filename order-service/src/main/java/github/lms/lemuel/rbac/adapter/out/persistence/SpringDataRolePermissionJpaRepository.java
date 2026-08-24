package github.lms.lemuel.rbac.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataRolePermissionJpaRepository extends JpaRepository<RolePermissionJpaEntity, RolePermissionId> {

    @Modifying
    @Query("DELETE FROM RolePermissionJpaEntity rp WHERE rp.id.roleId = :roleId")
    void deleteAllByRoleId(@Param("roleId") Long roleId);
}
