package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointCommand;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointResult;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointLot;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExpirePointLotsService 단위 테스트.
 *
 * <p>고객 재산을 지우는 배치라 dry-run 이 실제로 아무것도 바꾸지 않는지를 먼저 본다.
 * 그리고 계정당 락을 한 번만 잡는지(로트마다 잡으면 경합이 커진다)를 확인한다.
 */
class ExpirePointLotsServiceTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2027-01-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private ExpirePointLotsService service;
    private PointAccount account;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new ExpirePointLotsService(accountPort, lotPort, entryPort, eventPort);

        account = PointAccount.rehydrate(ACCOUNT_ID, 42L,
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                PointAccountStatus.ACTIVE, 0L, GRANTED_AT, GRANTED_AT);
        when(accountPort.loadByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountPort.save(any())).thenAnswer(call -> call.getArgument(0));
        when(lotPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.append(any())).thenAnswer(call -> {
            PointEntry entry = call.getArgument(0);
            entry.assignId(300L);
            return entry;
        });
    }

    private PointLot lot(long id, String remaining) {
        PointLot lot = PointLot.rehydrate(id, ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                new BigDecimal(remaining), new BigDecimal(remaining), PointLotStatus.ACTIVE,
                GRANTED_AT, GRANTED_AT.plusDays(30), "ORDER", "ref-" + id, 0L);
        return lot;
    }

    private ExpirePointCommand command(boolean dryRun) {
        return new ExpirePointCommand(NOW, 100, dryRun, "batch");
    }

    @Test
    @DisplayName("소멸 대상이 없으면 아무 일도 하지 않는다")
    void noExpiredLots() {
        when(lotPort.loadExpired(NOW, 100)).thenReturn(List.of());

        ExpirePointResult result = service.expire(command(false));

        assertThat(result.lotCount()).isZero();
        assertThat(result.forfeitedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountPort, never()).loadByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("dry-run 은 규모만 알려 주고 아무것도 바꾸지 않는다")
    void dryRunChangesNothing() {
        PointLot first = lot(1L, "400");
        PointLot second = lot(2L, "600");
        when(lotPort.loadExpired(NOW, 100)).thenReturn(List.of(first, second));

        ExpirePointResult result = service.expire(command(true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.lotCount()).isEqualTo(2);
        assertThat(result.forfeitedTotal()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(first.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("1000"));
        verify(accountPort, never()).save(any());
        verify(entryPort, never()).append(any());
    }

    @Test
    @DisplayName("실행하면 로트가 EXPIRED 로 닫히고 잔고가 줄며 로트마다 원장이 남는다")
    void expireClosesLotsAndRecordsLedger() {
        PointLot first = lot(1L, "400");
        PointLot second = lot(2L, "600");
        when(lotPort.loadExpired(NOW, 100)).thenReturn(List.of(first, second));

        ExpirePointResult result = service.expire(command(false));

        assertThat(result.dryRun()).isFalse();
        assertThat(result.forfeitedTotal()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(first.getStatus()).isEqualTo(PointLotStatus.EXPIRED);
        assertThat(second.getStatus()).isEqualTo(PointLotStatus.EXPIRED);
        assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(entryPort, times(2)).append(any());
        verify(eventPort, times(2)).pointExpired(any(), any(), any());
    }

    @Test
    @DisplayName("같은 계정의 로트가 여러 개여도 락은 한 번만 잡는다")
    void locksAccountOncePerBatch() {
        when(lotPort.loadExpired(NOW, 100)).thenReturn(List.of(lot(1L, "100"), lot(2L, "200")));

        service.expire(command(false));

        verify(accountPort, times(1)).loadByIdForUpdate(ACCOUNT_ID);
    }

    @Test
    @DisplayName("잔량 0 로트는 닫되 0원 엔트리를 만들지 않는다")
    void zeroRemainingLotProducesNoEntry() {
        PointLot exhausted = PointLot.rehydrate(3L, ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                new BigDecimal("500"), BigDecimal.ZERO, PointLotStatus.ACTIVE,
                GRANTED_AT, GRANTED_AT.plusDays(30), "ORDER", "ref-3", 0L);
        when(lotPort.loadExpired(NOW, 100)).thenReturn(List.of(exhausted));

        ExpirePointResult result = service.expire(command(false));

        assertThat(exhausted.getStatus()).isEqualTo(PointLotStatus.EXPIRED);
        assertThat(result.forfeitedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(entryPort, never()).append(any());
    }
}
