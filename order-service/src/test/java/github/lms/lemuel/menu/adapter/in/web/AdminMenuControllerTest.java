package github.lms.lemuel.menu.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.domain.Menu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminMenuController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminMenuControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean MenuUseCase menuUseCase;

    private Menu menu(long id, String name) {
        Menu m = Menu.create(new github.lms.lemuel.menu.domain.MenuAttributes(
                name, null, "/admin/" + name, "icon", null,
                github.lms.lemuel.menu.domain.MenuArea.SYSTEM,
                github.lms.lemuel.menu.domain.MenuType.ITEM, "ADMIN", null), null, 1, true);
        m.assignId(id);
        return m;
    }

    @Test
    @DisplayName("GET /admin/menus: 트리 응답 (children 포함)")
    void getMenuTree() throws Exception {
        Menu root = menu(1L, "users");
        root.addChild(menu(2L, "roles"));
        when(menuUseCase.getMenuTree()).thenReturn(List.of(root));

        mockMvc.perform(get("/admin/menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].children[0].id").value(2));
    }

    @Test
    @DisplayName("GET /admin/menus/flat: 평면 응답 (children 빈 배열)")
    void getMenuFlat() throws Exception {
        when(menuUseCase.getAllFlat()).thenReturn(List.of(menu(1L, "users")));

        mockMvc.perform(get("/admin/menus/flat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].children").isEmpty());
    }

    @Test
    @DisplayName("POST /admin/menus: 생성 위임 후 201")
    void createMenu() throws Exception {
        when(menuUseCase.createMenu(any())).thenReturn(menu(5L, "products"));

        mockMvc.perform(post("/admin/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"상품관리","path":"/admin/products","icon":"box",
                                 "area":"BACKOFFICE","menuType":"ITEM",
                                 "parentId":null,"sortOrder":1,"requiredRole":"ADMIN","visible":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));

        verify(menuUseCase).createMenu(any(MenuUseCase.CreateMenuCommand.class));
    }

    @Test
    @DisplayName("PUT /admin/menus/{id}: 수정 위임 후 200")
    void updateMenu() throws Exception {
        when(menuUseCase.updateMenu(eq(5L), any())).thenReturn(menu(5L, "products"));

        mockMvc.perform(put("/admin/menus/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"상품관리","path":"/admin/products","icon":"box",
                                 "area":"BACKOFFICE","menuType":"ITEM",
                                 "parentId":null,"sortOrder":2,"requiredRole":"ADMIN",
                                 "visible":true,"active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));

        verify(menuUseCase).updateMenu(eq(5L), any(MenuUseCase.UpdateMenuCommand.class));
    }

    @Test
    @DisplayName("DELETE /admin/menus/{id}: 삭제 위임 후 204")
    void deleteMenu() throws Exception {
        mockMvc.perform(delete("/admin/menus/5"))
                .andExpect(status().isNoContent());
        verify(menuUseCase).deleteMenu(5L);
    }
}
