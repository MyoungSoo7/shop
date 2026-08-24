package github.lms.lemuel.coupon.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CouponController.class)
@AutoConfigureMockMvc(addFilters = false)
class CouponControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CouponUseCase couponUseCase;

    @Test
    @DisplayName("POST /coupons creates coupon")
    void createCoupon() throws Exception {
        when(couponUseCase.createCoupon(any())).thenReturn(coupon("SAVE10"));

        mockMvc.perform(post("/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"SAVE10",
                                  "type":"FIXED",
                                  "discountValue":1000,
                                  "minOrderAmount":10000,
                                  "maxUses":100,
                                  "targetType":"ALL",
                                  "expiresAt":"2026-12-31T23:59:59"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE10"))
                .andExpect(jsonPath("$.type").value("FIXED"));
    }

    @Test
    @DisplayName("GET /coupons returns all coupons")
    void getAllCoupons() throws Exception {
        when(couponUseCase.getAllCoupons()).thenReturn(List.of(coupon("SAVE10")));

        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SAVE10"));
    }

    @Test
    @DisplayName("GET /coupons/{code}/validate returns discount result")
    void validateCoupon() throws Exception {
        when(couponUseCase.validateCoupon("SAVE10", 1L, new BigDecimal("10000")))
                .thenReturn(new CouponUseCase.ValidateResult(true, "ok",
                        new BigDecimal("1000"), new BigDecimal("9000"), coupon("SAVE10")));

        mockMvc.perform(get("/coupons/SAVE10/validate")
                        .param("userId", "1")
                        .param("amount", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discountAmount").value(1000));
    }

    @Test
    @DisplayName("POST /coupons/{code}/use delegates use command")
    void useCoupon() throws Exception {
        mockMvc.perform(post("/coupons/SAVE10/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"orderId":2}
                                """))
                .andExpect(status().isOk());

        verify(couponUseCase).useCoupon("SAVE10", 1L, 2L);
    }

    private static Coupon coupon(String code) {
        Coupon coupon = Coupon.create(code, CouponType.FIXED, new BigDecimal("1000"),
                new BigDecimal("10000"), null, 100, LocalDateTime.now().plusDays(1));
        coupon.assignId(1L);
        return coupon;
    }
}
