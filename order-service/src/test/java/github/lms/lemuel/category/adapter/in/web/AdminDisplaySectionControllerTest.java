package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.application.service.DisplaySectionService;
import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionItem;
import github.lms.lemuel.category.domain.DisplaySectionKind;
import github.lms.lemuel.common.config.jwt.JwtUtil;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminDisplaySectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDisplaySectionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean DisplaySectionService displaySectionService;

    private DisplaySection section() {
        return DisplaySection.rehydrate(7L, "EXH_2026_SUMMER", "2026 여름 기획전",
                DisplaySectionKind.EXHIBITION, null, null, null, 3, true);
    }

    @Test
    @DisplayName("GET /admin/display-sections: 노출이 끝난 편성까지 전부")
    void getAll() throws Exception {
        when(displaySectionService.getAllSections()).thenReturn(List.of(section()));

        mockMvc.perform(get("/admin/display-sections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("EXH_2026_SUMMER"))
                .andExpect(jsonPath("$[0].kind").value("EXHIBITION"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("GET /admin/display-sections/{code}/items: 편성에 담긴 상품 — 운영 콘솔이 편성 내용을 읽는 표면")
    void getItems() throws Exception {
        when(displaySectionService.getItems("EXH_2026_SUMMER")).thenReturn(List.of(
                DisplaySectionItem.of(7L, 101L, 0, true),
                DisplaySectionItem.of(7L, 102L, 1, false)));

        mockMvc.perform(get("/admin/display-sections/EXH_2026_SUMMER/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(101))
                .andExpect(jsonPath("$[0].pinned").value(true))
                .andExpect(jsonPath("$[1].productId").value(102))
                .andExpect(jsonPath("$[1].sortOrder").value(1));
    }

    @Test
    @DisplayName("POST /admin/display-sections: 생성 201")
    void create() throws Exception {
        when(displaySectionService.createSection(eq("EXH_2026_SUMMER"), eq("2026 여름 기획전"),
                eq(DisplaySectionKind.EXHIBITION), isNull(), any(), any(), eq(3)))
                .thenReturn(section());

        mockMvc.perform(post("/admin/display-sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"EXH_2026_SUMMER","name":"2026 여름 기획전","kind":"EXHIBITION",
                                 "categoryId":null,"startsAt":"2026-06-01T00:00:00",
                                 "endsAt":"2026-08-31T23:59:00","sortOrder":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("EXH_2026_SUMMER"));
    }

    @Test
    @DisplayName("POST /admin/display-sections/{code}/items: 고정 여부가 비면 고정하지 않는다")
    void addItemDefaultsToUnpinned() throws Exception {
        mockMvc.perform(post("/admin/display-sections/EXH_2026_SUMMER/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":101,"sortOrder":2}
                                """))
                .andExpect(status().isCreated());

        verify(displaySectionService).addProduct("EXH_2026_SUMMER", 101L, 2, false);
    }

    @Test
    @DisplayName("PATCH /admin/display-sections/{code}/active: 비활성 전환")
    void deactivate() throws Exception {
        when(displaySectionService.setActive("EXH_2026_SUMMER", false)).thenReturn(section());

        mockMvc.perform(patch("/admin/display-sections/EXH_2026_SUMMER/active").param("active", "false"))
                .andExpect(status().isOk());

        verify(displaySectionService).setActive("EXH_2026_SUMMER", false);
    }
}
