package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.out.PointUsageLimitPort;
import github.lms.lemuel.point.domain.PointUsageLimit;
import github.lms.lemuel.point.domain.PointUsageLimitType;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointUsageLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ManagePointUsageLimitService — 사용 상한 조회·검사·변경")
class ManagePointUsageLimitServiceTest {

    private PointUsageLimitPort port;
    private ManagePointUsageLimitService service;

    @BeforeEach
    void setUp() {
        port = mock(PointUsageLimitPort.class);
        service = new ManagePointUsageLimitService(port);
    }

    @Test
    @DisplayName("포인트를 쓰지 않는 결제는 정책을 조회조차 하지 않는다")
    void zeroUsageSkipsPolicyLoad() {
        service.assertWithinLimit(new BigDecimal("50000"), BigDecimal.ZERO);
        service.assertWithinLimit(new BigDecimal("50000"), null);

        verify(port, never()).load();
    }

    @Test
    @DisplayName("상한을 넘으면 거절한다")
    void exceedRejected() {
        when(port.load()).thenReturn(PointUsageLimit.orderRatio(new BigDecimal("30")));

        assertThatThrownBy(() -> service.assertWithinLimit(new BigDecimal("50000"), new BigDecimal("20000")))
                .isInstanceOf(PointUsageLimitExceededException.class);
        assertThatCode(() -> service.assertWithinLimit(new BigDecimal("50000"), new BigDecimal("15000")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정책 행이 없으면 상한 없음으로 착지 — 정책 부재로 고객 사용을 막지 않는다")
    void missingPolicyMeansNoLimit() {
        when(port.load()).thenReturn(PointUsageLimit.none());

        assertThatCode(() -> service.assertWithinLimit(new BigDecimal("50000"), new BigDecimal("50000")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유형별로 필요한 값만 써서 저장한다")
    void updateByType() {
        when(port.save(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(PointUsageLimitType.NONE, null, null, "admin").getType())
                .isEqualTo(PointUsageLimitType.NONE);
        assertThat(service.update(PointUsageLimitType.FIXED_AMOUNT, new BigDecimal("10000"), null, "admin")
                .maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("10000");
        assertThat(service.update(PointUsageLimitType.ORDER_RATIO, null, new BigDecimal("30"), "admin")
                .maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("유형 누락·잘못된 값은 저장까지 가지 않는다")
    void invalidUpdateRejected() {
        assertThatThrownBy(() -> service.update(null, null, null, "admin"))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> service.update(PointUsageLimitType.ORDER_RATIO, null, new BigDecimal("101"), "admin"))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> service.update(PointUsageLimitType.FIXED_AMOUNT, null, null, "admin"))
                .isInstanceOf(InvalidPointStateException.class);

        verify(port, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("현재 정책 조회는 포트에 위임한다")
    void currentDelegates() {
        when(port.load()).thenReturn(PointUsageLimit.fixedAmount(new BigDecimal("5000")));

        assertThat(service.current().getLimitAmount()).isEqualByComparingTo("5000");
    }
}
