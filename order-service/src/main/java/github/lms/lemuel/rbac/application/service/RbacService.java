package github.lms.lemuel.rbac.application.service;

import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.application.port.out.SaveRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import github.lms.lemuel.rbac.domain.exception.RoleInvariantViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RbacService implements RbacUseCase {

    private final LoadRbacPort loadRbacPort;
    private final SaveRbacPort saveRbacPort;

    @Override
    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return loadRbacPort.findAllRoles();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return loadRbacPort.findAllPermissions();
    }

    @Override
    @Transactional(readOnly = true)
    public Role getRoleById(Long id) {
        return loadRbacPort.findRoleById(id)
                .orElseThrow(() -> new RoleInvariantViolationException("존재하지 않는 역할 ID: " + id));
    }

    @Override
    public Role updateRolePermissions(Long roleId, List<Long> permissionIds) {
        // 역할 존재 여부 확인
        loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new RoleInvariantViolationException("존재하지 않는 역할 ID: " + roleId));

        saveRbacPort.replaceRolePermissions(roleId, permissionIds);
        log.info("역할 권한 갱신 완료: roleId={}, permissionCount={}", roleId,
                permissionIds == null ? 0 : permissionIds.size());

        // 방금 갱신한 역할을 같은 트랜잭션에서 재조회 — 실패는 발생할 수 없는 내부 불변식이라 generic 유지(프로그래밍 오류 가드).
        return loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new IllegalStateException("역할 재조회 실패: " + roleId));
    }

    @Override
    public Role createRole(CreateRoleCommand command) {
        Role role = Role.create(command.code(), command.name(), command.description());
        assertCodeAvailable(role.getCode());
        Role saved = saveRbacPort.saveRole(role);
        log.info("역할 생성: id={}, code={}", saved.getId(), saved.getCode());
        return saved;
    }

    @Override
    public Role updateRole(Long roleId, UpdateRoleCommand command) {
        Role role = loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new RoleInvariantViolationException("존재하지 않는 역할 ID: " + roleId));
        role.rename(command.name(), command.description());
        Role saved = saveRbacPort.saveRole(role);
        log.info("역할 수정: id={}, code={}", saved.getId(), saved.getCode());
        return saved;
    }

    @Override
    public void deleteRole(Long roleId) {
        Role role = loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new RoleInvariantViolationException("존재하지 않는 역할 ID: " + roleId));
        if (role.isBuiltin()) {
            throw new RoleInvariantViolationException("기본(builtin) 역할은 삭제할 수 없습니다: " + role.getCode());
        }
        saveRbacPort.deleteRoleById(roleId);
        log.info("역할 삭제: id={}, code={}", roleId, role.getCode());
    }

    @Override
    public Role cloneRole(Long sourceRoleId, CloneRoleCommand command) {
        Role source = loadRbacPort.findRoleById(sourceRoleId)
                .orElseThrow(() -> new RoleInvariantViolationException("존재하지 않는 역할 ID: " + sourceRoleId));

        String name = command.name() == null || command.name().isBlank()
                ? source.getName() + " (복제)" : command.name();
        Role clone = Role.create(command.code(), name, source.getDescription());
        assertCodeAvailable(clone.getCode());

        Role saved = saveRbacPort.saveRole(clone);
        List<Long> permissionIds = source.getPermissions().stream()
                .map(github.lms.lemuel.rbac.domain.Permission::getId)
                .toList();
        saveRbacPort.replaceRolePermissions(saved.getId(), permissionIds);
        log.info("역할 복제: source={}, clone={}, permissionCount={}",
                source.getCode(), saved.getCode(), permissionIds.size());

        // 방금 저장한 복제 역할을 같은 트랜잭션에서 재조회 — 실패는 발생할 수 없는 내부 불변식이라 generic 유지(프로그래밍 오류 가드).
        return loadRbacPort.findRoleById(saved.getId())
                .orElseThrow(() -> new IllegalStateException("복제 역할 재조회 실패: " + saved.getId()));
    }

    private void assertCodeAvailable(String code) {
        if (loadRbacPort.existsRoleByCode(code)) {
            throw new RoleInvariantViolationException("이미 존재하는 역할 코드입니다: " + code);
        }
    }
}
