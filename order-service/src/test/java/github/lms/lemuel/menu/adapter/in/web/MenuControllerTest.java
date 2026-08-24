package github.lms.lemuel.menu.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuAttributes;
import github.lms.lemuel.menu.domain.MenuType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MenuController.class)
@AutoConfigureMockMvc(addFilters = false)
class MenuControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean MenuUseCase menuUseCase;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Menu group(long id, String name, String shortName, String path, MenuArea area) {
        Menu m = Menu.create(new MenuAttributes(name, shortName, path, "💰", "부제",
                area, MenuType.GROUP, "ADMIN,MANAGER", null), null, 0, true);
        m.assignId(id);
        return m;
    }

    private Menu item(long id, String name, String path, MenuArea area) {
        Menu m = Menu.create(MenuAttributes.item(name, path, area, "ADMIN,MANAGER"), null, 0, true);
        m.assignId(id);
        return m;
    }

    @Test
    @DisplayName("GET /api/menus/me: 역할을 useCase 에 그대로 넘기고 트리를 반환한다")
    void getMyMenus() throws Exception {
        authenticateAs("MANAGER");
        Menu settlement = group(1L, "정산", null, "/admin/settlement", MenuArea.BACKOFFICE);
        settlement.addChild(item(2L, "정산조회", "/settlement/search", MenuArea.BACKOFFICE));
        when(menuUseCase.getVisibleMenuTreeForRole("MANAGER")).thenReturn(List.of(settlement));

        mockMvc.perform(get("/api/menus/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("정산"))
                .andExpect(jsonPath("$[0].label").value("정산"))
                .andExpect(jsonPath("$[0].path").value("/admin/settlement"))
                .andExpect(jsonPath("$[0].area").value("BACKOFFICE"))
                .andExpect(jsonPath("$[0].type").value("GROUP"))
                .andExpect(jsonPath("$[0].children[0].name").value("정산조회"));

        verify(menuUseCase).getVisibleMenuTreeForRole("MANAGER");
    }

    @Test
    @DisplayName("GET /api/menus/me: shortName 이 있으면 label 로 내려간다")
    void shortNameBecomesLabel() throws Exception {
        authenticateAs("ADMIN");
        when(menuUseCase.getVisibleMenuTreeForRole("ADMIN"))
                .thenReturn(List.of(group(1L, "시스템 관리", "시스템", "/admin/system/menus", MenuArea.SYSTEM)));

        mockMvc.perform(get("/api/menus/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("시스템 관리"))
                .andExpect(jsonPath("$[0].label").value("시스템"));
    }

    @Test
    @DisplayName("GET /api/menus/me: 미인증이면 role=null 로 조회한다 (401 아님)")
    void anonymousGetsPublicMenusOnly() throws Exception {
        when(menuUseCase.getVisibleMenuTreeForRole(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/menus/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(menuUseCase).getVisibleMenuTreeForRole(isNull());
    }

    @Test
    @DisplayName("GET /api/menus/me: ROLE_ANONYMOUS 는 역할 없음으로 취급한다")
    void anonymousAuthorityTreatedAsNoRole() throws Exception {
        authenticateAs("ANONYMOUS");
        when(menuUseCase.getVisibleMenuTreeForRole(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/menus/me"))
                .andExpect(status().isOk());

        verify(menuUseCase).getVisibleMenuTreeForRole(isNull());
    }
}
