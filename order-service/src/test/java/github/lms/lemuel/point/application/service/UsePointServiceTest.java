package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.UsePointUseCase.UsePointCommand;
import github.lms.lemuel.point.application.port.in.UsePointUseCase.UsePointResult;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UsePointService 단위 테스트.
 *
 * <p>사용 경로는 이 도메인에서 가장 위험한 자리다 — 잔액 확인과 차감이 원자적이지 않으면
 * 같은 포인트가 두 번 쓰인다. 여기서는 락 획득 경로를 쓰는지, 로트 소비 상세가 원장에 남는지,
 * 부족·정지 상황을 정확히 거절하는지를 본다.
 */
class UsePointServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private UsePointService service;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        // 로트·원장·이벤트는 PointSpendRecorder 가 맡는다. 목을 그대로 물린 실제 recorder 를 쓰면
        // 이 테스트가 보던 상호작용(소비 순서·엔트리·이벤트)을 그대로 계속 본다.
        service = new UsePointService(accountPort,
                new PointSpendRecorder(accountPort, lotPort, entryPort, eventPort));

        when(accountPort.save(any())).thenAnswer(call -> call.getArgument(0));
        when(lotPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(call -> {
            PointEntry entry = call.getArgument(0);
            entry.assignId(100L);
            return entry;
        });
    }

    private PointAccount accountWith(String available) {
        PointAccount account = PointAccount.rehydrate(ACCOUNT_ID, USER_ID,
                new BigDecimal(available), BigDecimal.ZERO, new BigDecimal(available),
                github.lms.lemuel.point.domain.PointAccountStatus.ACTIVE, 0L, GRANTED_AT, GRANTED_AT);
        return account;
    }

    private PointLot lot(long id, String amount, OffsetDateTime expiresAt) {
        PointLot lot = PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN, new BigDecimal(amount),
                GRANTED_AT, expiresAt, "ORDER", "ref-" + id);
        lot.assignId(id);
        return lot;
    }

    private UsePointCommand command(String amount) {
        return new UsePointCommand(USER_ID, new BigDecimal(amount), "PAYMENT_TENDER", "55", "user:42");
    }

    @Test
    @DisplayName("사용하면 잔고가 줄고 로트 소비 상세가 원장에 남는다")
    void use_deductsAndRecordsLedger() {
        PointAccount account = accountWith("5000");
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(
                lot(1L, "2000", GRANTED_AT.plusDays(10)),
                lot(2L, "3000", GRANTED_AT.plusDays(20))));

        UsePointResult result = service.use(command("2500"));

        assertThat(result.usedAmount()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(result.remainingBalance()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("2500"));

        org.mockito.ArgumentCaptor<PointEntry> captor =
                org.mockito.ArgumentCaptor.forClass(PointEntry.class);
        verify(entryPort).append(captor.capture());
        PointEntry appended = captor.getValue();
        assertThat(appended.getType()).isEqualTo(PointEntryType.USE);
        assertThat(appended.getAmount()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(appended.getAllocations()).hasSize(2);
        assertThat(appended.getAllocations().get(0).lotId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("잔액을 넘는 사용은 거절하고 아무것도 저장하지 않는다")
    void use_rejectsInsufficientBalance() {
        PointAccount account = accountWith("1000");
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "1000", null)));

        assertThatThrownBy(() -> service.use(command("1001")))
                .isInstanceOf(InsufficientPointException.class);

        verify(entryPort, never()).append(any());
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("계정이 없으면 잔액 부족과 같은 결과다 — 없는 계정은 잔액 0")
    void use_missingAccountIsInsufficient() {
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.use(command("100")))
                .isInstanceOf(InsufficientPointException.class);
    }

    @Test
    @DisplayName("정지 계정은 사용할 수 없다")
    void use_rejectedWhenSuspended() {
        PointAccount account = accountWith("5000");
        account.suspend();
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "5000", null)));

        assertThatThrownBy(() -> service.use(command("100")))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("같은 참조로 두 번 사용하면 두 번째는 멱등 단축 반환한다 — 이중 차감 금지")
    void use_isIdempotentOnSameReference() {
        PointAccount account = accountWith("5000");
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(entryPort.existsByReference(ACCOUNT_ID, PointEntryType.USE, "PAYMENT_TENDER", "55"))
                .thenReturn(true);

        UsePointResult result = service.use(command("2500"));

        assertThat(result.usedAmount()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
        verify(entryPort, never()).append(any());
    }

    @Test
    @DisplayName("소비된 로트는 저장되고 소진된 로트는 EXHAUSTED 로 남는다")
    void use_persistsConsumedLots() {
        PointAccount account = accountWith("2000");
        PointLot first = lot(1L, "2000", GRANTED_AT.plusDays(10));
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(first));

        service.use(command("2000"));

        assertThat(first.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
        verify(lotPort).saveAll(any());
    }

    @Test
    @DisplayName("사용 이벤트를 발행한다 — GL 이 포인트 부채를 상계할 수 있어야 한다")
    void use_publishesEvent() {
        PointAccount account = accountWith("5000");
        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "5000", null)));

        service.use(command("1000"));

        verify(eventPort).pointUsed(any(PointAccount.class), any(PointEntry.class));
    }
}
