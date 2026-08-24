package github.lms.lemuel.menu.adapter.out.rbac;

import github.lms.lemuel.menu.application.port.out.LoadPermissionCodesPort;
import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 메뉴가 필요한 "역할이 가진 권한 코드"를 rbac 모듈에서 읽어 온다.
 *
 * <p>역할은 3~수십 건 규모라 전체 조회 후 코드로 고르는 편이 전용 쿼리를 추가하는 것보다 싸다.
 */
@Component
@RequiredArgsConstructor
public class RolePermissionCodesAdapter implements LoadPermissionCodesPort {

    private final LoadRbacPort loadRbacPort;

    @Override
    public Set<String> findByRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Set.of();
        }
        String normalized = roleCode.trim().toUpperCase(Locale.ROOT);
        return loadRbacPort.findAllRoles().stream()
                .filter(role -> normalized.equals(role.getCode()))
                .findFirst()
                .map(Role::getPermissions)
                .orElse(java.util.List.of())
                .stream()
                .map(Permission::getCode)
                .collect(Collectors.toUnmodifiableSet());
    }
}
