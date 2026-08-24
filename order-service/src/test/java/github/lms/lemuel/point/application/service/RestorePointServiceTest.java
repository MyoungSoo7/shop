package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.RestorePointUseCase.RestorePointCommand;
import github.lms.lemuel.point.application.port.in.RestorePointUseCase.RestorePointResult;
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
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * RestorePointService 단위 테스트.
 *
 * <p>복원은 "마지막에 쓴 로트부터 되돌린다"(LIFO)는 규칙과, 원 로트가 죽었을 때 <b>원래 가졌던
 * 유효기간 길이</b>로 대체 로트를 발급하는 규칙이 핵심이다. 기본 정책값으로 발급하면 고객에게
 * 유효기간을 덤으로 주게 된다.
 */
class RestorePointServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private RestorePointService service;
    private PointAccount account;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new RestorePointService(accountPort, lotPort, entryPort, eventPort);

        account = PointAccount.rehydrate(ACCOUNT_ID, USER_ID,
                new BigDecimal("0"), BigDecimal.ZERO, new BigDecimal("0"),
                PointAccountStatus.ACTIVE, 0L, GRANTED_AT, GRANTED_AT);
        when(accountPort.loadByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(entryPort.findAccountIdByReference(PointEntryType.USE, "PAYMENT_TENDER", "55"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accountPort.save(any())).thenAnswer(call -> call.getArgument(0));
        when(lotPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(call -> {
            PointEntry entry = call.getArgument(0);
            entry.assignId(200L);
            return entry;
        });
    }

    private PointLot lot(long id, String original, String remaining, PointLotStatus status,
                         OffsetDateTime expiresAt) {
        return PointLot.rehydrate(id, ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                new BigDecimal(original), new BigDecimal(remaining), status,
                GRANTED_AT, expiresAt, "ORDER", "ref-" + id, 0L);
    }

    private void givenUseHistory(PointLotConsumption... allocations) {
        BigDecimal total = java.util.Arrays.stream(allocations)
                .map(PointLotConsumption::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        PointEntry use = PointEntry.use(ACCOUNT_ID, total, "PAYMENT_TENDER", "55", 0,
                List.of(allocations), "user:42");
        when(entryPort.loadByReference(ACCOUNT_ID, PointEntryType.USE, "PAYMENT_TENDER", "55"))
                .thenReturn(List.of(use));
    }

    private RestorePointCommand command(String amount) {
        return new RestorePointCommand(new BigDecimal(amount),
                "PAYMENT_TENDER", "55", "PAYMENT_TENDER_REFUND", "55", "system");
    }

    @Test
    @DisplayName("마지막에 소비한 로트부터 되돌린다 — 유효기간이 긴 포인트를 먼저 돌려준다")
    void restoresInReverseConsumptionOrder() {
        givenUseHistory(new PointLotConsumption(1L, new BigDecimal("300")),
                new PointLotConsumption(2L, new BigDecimal("700")));
        PointLot second = lot(2L, "700", "0", PointLotStatus.EXHAUSTED, GRANTED_AT.plusDays(60));
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(second));

        RestorePointResult result = service.restore(command("500"));

        assertThat(result.restoredAmount()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(second.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("한 로트로 모자라면 그 앞 로트까지 거슬러 되돌린다")
    void restoreSpansMultipleLots() {
        givenUseHistory(new PointLotConsumption(1L, new BigDecimal("300")),
                new PointLotConsumption(2L, new BigDecimal("700")));
        PointLot first = lot(1L, "300", "0", PointLotStatus.EXHAUSTED, GRANTED_AT.plusDays(30));
        PointLot second = lot(2L, "700", "0", PointLotStatus.EXHAUSTED, GRANTED_AT.plusDays(60));
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(first, second));

        service.restore(command("1000"));

        assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("700"));
        assertThat(first.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("소멸된 로트는 되살리지 않고 원래 유효기간 길이로 대체 로트를 발급한다")
    void issuesReplacementLotForExpiredOrigin() {
        givenUseHistory(new PointLotConsumption(1L, new BigDecimal("1000")));
        PointLot expired = lot(1L, "1000", "0", PointLotStatus.EXPIRED, GRANTED_AT.plusDays(30));
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(expired));
        when(lotPort.save(any())).thenAnswer(call -> {
            PointLot replacement = call.getArgument(0);
            replacement.assignId(99L);
            return replacement;
        });

        service.restore(command("1000"));

        ArgumentCaptor<PointLot> captor = ArgumentCaptor.forClass(PointLot.class);
        verify(lotPort).save(captor.capture());
        PointLot replacement = captor.getValue();
        assertThat(replacement.getOrigin()).isEqualTo(PointLotOrigin.REFUND_RESTORE);
        // 원 로트의 유효기간 길이(30일)를 지금부터 다시 부여한다.
        assertThat(java.time.Duration.between(replacement.getGrantedAt(), replacement.getExpiresAt())
                .toDays()).isEqualTo(30);

        ArgumentCaptor<PointEntry> entryCaptor = ArgumentCaptor.forClass(PointEntry.class);
        verify(entryPort).append(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getAllocations().get(0).lotId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("원 사용 이력이 없으면 복원할 수 없다")
    void rejectsRestoreWithoutUseHistory() {
        when(entryPort.findAccountIdByReference(PointEntryType.USE, "PAYMENT_TENDER", "55"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(command("100")))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("원 사용액을 넘는 복원은 거절한다")
    void rejectsRestoreOverUsedAmount() {
        givenUseHistory(new PointLotConsumption(1L, new BigDecimal("300")));
        PointLot first = lot(1L, "300", "0", PointLotStatus.EXHAUSTED, GRANTED_AT.plusDays(30));
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(first));

        assertThatThrownBy(() -> service.restore(command("400")))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("같은 환불 참조로 두 번 복원하면 두 번째는 멱등 단축 반환한다")
    void restore_isIdempotent() {
        when(entryPort.existsByReference(ACCOUNT_ID, PointEntryType.RESTORE, "PAYMENT_TENDER_REFUND", "55"))
                .thenReturn(true);

        RestorePointResult result = service.restore(command("500"));

        assertThat(result.entryId()).isNull();
        verify(entryPort, never()).append(any());
        assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("복원 이벤트를 발행한다 — 사용의 대칭 분개가 필요하다")
    void publishesRestoredEvent() {
        givenUseHistory(new PointLotConsumption(1L, new BigDecimal("300")));
        PointLot first = lot(1L, "300", "0", PointLotStatus.EXHAUSTED, GRANTED_AT.plusDays(30));
        when(lotPort.loadByIds(anyCollection())).thenReturn(List.of(first));

        service.restore(command("300"));

        verify(eventPort).pointRestored(any(PointAccount.class), any(PointEntry.class));
    }
}
