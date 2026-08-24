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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicDisplaySectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicDisplaySectionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean DisplaySectionService displaySectionService;

    @Test
    @DisplayName("GET /display-sections: 노출 중인 편성만")
    void getVisibleSections() throws Exception {
        when(displaySectionService.getVisibleSections()).thenReturn(List.of(
                DisplaySection.rehydrate(7L, "EXH_2026_SUMMER", "2026 여름 기획전",
                        DisplaySectionKind.EXHIBITION, null, null, null, 0, true)));

        mockMvc.perform(get("/display-sections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("EXH_2026_SUMMER"));
    }

    @Test
    @DisplayName("GET /display-sections/{code}/items: 노출 판정을 거친 조회만 쓴다 — 운영 조회로 새면 시작 전 라인업이 공개된다")
    void getItemsGoesThroughVisibilityCheck() throws Exception {
        when(displaySectionService.getVisibleItems("EXH_2026_SUMMER")).thenReturn(List.of(
                DisplaySectionItem.of(7L, 101L, 0, true)));

        mockMvc.perform(get("/display-sections/EXH_2026_SUMMER/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(101));

        verify(displaySectionService).getVisibleItems("EXH_2026_SUMMER");
        verify(displaySectionService, never()).getItems("EXH_2026_SUMMER");
    }
}
