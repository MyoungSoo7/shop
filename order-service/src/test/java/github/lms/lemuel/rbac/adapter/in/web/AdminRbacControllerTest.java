package github.lms.lemuel.rbac.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminRbacController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminRbacControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RbacUseCase rbacUseCase;

    private Role role() {
        Role r = Role.of(1L, "ADMIN", "관리자", "전체권한", true, LocalDateTime.now());
        r.replacePermissions(List.of(Permission.of(11L, "ORDER_READ", "주문조회", "ORDER", "desc")));
        return r;
    }

    @Test
    @DisplayName("GET /admin/rbac/roles: 역할 목록 (권한 ID/코드 포함)")
    void getRoles() throws Exception {
        when(rbacUseCase.getAllRoles()).thenReturn(List.of(role()));

        mockMvc.perform(get("/admin/rbac/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ADMIN"))
                .andExpect(jsonPath("$[0].permissionCodes[0]").value("ORDER_READ"))
                .andExpect(jsonPath("$[0].permissionIds[0]").value(11));
    }

    @Test
    @DisplayName("GET /admin/rbac/permissions: 권한 평면 목록")
    void getPermissions() throws Exception {
        when(rbacUseCase.getAllPermissions())
                .thenReturn(List.of(Permission.of(11L, "ORDER_READ", "주문조회", "ORDER", "desc")));

        mockMvc.perform(get("/admin/rbac/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ORDER_READ"))
                .andExpect(jsonPath("$[0].category").value("ORDER"));
    }

    @Test
    @DisplayName("GET /admin/rbac/roles/{id}: 역할 단건")
    void getRole() throws Exception {
        when(rbacUseCase.getRoleById(1L)).thenReturn(role());

        mockMvc.perform(get("/admin/rbac/roles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.builtin").value(true));
    }

    @Test
    @DisplayName("PUT /admin/rbac/roles/{id}/permissions: 권한 매트릭스 교체")
    void updateRolePermissions() throws Exception {
        when(rbacUseCase.updateRolePermissions(eq(1L), eq(List.of(11L, 12L)))).thenReturn(role());

        mockMvc.perform(put("/admin/rbac/roles/1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionIds":[11,12]}
                                """))
                .andExpect(status().isOk());
        verify(rbacUseCase).updateRolePermissions(eq(1L), eq(List.of(11L, 12L)));
    }
}
