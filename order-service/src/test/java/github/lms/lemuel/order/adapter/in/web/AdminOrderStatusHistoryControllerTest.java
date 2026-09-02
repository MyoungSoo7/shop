package github.lms.lemuel.order.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase;
import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase.OrderStatusTimeline;
import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase.StatusStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 상태 이력 운영 콘솔.
 *
 * <p>CS 가 "이 주문 왜 이 상태예요?" 를 이 한 번의 호출로 끝내는 것이 목적이므로, 여기서 확인하는 것은
 * 경로가 {@code /orders/admin/**} 아래에 있다는 것과, 화면이 봐야 할 두 값(체류 시간·이력 불일치)이
 * 실제로 JSON 에 실려 나간다는 것이다.
 */
class AdminOrderStatusHistoryControllerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0, 0);

    private ViewOrderStatusHistoryUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ViewOrderStatusHistoryUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminOrderStatusHistoryController(useCase))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private static OrderStatusTimeline timeline(boolean matches) {
        return new OrderStatusTimeline(42L, "SHIPPING_PENDING",
                matches ? "SHIPPING_PENDING" : "PAID", matches,
                List.of(new StatusStep(1L, null, "CREATED", "user@lemuel", null, T0, 3_600L),
                        new StatusStep(2L, "CREATED", "PAID", "system", "결제 승인", T0.plusHours(1), 777_600L)));
    }

    @Test
    @DisplayName("경로는 /orders/admin/{orderId}/status-history — 이미 보호·라우팅되는 접두사 아래에 있다")
    void 경로와_기본_응답() throws Exception {
        when(useCase.view(anyLong(), any())).thenReturn(timeline(true));

        mockMvc.perform(get("/orders/admin/42/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(42))
                .andExpect(jsonPath("$.currentStatus").value("SHIPPING_PENDING"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    @DisplayName("경로의 orderId 를 그대로 유스케이스에 넘긴다")
    void orderId_전달() throws Exception {
        when(useCase.view(anyLong(), any())).thenReturn(timeline(true));

        mockMvc.perform(get("/orders/admin/4242/status-history")).andExpect(status().isOk());

        ArgumentCaptor<Long> orderId = ArgumentCaptor.forClass(Long.class);
        verify(useCase).view(orderId.capture(), any());
        assertThat(orderId.getValue()).isEqualTo(4242L);
    }

    @Test
    @DisplayName("체류 시간이 응답에 실린다 — 이게 없으면 그냥 테이블 덤프다")
    void 체류_시간이_나간다() throws Exception {
        when(useCase.view(anyLong(), any())).thenReturn(timeline(true));

        mockMvc.perform(get("/orders/admin/42/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].dwellSeconds").value(3600))
                .andExpect(jsonPath("$.steps[1].dwellSeconds").value(777600))
                .andExpect(jsonPath("$.steps[1].reason").value("결제 승인"))
                .andExpect(jsonPath("$.steps[1].changedBy").value("system"));
    }

    @Test
    @DisplayName("이력 불일치가 응답에 실린다 — 이력을 안 남긴 전이가 있다는 유일한 신호다")
    void 불일치가_나간다() throws Exception {
        when(useCase.view(anyLong(), any())).thenReturn(timeline(false));

        mockMvc.perform(get("/orders/admin/42/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMatchesOrder").value(false))
                .andExpect(jsonPath("$.currentStatus").value("SHIPPING_PENDING"))
                .andExpect(jsonPath("$.lastRecordedStatus").value("PAID"));
    }
}
