package github.lms.lemuel.commoncode.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeUseCase;
import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
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

@WebMvcTest(controllers = AdminCommonCodeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminCommonCodeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CommonCodeGroupUseCase groupUseCase;
    @MockitoBean CommonCodeUseCase codeUseCase;

    private CommonCodeGroup group() {
        return CommonCodeGroup.create("ORDER_STATUS", "주문상태", "주문 상태코드");
    }

    private CommonCode code() {
        CommonCode c = CommonCode.create("ORDER_STATUS", "PAID", "결제완료", 3, "green");
        c.assignId(10L);
        return c;
    }

    @Test
    @DisplayName("GET /admin/common-codes/groups: 그룹 목록")
    void getAllGroups() throws Exception {
        when(groupUseCase.getAllGroups()).thenReturn(List.of(group()));

        mockMvc.perform(get("/admin/common-codes/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupCode").value("ORDER_STATUS"));
    }

    @Test
    @DisplayName("POST /admin/common-codes/groups: 그룹 생성 201")
    void createGroup() throws Exception {
        when(groupUseCase.createGroup(any())).thenReturn(group());

        mockMvc.perform(post("/admin/common-codes/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"ORDER_STATUS","name":"주문상태","description":"주문 상태코드"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("주문상태"));
    }

    @Test
    @DisplayName("PUT /admin/common-codes/groups/{code}: 그룹 수정 200")
    void updateGroup() throws Exception {
        when(groupUseCase.updateGroup(eq("ORDER_STATUS"), any())).thenReturn(group());

        mockMvc.perform(put("/admin/common-codes/groups/ORDER_STATUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"주문상태","description":"수정됨","active":true}
                                """))
                .andExpect(status().isOk());

        verify(groupUseCase).updateGroup(eq("ORDER_STATUS"), any());
    }

    @Test
    @DisplayName("DELETE /admin/common-codes/groups/{code}: 그룹 삭제 204")
    void deleteGroup() throws Exception {
        mockMvc.perform(delete("/admin/common-codes/groups/ORDER_STATUS"))
                .andExpect(status().isNoContent());
        verify(groupUseCase).deleteGroup("ORDER_STATUS");
    }

    @Test
    @DisplayName("GET /admin/common-codes/groups/{code}/codes: 코드 목록")
    void getCodesByGroup() throws Exception {
        when(codeUseCase.getCodesByGroup("ORDER_STATUS")).thenReturn(List.of(code()));

        mockMvc.perform(get("/admin/common-codes/groups/ORDER_STATUS/codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PAID"))
                .andExpect(jsonPath("$[0].label").value("결제완료"));
    }

    @Test
    @DisplayName("POST /admin/common-codes: 코드 생성 201")
    void createCode() throws Exception {
        when(codeUseCase.createCode(any())).thenReturn(code());

        mockMvc.perform(post("/admin/common-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"ORDER_STATUS","code":"PAID","label":"결제완료",
                                 "sortOrder":3,"extra1":"green"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    @DisplayName("PUT /admin/common-codes/{id}: 코드 수정 200")
    void updateCode() throws Exception {
        when(codeUseCase.updateCode(eq(10L), any())).thenReturn(code());

        mockMvc.perform(put("/admin/common-codes/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"결제완료","sortOrder":3,"active":true,"extra1":"green"}
                                """))
                .andExpect(status().isOk());

        verify(codeUseCase).updateCode(eq(10L), any());
    }

    @Test
    @DisplayName("DELETE /admin/common-codes/{id}: 코드 삭제 204")
    void deleteCode() throws Exception {
        mockMvc.perform(delete("/admin/common-codes/10"))
                .andExpect(status().isNoContent());
        verify(codeUseCase).deleteCode(10L);
    }
}
