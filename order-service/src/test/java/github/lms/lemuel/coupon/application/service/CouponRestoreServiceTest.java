package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주문 취소·환불 시 쿠폰 회수.
 *
 * <p>레거시 커머스(ssgb2e-front)는 취소 처리 안에서 쿠폰 상태를 미사용으로 되돌렸다. 그 규칙이
 * 없으면 "환불은 받았는데 1 회용 쿠폰은 소멸"하는 비대칭이 남는다.
 */
@DisplayName("CouponService — 주문 취소 시 쿠폰 회수")
class CouponRestoreServiceTest {

    private LoadCouponPort loadCouponPort;
    private SaveCouponPort saveCouponPort;
    private CouponService service;

    @BeforeEach
    void setUp() {
        loadCouponPort = mock(LoadCouponPort.class);
        saveCouponPort = mock(SaveCouponPort.class);
        service = new CouponService(loadCouponPort, saveCouponPort,
                Clock.system(ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("주문이 쓴 쿠폰의 사용 이력을 무효화하고 사용 횟수를 되돌린다")
    void restore_revokesUsageAndDecrementsCount() {
        when(saveCouponPort.revokeUsagesForOrder(77L, "주문 CANCELED")).thenReturn(List.of(5L, 9L));
        when(saveCouponPort.decrementUsage(anyLong())).thenReturn(true);

        int restored = service.restoreCouponsForOrder(77L, "주문 CANCELED");

        assertThat(restored).isEqualTo(2);
        verify(saveCouponPort).decrementUsage(5L);
        verify(saveCouponPort).decrementUsage(9L);
    }

    @Test
    @DisplayName("이미 되돌린 주문을 다시 취소해도 사용 횟수를 두 번 깎지 않는다 — 멱등")
    void restore_isIdempotent() {
        when(saveCouponPort.revokeUsagesForOrder(eq(77L), eq("재시도")))
                .thenReturn(List.of(5L))   // 1회차: 되돌릴 이력 있음
                .thenReturn(List.of());    // 2회차: 이미 무효화됨
        when(saveCouponPort.decrementUsage(5L)).thenReturn(true);

        assertThat(service.restoreCouponsForOrder(77L, "재시도")).isEqualTo(1);
        assertThat(service.restoreCouponsForOrder(77L, "재시도")).isZero();

        verify(saveCouponPort).decrementUsage(5L); // 정확히 1회
    }

    @Test
    @DisplayName("쿠폰을 쓰지 않은 주문은 아무 일도 하지 않는다")
    void restore_noCouponUsed() {
        when(saveCouponPort.revokeUsagesForOrder(anyLong(), eq("취소"))).thenReturn(List.of());

        assertThat(service.restoreCouponsForOrder(1L, "취소")).isZero();

        verify(saveCouponPort, never()).decrementUsage(anyLong());
    }

    @Test
    @DisplayName("사용 횟수가 이미 0 이면 취소를 실패시키지 않고 회수 0 건으로 보고한다")
    void restore_toleratesAlreadyZeroCount() {
        when(saveCouponPort.revokeUsagesForOrder(77L, "취소")).thenReturn(List.of(5L));
        when(saveCouponPort.decrementUsage(5L)).thenReturn(false);

        assertThat(service.restoreCouponsForOrder(77L, "취소")).isZero();
    }

    @Test
    @DisplayName("주문 식별자가 없으면(레거시·게스트) 조회조차 하지 않는다")
    void restore_nullOrderId() {
        assertThat(service.restoreCouponsForOrder(null, "취소")).isZero();

        verify(saveCouponPort, never()).revokeUsagesForOrder(anyLong(), eq("취소"));
    }
}
