package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointResult;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GrantPointService 단위 테스트.
 *
 * <p>핵심은 두 가지다 — 로트를 먼저 저장해 식별자를 확보한 뒤 원장 배분에 적는가,
 * 그리고 현금 충전 원금과 판촉성 적립이 <b>서로 다른 이벤트</b>로 나가는가(GL 계정이 다르다).
 */
class GrantPointServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private GrantPointService service;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new GrantPointService(accountPort, lotPort, entryPort, eventPort);

        PointAccount account = PointAccount.rehydrate(ACCOUNT_ID, USER_ID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                PointAccountStatus.ACTIVE, 0L, NOW, NOW);
        when(accountPort.openIfAbsent(USER_ID)).thenReturn(account);
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(accountPort.save(any())).thenAnswer(call -> call.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(call -> {
            PointEntry entry = call.getArgument(0);
            entry.assignId(100L);
            return entry;
        });
        when(lotPort.save(any())).thenAnswer(call -> {
            PointLot lot = call.getArgument(0);
            lot.assignId(55L);
            return lot;
        });
    }

    private GrantPointCommand command(PointLotOrigin origin, String amount) {
        return new GrantPointCommand(USER_ID, new BigDecimal(amount), origin,
                "CHARGE", "chg-1", NOW.plusDays(365), "system", null);
    }

    @Test
    @DisplayName("적립하면 로트가 발급되고 원장 배분이 그 로트를 가리킨다")
    void grant_issuesLotAndLinksEntry() {
        GrantPointResult result = service.grant(command(PointLotOrigin.ORDER_EARN, "1000"));

        assertThat(result.lotId()).isEqualTo(55L);
        assertThat(result.grantedAmount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(result.remainingBalance()).isEqualByComparingTo(new BigDecimal("1000"));

        ArgumentCaptor<PointEntry> captor = ArgumentCaptor.forClass(PointEntry.class);
        verify(entryPort).append(captor.capture());
        PointEntry entry = captor.getValue();
        assertThat(entry.getType()).isEqualTo(PointEntryType.GRANT);
        assertThat(entry.getAllocations()).hasSize(1);
        assertThat(entry.getAllocations().get(0).lotId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("현금 충전 원금은 charged 이벤트로 나간다 — DR CASH / CR POINT_LIABILITY")
    void chargePrincipalPublishesCharged() {
        service.grant(command(PointLotOrigin.CHARGE_PRINCIPAL, "10000"));

        verify(eventPort).pointCharged(any(PointAccount.class), any(PointLot.class), anyString());
        verify(eventPort, never()).pointGranted(any(), any());
    }

    @Test
    @DisplayName("충전 보너스는 granted 이벤트로 나간다 — 판촉비 인식이라 계정이 다르다")
    void chargeBonusPublishesGranted() {
        service.grant(command(PointLotOrigin.CHARGE_BONUS, "800"));

        verify(eventPort).pointGranted(any(PointAccount.class), any(PointLot.class));
        verify(eventPort, never()).pointCharged(any(), any(), anyString());
    }

    @Test
    @DisplayName("같은 참조로 두 번 적립하면 두 번째는 멱등 단축 반환한다")
    void grant_isIdempotent() {
        when(entryPort.existsByReference(ACCOUNT_ID, PointEntryType.GRANT, "CHARGE", "chg-1")).thenReturn(true);

        GrantPointResult result = service.grant(command(PointLotOrigin.ORDER_EARN, "1000"));

        assertThat(result.lotId()).isNull();
        verify(lotPort, never()).save(any());
        verify(entryPort, never()).append(any());
    }
}
