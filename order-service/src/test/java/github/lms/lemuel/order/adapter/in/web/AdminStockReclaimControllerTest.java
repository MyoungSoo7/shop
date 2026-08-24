package github.lms.lemuel.order.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase.PendingLine;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase.PendingReclaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회수 대기 재고 운영 콘솔.
 *
 * <p>배송 후 환불로 재고가 보류된 건을 운영자가 훑어 택배 회수를 독촉하거나 손실 처리를 판단한다.
 */
class AdminStockReclaimControllerTest {

    private GetPendingStockReclaimUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(GetPendingStockReclaimUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminStockReclaimController(useCase))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private PendingReclaim sample() {
        return new PendingReclaim(7L, 42L, "REFUNDED",
                LocalDateTime.of(2026, 8, 1, 9, 0), 19L, 5, new BigDecimal("35000"),
                List.of(new PendingLine(100L, 500L, "SKU-1", "상품A", 2),
                        new PendingLine(200L, null, null, "상품B", 3)));
    }

    @Test @DisplayName("회수 대기 목록을 묶인 수량·경과일과 함께 돌려준다")
    void listsPendingReclaims() throws Exception {
        when(useCase.findPending(any(), anyInt())).thenReturn(List.of(sample()));

        mockMvc.perform(get("/admin/stock-reclaim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.items[0].orderId").value(7))
                .andExpect(jsonPath("$.items[0].pendingDays").value(19))
                .andExpect(jsonPath("$.items[0].lines[0].sku").value("SKU-1"));
    }

    @Test @DisplayName("대기 건이 없으면 빈 목록과 합계 0")
    void emptyList() throws Exception {
        when(useCase.findPending(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/stock-reclaim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(0))
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test @DisplayName("limit 파라미터를 유스케이스에 전달한다(기본 100)")
    void passesLimit() throws Exception {
        when(useCase.findPending(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/stock-reclaim")).andExpect(status().isOk());
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(useCase).findPending(any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(100);

        mockMvc.perform(get("/admin/stock-reclaim").param("limit", "5"))
                .andExpect(status().isOk());
        verify(useCase).findPending(any(), org.mockito.ArgumentMatchers.eq(5));
    }
}
