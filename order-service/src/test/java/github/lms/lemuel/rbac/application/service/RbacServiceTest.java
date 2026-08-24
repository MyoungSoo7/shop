package github.lms.lemuel.rbac.application.service;
import github.lms.lemuel.rbac.domain.exception.RoleInvariantViolationException;

import github.lms.lemuel.rbac.domain.exception.RoleInvariantViolationException;

import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.application.port.out.SaveRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RbacService — 역할/권한 조회 및 매핑 교체")
class RbacServiceTest {

    @Mock LoadRbacPort loadRbacPort;
    @Mock SaveRbacPort saveRbacPort;
    @InjectMocks RbacService service;

    @Test
    @DisplayName("getAllRoles — 위임")
    void getAllRoles() {
        when(loadRbacPort.findAllRoles()).thenReturn(List.of(mock(Role.class)));
        assertThat(service.getAllRoles()).hasSize(1);
    }

    @Test
    @DisplayName("getAllPermissions — 위임")
    void getAllPermissions() {
        when(loadRbacPort.findAllPermissions()).thenReturn(List.of(mock(Permission.class)));
        assertThat(service.getAllPermissions()).hasSize(1);
    }

    @Test
    @DisplayName("getRoleById — 존재하면 반환")
    void getRoleById_ok() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        assertThat(service.getRoleById(1L)).isSameAs(role);
    }

    @Test
    @DisplayName("getRoleById — 없으면 예외")
    void getRoleById_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRoleById(9L)).isInstanceOf(RoleInvariantViolationException.class);
    }

    @Test
    @DisplayName("updateRolePermissions — 교체 후 재조회 반환")
    void updateRolePermissions_ok() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        Role updated = service.updateRolePermissions(1L, List.of(10L, 20L));
        assertThat(updated).isSameAs(role);
        verify(saveRbacPort).replaceRolePermissions(1L, List.of(10L, 20L));
    }

    @Test
    @DisplayName("updateRolePermissions — permissionIds null 이면 count 0 로그 경로")
    void updateRolePermissions_nullIds() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        Role updated = service.updateRolePermissions(1L, null);
        assertThat(updated).isSameAs(role);
        verify(saveRbacPort).replaceRolePermissions(1L, null);
    }

    @Test
    @DisplayName("updateRolePermissions — 역할 없으면 예외")
    void updateRolePermissions_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRolePermissions(9L, List.of()))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    // ── 역할 CRUD ─────────────────────────────────────────────

    @Test
    @DisplayName("createRole — 코드 대문자 정규화 후 저장")
    void createRole_ok() {
        when(loadRbacPort.existsRoleByCode("CS_AGENT")).thenReturn(false);
        when(saveRbacPort.saveRole(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role created = service.createRole(new RbacUseCase.CreateRoleCommand(
                "cs_agent", "CS 상담원", "고객 문의 대응"));

        assertThat(created.getCode()).isEqualTo("CS_AGENT");
        assertThat(created.getName()).isEqualTo("CS 상담원");
        assertThat(created.isBuiltin()).isFalse();
    }

    @Test
    @DisplayName("createRole — 코드 중복이면 예외")
    void createRole_duplicateCode() {
        when(loadRbacPort.existsRoleByCode("CS_AGENT")).thenReturn(true);
        assertThatThrownBy(() -> service.createRole(new RbacUseCase.CreateRoleCommand(
                "CS_AGENT", "CS 상담원", null)))
                .isInstanceOf(RoleInvariantViolationException.class)
                .hasMessageContaining("이미 존재하는");
        verify(saveRbacPort, never()).saveRole(any());
    }

    @Test
    @DisplayName("createRole — 코드 형식 위반이면 예외 (한글/특수문자/1자)")
    void createRole_invalidCode() {
        assertThatThrownBy(() -> service.createRole(new RbacUseCase.CreateRoleCommand(
                "cs-agent!", "이름", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
        assertThatThrownBy(() -> service.createRole(new RbacUseCase.CreateRoleCommand(
                "A", "이름", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
        assertThatThrownBy(() -> service.createRole(new RbacUseCase.CreateRoleCommand(
                null, "이름", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    @Test
    @DisplayName("createRole — 이름 공백이면 예외")
    void createRole_blankName() {
        assertThatThrownBy(() -> service.createRole(new RbacUseCase.CreateRoleCommand(
                "CS_AGENT", "  ", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    @Test
    @DisplayName("updateRole — 이름/설명 수정 후 저장")
    void updateRole_ok() {
        Role role = Role.of(1L, "CS_AGENT", "이전 이름", "이전 설명", false, null);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        when(saveRbacPort.saveRole(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role updated = service.updateRole(1L, new RbacUseCase.UpdateRoleCommand("새 이름", " "));

        assertThat(updated.getName()).isEqualTo("새 이름");
        assertThat(updated.getDescription()).isNull();
        assertThat(updated.getCode()).isEqualTo("CS_AGENT"); // 코드 불변
    }

    @Test
    @DisplayName("updateRole — 없는 역할이면 예외")
    void updateRole_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRole(9L, new RbacUseCase.UpdateRoleCommand("n", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    @Test
    @DisplayName("deleteRole — 커스텀 역할은 삭제")
    void deleteRole_ok() {
        Role custom = Role.of(5L, "CS_AGENT", "CS 상담원", null, false, null);
        when(loadRbacPort.findRoleById(5L)).thenReturn(Optional.of(custom));

        service.deleteRole(5L);

        verify(saveRbacPort).deleteRoleById(5L);
    }

    @Test
    @DisplayName("deleteRole — builtin 역할은 예외")
    void deleteRole_builtin() {
        Role builtin = Role.of(1L, "ADMIN", "최고 관리자", null, true, null);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(builtin));

        assertThatThrownBy(() -> service.deleteRole(1L))
                .isInstanceOf(RoleInvariantViolationException.class)
                .hasMessageContaining("builtin");
        verify(saveRbacPort, never()).deleteRoleById(any());
    }

    @Test
    @DisplayName("deleteRole — 없는 역할이면 예외")
    void deleteRole_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteRole(9L))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    // ── 역할 복제 ─────────────────────────────────────────────

    @Test
    @DisplayName("cloneRole — 권한 매핑까지 복사")
    void cloneRole_ok() {
        Permission p1 = Permission.of(10L, "ORDER_READ", "주문 조회", "ORDER", null);
        Permission p2 = Permission.of(20L, "PRODUCT_READ", "상품 조회", "PRODUCT", null);
        Role source = Role.of(1L, "MANAGER", "매니저", "운영 매니저", true, null);
        source.replacePermissions(List.of(p1, p2));

        Role saved = Role.of(7L, "MANAGER_JR", "주니어 매니저", "운영 매니저", false, null);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(source));
        when(loadRbacPort.existsRoleByCode("MANAGER_JR")).thenReturn(false);
        when(saveRbacPort.saveRole(any(Role.class))).thenReturn(saved);
        when(loadRbacPort.findRoleById(7L)).thenReturn(Optional.of(saved));

        Role clone = service.cloneRole(1L, new RbacUseCase.CloneRoleCommand("manager_jr", "주니어 매니저"));

        assertThat(clone.getId()).isEqualTo(7L);
        verify(saveRbacPort).replaceRolePermissions(7L, List.of(10L, 20L));
    }

    @Test
    @DisplayName("cloneRole — 이름 생략 시 '원본이름 (복제)'")
    void cloneRole_defaultName() {
        Role source = Role.of(1L, "MANAGER", "매니저", null, true, null);
        Role saved = Role.of(7L, "MANAGER_COPY", "매니저 (복제)", null, false, null);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(source));
        when(loadRbacPort.existsRoleByCode("MANAGER_COPY")).thenReturn(false);
        when(saveRbacPort.saveRole(any(Role.class))).thenAnswer(inv -> {
            Role arg = inv.getArgument(0);
            assertThat(arg.getName()).isEqualTo("매니저 (복제)");
            return saved;
        });
        when(loadRbacPort.findRoleById(7L)).thenReturn(Optional.of(saved));

        Role clone = service.cloneRole(1L, new RbacUseCase.CloneRoleCommand("MANAGER_COPY", null));
        assertThat(clone.getCode()).isEqualTo("MANAGER_COPY");
    }

    @Test
    @DisplayName("cloneRole — 원본 없으면 예외")
    void cloneRole_sourceMissing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cloneRole(9L, new RbacUseCase.CloneRoleCommand("X_ROLE", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
    }

    @Test
    @DisplayName("cloneRole — 새 코드 중복이면 예외")
    void cloneRole_duplicateCode() {
        Role source = Role.of(1L, "MANAGER", "매니저", null, true, null);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(source));
        when(loadRbacPort.existsRoleByCode("ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> service.cloneRole(1L, new RbacUseCase.CloneRoleCommand("ADMIN", null)))
                .isInstanceOf(RoleInvariantViolationException.class);
        verify(saveRbacPort, never()).saveRole(any());
    }
}
