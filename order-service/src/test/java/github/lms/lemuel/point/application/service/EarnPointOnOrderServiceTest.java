package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.EarnPointOnOrderUseCase.EarnPointCommand;
import github.lms.lemuel.point.application.port.in.EarnPointOnOrderUseCase.EarnPointResult;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointResult;
import github.lms.lemuel.point.application.port.out.PointEarnPolicyPort;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EarnPointOnOrderService 단위 테스트.
 *
 * <p>가장 중요한 건 <b>정책이 없으면 아무 일도 하지 않는다</b>는 것이다 — 기본 적립률로 폴백하면
 * 정책 표를 채우기 전부터 회사가 판촉비를 쓰게 된다.
 */
class EarnPointOnOrderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private PointEarnPolicyPort policyPort;
    private GrantPointUseCase grantPointUseCase;
    private EarnPointOnOrderService service;

    @BeforeEach
    void setUp() {
        policyPort = mock(PointEarnPolicyPort.class);
        grantPointUseCase = mock(GrantPointUseCase.class);
        service = new EarnPointOnOrderService(policyPort, grantPointUseCase);

        when(grantPointUseCase.grant(any())).thenReturn(
                new GrantPointResult(100L, 55L, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private void givenPolicy(String rate, int validityDays) {
        when(policyPort.loadCandidates(any(), any(), any())).thenReturn(List.of(
                PointEarnPolicy.of(PointEarnScope.GLOBAL, "*", new BigDecimal(rate), validityDays,
                        TODAY.minusDays(1), null, "테스트 정책", "admin")));
    }

    private EarnPointCommand command(String amount) {
        return new EarnPointCommand(1001L, 42L, new BigDecimal(amount), TODAY, "order:1001");
    }

    @Test
    @DisplayName("정책이 없으면 적립하지 않는다 — 기본 적립률로 폴백하지 않는다")
    void noPolicy_noEarn() {
        when(policyPort.loadCandidates(any(), any(), any())).thenReturn(List.of());

        EarnPointResult result = service.earn(command("50000"));

        assertThat(result.earnedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.lotId()).isNull();
        verify(grantPointUseCase, never()).grant(any());
    }

    @Test
    @DisplayName("정책이 있으면 주문금액 × 적립률로 적립한다")
    void earnsByPolicyRate() {
        givenPolicy("0.01000", 365);

        EarnPointResult result = service.earn(command("50000"));

        assertThat(result.earnedAmount()).isEqualByComparingTo(new BigDecimal("500"));

        ArgumentCaptor<GrantPointCommand> captor = ArgumentCaptor.forClass(GrantPointCommand.class);
        verify(grantPointUseCase).grant(captor.capture());
        GrantPointCommand granted = captor.getValue();
        assertThat(granted.origin()).isEqualTo(PointLotOrigin.ORDER_EARN);
        assertThat(granted.userId()).isEqualTo(42L);
        assertThat(granted.amount()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("멱등 키는 (ORDER, orderId) 다 — 같은 주문이 두 번 확정돼도 로트는 하나다")
    void idempotencyKeyIsOrderReference() {
        givenPolicy("0.01000", 365);

        service.earn(command("50000"));

        ArgumentCaptor<GrantPointCommand> captor = ArgumentCaptor.forClass(GrantPointCommand.class);
        verify(grantPointUseCase).grant(captor.capture());
        assertThat(captor.getValue().referenceType()).isEqualTo("ORDER");
        assertThat(captor.getValue().referenceId()).isEqualTo("1001");
    }

    @Test
    @DisplayName("적립분 만료일은 정책의 유효기간을 따른다")
    void expiryFollowsPolicyValidityDays() {
        givenPolicy("0.01000", 30);

        service.earn(command("50000"));

        ArgumentCaptor<GrantPointCommand> captor = ArgumentCaptor.forClass(GrantPointCommand.class);
        verify(grantPointUseCase).grant(captor.capture());
        assertThat(captor.getValue().expiresAt()).isNotNull();
        assertThat(java.time.Duration.between(
                java.time.OffsetDateTime.now(), captor.getValue().expiresAt()).toDays())
                .isBetween(29L, 30L);
    }

    @Test
    @DisplayName("적립액이 1원 미만이면 로트를 만들지 않는다 (경계값)")
    void belowOneUnit_noEarn() {
        givenPolicy("0.01000", 365);

        EarnPointResult result = service.earn(command("99"));

        assertThat(result.earnedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(grantPointUseCase, never()).grant(any());
    }

    @Test
    @DisplayName("기간이 지난 정책은 적용되지 않는다")
    void expiredPolicyIsIgnored() {
        when(policyPort.loadCandidates(any(), any(), any())).thenReturn(List.of(
                PointEarnPolicy.of(PointEarnScope.GLOBAL, "*", new BigDecimal("0.01000"), 365,
                        TODAY.minusDays(30), TODAY.minusDays(1), "만료된 정책", "admin")));

        EarnPointResult result = service.earn(command("50000"));

        assertThat(result.earnedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(grantPointUseCase, never()).grant(any());
    }
}
