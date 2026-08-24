package github.lms.lemuel.coupon.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.coupon.application.port.in.ManageCouponUseCase;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponExport;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycle;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycleCount;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponPage;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponQuery;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponRow;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponUsageRow;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 쿠폰 콘솔의 HTTP 표면.
 *
 * <p>여기서 지키는 것: 필터가 질의로 온전히 옮겨지는가, 상태별 집계가 상태 필터에 오염되지
 * 않는가, 그리고 <b>삭제 엔드포인트가 없는가</b>. 마지막은 "생기지 않았음"을 지키는 테스트다 —
 * 사용된 쿠폰을 지우면 사용 이력의 참조가 끊겨 그 할인이 어디서 왔는지 설명할 수 없게 된다.
 */
@WebMvcTest(controllers = AdminCouponController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminCouponControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SearchCouponsUseCase searchCouponsUseCase;
    @MockitoBean ManageCouponUseCase manageCouponUseCase;

    private static CouponRow row() {
        return new CouponRow(1L, "WELCOME10", "PERCENTAGE", BigDecimal.TEN, BigDecimal.ZERO, null,
                100, 3, "ALL", null, null, LocalDateTime.of(2027, 1, 1, 0, 0), true, "ACTIVE",
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private static Coupon coupon() {
        return Coupon.create("WELCOME10", CouponType.PERCENTAGE, BigDecimal.TEN,
                BigDecimal.ZERO, null, 100, LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("GET /admin/coupons — 수명 상태까지 담아 돌려준다")
    void search() throws Exception {
        when(searchCouponsUseCase.search(any()))
                .thenReturn(new CouponPage(List.of(row()), 0, 50, 1, 1));

        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("WELCOME10"))
                .andExpect(jsonPath("$.content[0].lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].usedCount").value(3));
    }

    @Test
    @DisplayName("필터 파라미터는 그대로 질의가 된다")
    void passesFiltersThrough() throws Exception {
        when(searchCouponsUseCase.search(any())).thenReturn(new CouponPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/coupons")
                        .param("code", "welcome")
                        .param("lifecycle", "exhausted")
                        .param("type", "percentage")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk());

        ArgumentCaptor<CouponQuery> captor = ArgumentCaptor.forClass(CouponQuery.class);
        verify(searchCouponsUseCase).search(captor.capture());
        CouponQuery query = captor.getValue();
        assertThat(query.code()).isEqualTo("welcome");
        assertThat(query.lifecycle()).isEqualTo(CouponLifecycle.EXHAUSTED);
        assertThat(query.type()).isEqualTo("PERCENTAGE");
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("모르는 수명 상태는 조건에서 뺀다")
    void unknownLifecycleDropsFilter() throws Exception {
        when(searchCouponsUseCase.search(any())).thenReturn(new CouponPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/coupons").param("lifecycle", "DEAD"))
                .andExpect(status().isOk());

        ArgumentCaptor<CouponQuery> captor = ArgumentCaptor.forClass(CouponQuery.class);
        verify(searchCouponsUseCase).search(captor.capture());
        assertThat(captor.getValue().lifecycle()).isNull();
    }

    @Test
    @DisplayName("상태별 집계에는 상태 필터를 걸지 않는다")
    void lifecycleCountsIgnoreLifecycleFilter() throws Exception {
        when(searchCouponsUseCase.countByLifecycle(any()))
                .thenReturn(List.of(new CouponLifecycleCount("ACTIVE", 7)));

        mockMvc.perform(get("/admin/coupons/lifecycle-counts").param("code", "W"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$[0].count").value(7));

        ArgumentCaptor<CouponQuery> captor = ArgumentCaptor.forClass(CouponQuery.class);
        verify(searchCouponsUseCase).countByLifecycle(captor.capture());
        assertThat(captor.getValue().lifecycle()).isNull();
        assertThat(captor.getValue().code()).isEqualTo("W");
    }

    @Test
    @DisplayName("GET /admin/coupons/enums — 서버 enum 이 필터 목록의 정본이다")
    void enumsAreServerOwned() throws Exception {
        mockMvc.perform(get("/admin/coupons/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycles").value(org.hamcrest.Matchers.hasItems(
                        "ACTIVE", "EXPIRED", "EXHAUSTED", "SCHEDULED", "INACTIVE")))
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.hasItems(
                        "FIXED", "PERCENTAGE")));
    }

    @Test
    @DisplayName("사용 내역은 회수된 이력도 함께 보여 준다 — 없으면 숫자 불일치가 버그인지 정상인지 알 수 없다")
    void usagesIncludeRevoked() throws Exception {
        when(searchCouponsUseCase.usages(anyLong(), anyInt())).thenReturn(List.of(
                new CouponUsageRow(1L, 5L, "a@b.c", 77L,
                        LocalDateTime.of(2026, 3, 1, 10, 0),
                        LocalDateTime.of(2026, 3, 2, 10, 0), "주문 취소")));

        mockMvc.perform(get("/admin/coupons/1/usages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userEmail").value("a@b.c"))
                .andExpect(jsonPath("$[0].revokeReason").value("주문 취소"));
    }

    @Test
    @DisplayName("중단은 코드로 부른다")
    void deactivate() throws Exception {
        when(manageCouponUseCase.deactivate(any())).thenReturn(coupon());

        mockMvc.perform(post("/admin/coupons/WELCOME10/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WELCOME10"));

        verify(manageCouponUseCase).deactivate(eq("WELCOME10"));
    }

    @Test
    @DisplayName("재개도 코드로 부른다")
    void activate() throws Exception {
        when(manageCouponUseCase.activate(any())).thenReturn(coupon());

        mockMvc.perform(post("/admin/coupons/WELCOME10/activate"))
                .andExpect(status().isOk());

        verify(manageCouponUseCase).activate(eq("WELCOME10"));
    }

    @Test
    @DisplayName("삭제 엔드포인트는 존재하지 않는다 — 사용 이력의 참조가 끊기면 할인 출처를 설명할 수 없다")
    void hasNoDeleteEndpoint() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/coupons/WELCOME10"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
    }

    @Test
    @DisplayName("CSV 는 BOM 으로 시작하고 무제한 한도를 '무제한'으로 적는다")
    void exportsCsv() throws Exception {
        CouponRow unlimited = new CouponRow(2L, "OPEN", "FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 0, 12, "ALL", null, null, null, true, "ACTIVE",
                LocalDateTime.of(2026, 1, 1, 0, 0));
        when(searchCouponsUseCase.export(any()))
                .thenReturn(new CouponExport(List.of(unlimited), false, 1));

        MvcResult result = mockMvc.perform(get("/admin/coupons/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        assertThat(body).contains("\"12/무제한\"");
    }
}
