package github.lms.lemuel.menu.adapter.in.web;

import github.lms.lemuel.menu.adapter.in.web.dto.NavMenuResponse;
import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 셸 네비게이션용 메뉴 조회 API.
 *
 * <p>관리용 {@code /admin/menus} 와 분리한다 — 이쪽은 모든 로그인 사용자가 매 화면 진입에
 * 호출하는 읽기 경로이고, 응답은 호출자 권한으로 이미 걸러진 것만 담는다.
 *
 * <p><b>메뉴가 숨겨지는 것은 UX 이지 보안이 아니다.</b> URL 직접 입력은 각 API 의 인가가 막는다.
 */
@Tag(name = "Menu", description = "로그인 사용자용 네비게이션 메뉴 조회")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ANONYMOUS_ROLE = "ANONYMOUS";

    private final MenuUseCase menuUseCase;

    /**
     * 내 메뉴 트리 조회
     * GET /api/menus/me
     */
    @Operation(summary = "내 네비게이션 메뉴 조회",
            description = "호출자의 역할·권한으로 필터링된 메뉴 트리를 반환한다. 미인증이면 공개 메뉴만 담긴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/me")
    public ResponseEntity<List<NavMenuResponse>> getMyMenus() {
        List<NavMenuResponse> response = menuUseCase.getVisibleMenuTreeForRole(currentRole()).stream()
                .map(NavMenuResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * 현재 호출자의 역할 코드. 미인증이거나 익명 토큰이면 null 을 돌려 "공개 메뉴만" 경로로 보낸다.
     */
    private String currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .filter(role -> !ANONYMOUS_ROLE.equals(role))
                .findFirst()
                .orElse(null);
    }
}
