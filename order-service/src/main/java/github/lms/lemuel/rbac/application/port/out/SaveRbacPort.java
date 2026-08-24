package github.lms.lemuel.rbac.application.port.out;

import java.util.List;

import github.lms.lemuel.rbac.domain.Role;

public interface SaveRbacPort {

    /**
     * 역할의 권한 매핑을 전체 교체한다.
     * 기존 role_permissions 레코드를 삭제하고 새 permissionIds 로 재삽입.
     */
    void replaceRolePermissions(Long roleId, List<Long> permissionIds);

    /**
     * 역할 저장. id 가 없으면 신규 생성, 있으면 이름/설명만 갱신한다
     * (코드·builtin·권한 매핑은 이 경로로 변경하지 않는다).
     */
    Role saveRole(Role role);

    /** 역할 삭제. 권한 매핑(role_permissions)도 함께 제거된다. */
    void deleteRoleById(Long roleId);
}
