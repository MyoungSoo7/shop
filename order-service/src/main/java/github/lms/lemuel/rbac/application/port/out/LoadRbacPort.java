package github.lms.lemuel.rbac.application.port.out;

import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;

import java.util.List;
import java.util.Optional;

public interface LoadRbacPort {

    List<Role> findAllRoles();

    Optional<Role> findRoleById(Long id);

    boolean existsRoleByCode(String code);

    List<Permission> findAllPermissions();

    List<Permission> findPermissionsByIds(List<Long> ids);
}
