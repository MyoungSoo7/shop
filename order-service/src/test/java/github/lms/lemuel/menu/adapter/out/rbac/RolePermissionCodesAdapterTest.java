package github.lms.lemuel.menu.adapter.out.rbac;

import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolePermissionCodesAdapterTest {

    @Mock LoadRbacPort loadRbacPort;
    @InjectMocks RolePermissionCodesAdapter adapter;

    private Role roleWith(String code, String... permissionCodes) {
        Role role = Role.of(1L, code, code, null, true, LocalDateTime.now());
        for (String permissionCode : permissionCodes) {
            role.getPermissions().add(Permission.of(1L, permissionCode, permissionCode, "CAT", null));
        }
        return role;
    }

    @Test @DisplayName("역할 코드로 권한 코드 집합을 돌려준다")
    void findsPermissions() {
        when(loadRbacPort.findAllRoles()).thenReturn(List.of(
                roleWith("ADMIN", "SYSTEM_MENU_MANAGE", "ORDER_READ"),
                roleWith("USER")));

        assertThat(adapter.findByRoleCode("ADMIN"))
                .containsExactlyInAnyOrder("SYSTEM_MENU_MANAGE", "ORDER_READ");
    }

    @Test @DisplayName("소문자 역할 코드도 대문자로 정규화해 찾는다")
    void normalizesRoleCode() {
        when(loadRbacPort.findAllRoles()).thenReturn(List.of(roleWith("MANAGER", "ORDER_READ")));

        assertThat(adapter.findByRoleCode(" manager ")).containsExactly("ORDER_READ");
    }

    @Test @DisplayName("모르는 역할이면 빈 집합")
    void unknownRole() {
        when(loadRbacPort.findAllRoles()).thenReturn(List.of(roleWith("ADMIN", "ORDER_READ")));

        assertThat(adapter.findByRoleCode("GHOST")).isEmpty();
    }

    @Test @DisplayName("역할 코드가 비어 있으면 조회하지 않는다")
    void blankRoleSkipsLookup() {
        assertThat(adapter.findByRoleCode(null)).isEmpty();
        assertThat(adapter.findByRoleCode("  ")).isEmpty();

        verify(loadRbacPort, never()).findAllRoles();
    }
}
