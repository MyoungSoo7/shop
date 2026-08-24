package github.lms.lemuel.coupon.application.service;
import github.lms.lemuel.coupon.domain.exception.InvalidCouponStateException;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock LoadCouponPort loadCouponPort;
    @Mock SaveCouponPort saveCouponPort;

    /** 쿠폰 기간 판정을 결정적으로 만들기 위한 고정 시각(KST) — 프로덕션은 TimeConfig 의 시스템 Clock. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 12, 0);
    private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

    private CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(loadCouponPort, saveCouponPort, clock);
    }

    @Test @DisplayName("createCoupon: 쿠폰 생성")
    void createCoupon() {
        var cmd = new CouponUseCase.CreateCouponCommand(
                "TEST10", CouponType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("10000"), new BigDecimal("5000"), 100,
                LocalDateTime.now().plusDays(30));
        when(saveCouponPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Coupon result = service.createCoupon(cmd);
        assertThat(result).isNotNull();
        verify(saveCouponPort).save(any());
    }

    @Test @DisplayName("validateCoupon: 존재하지 않는 쿠폰")
    void validateCoupon_notFound() {
        when(loadCouponPort.findByCode("INVALID")).thenReturn(Optional.empty());
        var result = service.validateCoupon("invalid", 1L, BigDecimal.TEN);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("존재하지 않는");
    }

    @Test @DisplayName("validateCoupon: 이미 사용한 쿠폰")
    void validateCoupon_alreadyUsed() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(1L);
        when(loadCouponPort.findByCode("USED")).thenReturn(Optional.of(coupon));
        when(loadCouponPort.hasUserUsedCoupon(1L, 1L)).thenReturn(true);
        var result = service.validateCoupon("USED", 1L, BigDecimal.TEN);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("이미 사용");
    }

    @Test @DisplayName("useCoupon: 쿠폰 사용 성공")
    void useCoupon() {
        Coupon coupon = mock(Coupon.class);
        when(loadCouponPort.findByCode("CODE")).thenReturn(Optional.of(coupon));
        when(coupon.getId()).thenReturn(1L);
        when(saveCouponPort.incrementUsageIfAvailable(1L)).thenReturn(true);
        service.useCoupon("code", 1L, 100L);
        verify(saveCouponPort).incrementUsageIfAvailable(1L);
        verify(saveCouponPort).recordUsage(1L, 1L, 100L);
    }

    @Test @DisplayName("useCoupon: 사용 한도 초과면 예외, 사용 기록 안 함")
    void useCoupon_exhausted() {
        Coupon coupon = mock(Coupon.class);
        when(loadCouponPort.findByCode("CODE")).thenReturn(Optional.of(coupon));
        when(coupon.getId()).thenReturn(1L);
        when(saveCouponPort.incrementUsageIfAvailable(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.useCoupon("code", 1L, 100L))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("한도");
        verify(saveCouponPort, never()).recordUsage(anyLong(), anyLong(), anyLong());
    }

    @Test @DisplayName("useCoupon: 존재하지 않는 쿠폰이면 예외")
    void useCoupon_notFound() {
        when(loadCouponPort.findByCode("INVALID")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.useCoupon("invalid", 1L, 100L))
                .isInstanceOf(CouponInvariantViolationException.class);
    }

    @Test @DisplayName("getAllCoupons: 전체 쿠폰 조회")
    void getAllCoupons() {
        when(loadCouponPort.findAll()).thenReturn(List.of());
        var result = service.getAllCoupons();
        assertThat(result).isEmpty();
    }
}
