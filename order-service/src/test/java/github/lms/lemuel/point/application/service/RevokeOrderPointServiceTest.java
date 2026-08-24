package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.RevokeOrderPointUseCase.RevokeOrderPointCommand;
import github.lms.lemuel.point.application.port.in.RevokeOrderPointUseCase.RevokeOrderPointResult;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RevokeOrderPointService 단위 테스트.
 *
 * <p>핵심 규칙 — <b>이미 써 버린 적립분은 회수하지 않는다.</b> 잔고를 음수로 만들거나 다른 적립분에서
 * 빼 오면 고객이 정당하게 가진 포인트를 건드리게 된다.
 */
class RevokeOrderPointServiceTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final Long ORDER_ID = 1001L;
    private static final OffsetDateTime GRANTED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private RevokeOrderPointService service;
    private PointAccount account;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new RevokeOrderPointService(accountPort, lotPort, entryPort, eventPort);

        account = PointAccount.rehydrate(ACCOUNT_ID, 42L, new BigDecimal("5000"), BigDecimal.ZERO,
                new BigDecimal("5000"), PointAccountStatus.ACTIVE, 0L, GRANTED_AT, GRANTED_AT);
        when(accountPort.loadByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountPort.save(any())).thenAnswer(call -> call.getArgument(0));
        when(lotPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(call -> {
            PointEntry entry = call.getArgument(0);
            entry.assignId(902L);
            return entry;
        });
    }

    private void givenEarned(String amount, String remaining, PointLotStatus status) {
        when(entryPort.findAccountIdByReference(PointEntryType.GRANT, "ORDER", "1001"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        PointEntry grant = PointEntry.grant(ACCOUNT_ID, new BigDecimal(amount), "ORDER", "1001", 0,
                List.of(new PointLotConsumption(57L, new BigDecimal(amount))), "order:1001", null);
        when(entryPort.loadByReference(ACCOUNT_ID, PointEntryType.GRANT, "ORDER", "1001"))
                .thenReturn(List.of(grant));
        PointLot lot = PointLot.rehydrate(57L, ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                new BigDecimal(amount), new BigDecimal(remaining), status,
                GRANTED_AT, GRANTED_AT.plusDays(365), "ORDER", "1001", 0L);
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(lot));
    }

    private RevokeOrderPointCommand command() {
        return new RevokeOrderPointCommand(ORDER_ID, "order:1001");
    }

    @Test
    @DisplayName("적립이 없었으면 아무 일도 하지 않는다 — 정책 미설정·미확정 주문의 정상 경로")
    void noEarnHistory_noop() {
        when(entryPort.findAccountIdByReference(PointEntryType.GRANT, "ORDER", "1001"))
                .thenReturn(Optional.empty());

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountPort, never()).loadByIdForUpdate(anyLong());
        verify(entryPort, never()).append(any());
    }

    @Test
    @DisplayName("쓰지 않은 적립분은 전액 회수한다")
    void revokesUnusedEarning() {
        givenEarned("500", "500", PointLotStatus.ACTIVE);

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("4500"));
        verify(eventPort).pointRevoked(any(PointAccount.class), any(PointEntry.class));
    }

    @Test
    @DisplayName("일부만 남았으면 남은 만큼만 회수한다 — 이미 쓴 몫은 회사 손실로 남긴다")
    void revokesOnlyRemaining() {
        givenEarned("500", "200", PointLotStatus.ACTIVE);

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("4800"));
    }

    @Test
    @DisplayName("전액 사용된 적립분은 회수액 0 이고 원장에 0원 엔트리를 만들지 않는다")
    void fullyUsedEarning_noEntry() {
        givenEarned("500", "0", PointLotStatus.EXHAUSTED);

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
        verify(entryPort, never()).append(any());
        verify(eventPort, never()).pointRevoked(any(), any());
    }

    @Test
    @DisplayName("이미 소멸한 적립분도 회수 대상이 아니다")
    void expiredEarning_noRevoke() {
        givenEarned("500", "0", PointLotStatus.EXPIRED);

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(entryPort, never()).append(any());
    }

    @Test
    @DisplayName("같은 주문으로 두 번 회수하면 두 번째는 멱등 단축 반환한다")
    void revoke_isIdempotent() {
        givenEarned("500", "500", PointLotStatus.ACTIVE);
        when(entryPort.existsByReference(ACCOUNT_ID, PointEntryType.REVOKE, "ORDER", "1001")).thenReturn(true);

        RevokeOrderPointResult result = service.revoke(command());

        assertThat(result.revokedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
        verify(entryPort, never()).append(any());
    }
}
