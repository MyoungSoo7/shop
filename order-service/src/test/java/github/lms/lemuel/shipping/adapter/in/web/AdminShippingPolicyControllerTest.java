package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.application.port.in.ManageSellerShippingPolicyUseCase;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배송비 정책 운영 콘솔의 HTTP 계약.
 *
 * <p>여기서 못박는 것은 화면이 의존하는 두 가지다: ① 목록은 배열을 그대로 준다(껍데기 없음),
 * ② 정책이 없는 셀러는 404 다 — 화면이 "미등록"과 "0 원 정책"을 구분할 수 있어야 한다.
 * 둘이 같은 응답이면 운영자는 배송비가 왜 안 붙는지 화면만 보고는 알 수 없다.
 */
@DisplayName("AdminShippingPolicyController — 배송비 정책 콘솔 API")
class AdminShippingPolicyControllerTest {

    private ManageSellerShippingPolicyUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ManageSellerShippingPolicyUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminShippingPolicyController(useCase)).build();
    }

    @Test
    @DisplayName("GET / — 등록된 정책을 배열로 준다")
    void list() throws Exception {
        when(useCase.findAll()).thenReturn(List.of(
                SellerShippingPolicy.rehydrate(7L, new BigDecimal("3000"), new BigDecimal("50000")),
                SellerShippingPolicy.rehydrate(8L, new BigDecimal("2500"), null)));

        mockMvc.perform(get("/admin/shipping-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sellerId").value(7))
                .andExpect(jsonPath("$[0].baseFee").value(3000))
                .andExpect(jsonPath("$[0].freeThreshold").value(50000))
                // 임계 없음은 0 이 아니라 null 로 나가야 한다 — 0 이면 "항상 무료"라는 정반대 뜻이 된다.
                .andExpect(jsonPath("$[1].freeThreshold").doesNotExist());
    }

    @Test
    @DisplayName("GET / — 한 건도 없으면 빈 배열")
    void listEmpty() throws Exception {
        when(useCase.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/admin/shipping-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /{sellerId} — 미등록 셀러는 404")
    void findNotFound() throws Exception {
        when(useCase.find(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/shipping-policies/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /{sellerId} — 등록·변경은 저장된 정책을 되돌려 준다")
    void upsert() throws Exception {
        when(useCase.upsert(eq(7L), any(), any()))
                .thenReturn(SellerShippingPolicy.rehydrate(7L, new BigDecimal("3000"), new BigDecimal("50000")));

        mockMvc.perform(put("/admin/shipping-policies/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseFee\":3000,\"freeThreshold\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.baseFee").value(3000));

        verify(useCase).upsert(7L, new BigDecimal("3000"), new BigDecimal("50000"));
    }

    @Test
    @DisplayName("PUT /{sellerId} — freeThreshold 를 비우면 null 로 전달된다(무료배송 조건 없음)")
    void upsertWithoutThreshold() throws Exception {
        when(useCase.upsert(eq(7L), any(), any()))
                .thenReturn(SellerShippingPolicy.rehydrate(7L, new BigDecimal("3000"), null));

        mockMvc.perform(put("/admin/shipping-policies/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseFee\":3000}"))
                .andExpect(status().isOk());

        verify(useCase).upsert(7L, new BigDecimal("3000"), null);
    }
}
