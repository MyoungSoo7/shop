package github.lms.lemuel.rbac.adapter.out.persistence;

import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC 영속 어댑터 매핑/위임 회귀 테스트 (Mockito, 실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class RbacPersistenceAdapterTest {

    @Mock SpringDataRoleJpaRepository roleRepository;
    @Mock SpringDataPermissionJpaRepository permissionRepository;
    @Mock SpringDataRolePermissionJpaRepository rolePermissionRepository;
    @InjectMocks RbacPersistenceAdapter adapter;

    private PermissionJpaEntity permEntity(long id, String code) {
        PermissionJpaEntity e = new PermissionJpaEntity();
        e.setId(id);
        e.setCode(code);
        e.setName(code + "-name");
        e.setCategory("ORDER");
        e.setDescription("desc");
        return e;
    }

    private RoleJpaEntity roleEntity() {
        RoleJpaEntity e = new RoleJpaEntity();
        e.setId(1L);
        e.setCode("ADMIN");
        e.setName("관리자");
        e.setDescription("전체권한");
        e.setBuiltin(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setPermissions(List.of(permEntity(11L, "ORDER_READ")));
        return e;
    }

    @Test
    @DisplayName("findAllRoles: 권한 포함 조회 후 도메인 매핑")
    void findAllRoles() {
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(roleEntity()));

        List<Role> roles = adapter.findAllRoles();

        assertThat(roles).hasSize(1);
        Role r = roles.get(0);
        assertThat(r.getCode()).isEqualTo("ADMIN");
        assertThat(r.isBuiltin()).isTrue();
        assertThat(r.getPermissions()).hasSize(1);
        assertThat(r.getPermissions().get(0).getCode()).isEqualTo("ORDER_READ");
    }

    @Test
    @DisplayName("findRoleById: 매칭 id 만 반환")
    void findRoleById_found() {
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(roleEntity()));

        Optional<Role> role = adapter.findRoleById(1L);

        assertThat(role).isPresent();
        assertThat(role.get().getName()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("findRoleById: 미매칭 id 는 empty")
    void findRoleById_notFound() {
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(roleEntity()));
        assertThat(adapter.findRoleById(999L)).isEmpty();
    }

    @Test
    @DisplayName("findRoleById: permissions 가 null 이어도 안전하게 빈 리스트로 매핑")
    void findRoleById_nullPermissions() {
        RoleJpaEntity e = roleEntity();
        e.setPermissions(null);
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(e));

        Optional<Role> role = adapter.findRoleById(1L);

        assertThat(role).isPresent();
        assertThat(role.get().getPermissions()).isEmpty();
    }

    @Test
    @DisplayName("findAllPermissions: 전체 조회 후 도메인 매핑")
    void findAllPermissions() {
        when(permissionRepository.findAll()).thenReturn(List.of(permEntity(11L, "ORDER_READ")));

        List<Permission> perms = adapter.findAllPermissions();

        assertThat(perms).hasSize(1);
        assertThat(perms.get(0).getCategory()).isEqualTo("ORDER");
    }

    @Test
    @DisplayName("findPermissionsByIds: 빈/널 입력은 즉시 빈 리스트")
    void findPermissionsByIds_empty() {
        assertThat(adapter.findPermissionsByIds(null)).isEmpty();
        assertThat(adapter.findPermissionsByIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("findPermissionsByIds: id 목록 조회 후 매핑")
    void findPermissionsByIds() {
        when(permissionRepository.findAllByIdIn(List.of(11L)))
                .thenReturn(List.of(permEntity(11L, "ORDER_READ")));

        List<Permission> perms = adapter.findPermissionsByIds(List.of(11L));

        assertThat(perms).hasSize(1);
        assertThat(perms.get(0).getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("replaceRolePermissions: 삭제→flush→저장 순서로 위임")
    void replaceRolePermissions() {
        adapter.replaceRolePermissions(1L, List.of(11L, 12L));

        verify(rolePermissionRepository).deleteAllByRoleId(1L);
        verify(rolePermissionRepository).flush();
        ArgumentCaptor<List<RolePermissionJpaEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("replaceRolePermissions: 빈 목록이면 삭제·flush 만 하고 저장 없음")
    void replaceRolePermissions_empty() {
        adapter.replaceRolePermissions(1L, List.of());

        verify(rolePermissionRepository).deleteAllByRoleId(1L);
        verify(rolePermissionRepository).flush();
    }
}
