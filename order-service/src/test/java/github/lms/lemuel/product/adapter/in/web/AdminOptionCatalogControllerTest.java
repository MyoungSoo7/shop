package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase;
import github.lms.lemuel.product.application.port.in.ManageOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.OptionInputType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminOptionCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminOptionCatalogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean BackfillOptionCatalogUseCase backfillUseCase;
    @MockitoBean BackfillVariantSignatureUseCase signatureUseCase;
    @MockitoBean LoadOptionCatalogPort loadCatalogPort;
    @MockitoBean ManageOptionCatalogUseCase manageUseCase;

    private OptionAxis color() {
        return OptionAxis.rehydrate(1L, "COLOR", "색상", OptionInputType.SWATCH, true);
    }

    private OptionAxisValue red() {
        return OptionAxisValue.rehydrate(11L, 1L, "RED", "빨강", "#FF0000", 0, true);
    }

    @Test
    @DisplayName("POST /admin/option-catalog/axes: 축 생성 201")
    void createAxis() throws Exception {
        when(manageUseCase.createAxis("CAPACITY", "용량", OptionInputType.SELECT))
                .thenReturn(OptionAxis.rehydrate(2L, "CAPACITY", "용량", OptionInputType.SELECT, true));

        mockMvc.perform(post("/admin/option-catalog/axes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"CAPACITY","name":"용량","inputType":"SELECT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CAPACITY"))
                .andExpect(jsonPath("$.inputType").value("SELECT"));
    }

    @Test
    @DisplayName("PATCH /admin/option-catalog/axes/{code}: 이름·표현방식 변경")
    void updateAxis() throws Exception {
        when(manageUseCase.updateAxis("COLOR", "컬러", OptionInputType.SWATCH)).thenReturn(color());

        mockMvc.perform(patch("/admin/option-catalog/axes/COLOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"컬러","inputType":"SWATCH"}
                                """))
                .andExpect(status().isOk());

        verify(manageUseCase).updateAxis("COLOR", "컬러", OptionInputType.SWATCH);
    }

    @Test
    @DisplayName("PATCH /admin/option-catalog/axes/{code}/active: 축 내리기")
    void deactivateAxis() throws Exception {
        when(manageUseCase.setAxisActive("COLOR", false)).thenReturn(color());

        mockMvc.perform(patch("/admin/option-catalog/axes/COLOR/active").param("active", "false"))
                .andExpect(status().isOk());

        verify(manageUseCase).setAxisActive("COLOR", false);
    }

    @Test
    @DisplayName("GET /admin/option-catalog/axes/{code}/values: 값 목록 — 표시색까지 내려 화면이 칩을 그린다")
    void getValues() throws Exception {
        when(manageUseCase.getValues("COLOR")).thenReturn(List.of(red()));

        mockMvc.perform(get("/admin/option-catalog/axes/COLOR/values"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("RED"))
                .andExpect(jsonPath("$[0].name").value("빨강"))
                .andExpect(jsonPath("$[0].swatchHex").value("#FF0000"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("POST /admin/option-catalog/axes/{code}/values: 값 추가 201 — 정렬이 비면 0")
    void addValueDefaultsSortOrder() throws Exception {
        when(manageUseCase.addValue("COLOR", "RED", "빨강", "#FF0000", 0)).thenReturn(red());

        mockMvc.perform(post("/admin/option-catalog/axes/COLOR/values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"RED","name":"빨강","swatchHex":"#FF0000"}
                                """))
                .andExpect(status().isCreated());

        verify(manageUseCase).addValue("COLOR", "RED", "빨강", "#FF0000", 0);
    }

    @Test
    @DisplayName("PATCH /admin/option-catalog/axes/{code}/values/{valueCode}: 값 수정")
    void updateValue() throws Exception {
        when(manageUseCase.updateValue("COLOR", "RED", "진빨강", "#CC0000", 2)).thenReturn(red());

        mockMvc.perform(patch("/admin/option-catalog/axes/COLOR/values/RED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"진빨강","swatchHex":"#CC0000","sortOrder":2}
                                """))
                .andExpect(status().isOk());

        verify(manageUseCase).updateValue("COLOR", "RED", "진빨강", "#CC0000", 2);
    }

    @Test
    @DisplayName("PATCH /admin/option-catalog/axes/{code}/values/{valueCode}/active: 값 내리기")
    void deactivateValue() throws Exception {
        when(manageUseCase.setValueActive("COLOR", "RED", false)).thenReturn(red());

        mockMvc.perform(patch("/admin/option-catalog/axes/COLOR/values/RED/active").param("active", "false"))
                .andExpect(status().isOk());

        verify(manageUseCase).setValueActive("COLOR", "RED", false);
    }

    @Test
    @DisplayName("GET /admin/option-catalog/axes: 기존 축 목록은 그대로")
    void getAxes() throws Exception {
        when(loadCatalogPort.loadAllAxes()).thenReturn(List.of(color()));

        mockMvc.perform(get("/admin/option-catalog/axes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("COLOR"));
    }
}
