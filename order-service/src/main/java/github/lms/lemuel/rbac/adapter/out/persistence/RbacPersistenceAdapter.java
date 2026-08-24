package github.lms.lemuel.rbac.adapter.out.persistence;

import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.application.port.out.SaveRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RbacPersistenceAdapter implements LoadRbacPort, SaveRbacPort {

    private final SpringDataRoleJpaRepository roleRepository;
    private final SpringDataPermissionJpaRepository permissionRepository;
    private final SpringDataRolePermissionJpaRepository rolePermissionRepository;

    @Override
    public List<Role> findAllRoles() {
        return roleRepository.findAllWithPermissions().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Role> findRoleById(Long id) {
        return roleRepository.findAllWithPermissions().stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .map(this::toDomain);
    }

    @Override
    public List<Permission> findAllPermissions() {
        return permissionRepository.findAll().stream()
                .map(this::toPermissionDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Permission> findPermissionsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return permissionRepository.findAllByIdIn(ids).stream()
                .map(this::toPermissionDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsRoleByCode(String code) {
        return roleRepository.existsByCode(code);
    }

    @Override
    public Role saveRole(Role role) {
        RoleJpaEntity entity;
        if (role.getId() != null) {
            // 기존 역할: 이름/설명만 갱신 (권한 매핑은 replaceRolePermissions 전용 경로)
            entity = roleRepository.findById(role.getId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 역할 ID: " + role.getId()));
            entity.setName(role.getName());
            entity.setDescription(role.getDescription());
        } else {
            entity = new RoleJpaEntity();
            entity.setCode(role.getCode());
            entity.setName(role.getName());
            entity.setDescription(role.getDescription());
            entity.setBuiltin(role.isBuiltin());
            entity.setCreatedAt(role.getCreatedAt());
        }
        return toDomain(roleRepository.save(entity));
    }

    @Override
    public void deleteRoleById(Long roleId) {
        rolePermissionRepository.deleteAllByRoleId(roleId);
        roleRepository.deleteById(roleId);
    }

    @Override
    public void replaceRolePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionRepository.deleteAllByRoleId(roleId);
        rolePermissionRepository.flush();
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<RolePermissionJpaEntity> entities = permissionIds.stream()
                    .map(pid -> new RolePermissionJpaEntity(new RolePermissionId(roleId, pid)))
                    .collect(Collectors.toList());
            rolePermissionRepository.saveAll(entities);
        }
    }

    // ── 매핑 헬퍼 ──────────────────────────────────────────────

    private Role toDomain(RoleJpaEntity entity) {
        Role role = Role.of(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.isBuiltin(),
                entity.getCreatedAt()
        );
        List<Permission> perms = entity.getPermissions() == null ? List.of() :
                entity.getPermissions().stream()
                        .map(this::toPermissionDomain)
                        .collect(Collectors.toList());
        role.replacePermissions(perms);
        return role;
    }

    private Permission toPermissionDomain(PermissionJpaEntity entity) {
        return Permission.of(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getCategory(),
                entity.getDescription()
        );
    }
}
