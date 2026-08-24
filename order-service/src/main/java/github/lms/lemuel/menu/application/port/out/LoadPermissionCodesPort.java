package github.lms.lemuel.menu.application.port.out;

import java.util.Set;

/**
 * 역할이 가진 권한 코드를 읽어 오는 아웃 포트.
 *
 * <p>메뉴는 RBAC 를 소유하지 않는다 — 권한 조회는 포트 뒤로 밀어 두고, 구현이 rbac 모듈을
 * 들여다보게 한다. 메뉴 애플리케이션 계층은 권한이 어디에 저장되는지 알 필요가 없다.
 */
public interface LoadPermissionCodesPort {

    /** 역할 코드가 가진 권한 코드 집합. 알 수 없는 역할이면 빈 집합. */
    Set<String> findByRoleCode(String roleCode);
}
