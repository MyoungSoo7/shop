package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.DeductPointUseCase.DeductPointCommand;
import github.lms.lemuel.point.application.port.in.DeductPointUseCase.DeductPointResult;
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
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수기 차감 서비스 — 수기 지급의 역방향.
 *
 * <p>여기서 지키는 것 셋:
 * <ol>
 *   <li><b>비관적 락</b> — 잔액 확인과 차감 사이에 결제가 끼어들면 같은 포인트를 두 번 뺀다.
 *   <li><b>멱등</b> — 같은 참조로 두 번 눌러도 한 번만 빠진다.
 *   <li><b>로트까지 줄인다</b> — 잔고만 줄이면 3자 대조가 즉시 깨진다.
 * </ol>
 */
class DeductPointServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    private PointAccountPort accountPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private DeductPointService service;

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new DeductPointService(accountPort, lotPort, entryPort, eventPort);

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

    private PointAccount accountWith(String available, PointAccountStatus status) {
        return PointAccount.rehydrate(ACCOUNT_ID, USER_ID, new BigDecimal(available),
                BigDecimal.ZERO, new BigDecimal(available), status, 0L, GRANTED_AT, GRANTED_AT);
    }

    private PointLot lot(long id, String amount, OffsetDateTime expiresAt) {
        PointLot lot = PointLot.issue(ACCOUNT_ID, PointLotOrigin.MANUAL_GRANT, new BigDecimal(amount),
                GRANTED_AT, expiresAt, "MANUAL", "ref-" + id);
        lot.assignId(id);
        return lot;
    }

    private DeductPointCommand command(String amount) {
        return new DeductPointCommand(USER_ID, new BigDecimal(amount), "recall-1",
                "오지급 회수", "admin:1");
    }

    @Nested
    @DisplayName("정상 차감")
    class Deduct {

        @Test
        @DisplayName("잔고와 로트를 함께 줄이고 REVOKE 엔트리를 남긴다")
        void deductsBalanceAndLots() {
            PointAccount account = accountWith("5000", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(
                    lot(1L, "2000", GRANTED_AT.plusDays(10)),
                    lot(2L, "3000", GRANTED_AT.plusDays(20))));

            DeductPointResult result = service.deduct(command("2500"));

            assertThat(result.deductedAmount()).isEqualByComparingTo("2500");
            assertThat(result.remainingBalance()).isEqualByComparingTo("2500");
            assertThat(account.getAvailable()).isEqualByComparingTo("2500");

            ArgumentCaptor<PointEntry> captor = ArgumentCaptor.forClass(PointEntry.class);
            verify(entryPort).append(captor.capture());
            PointEntry entry = captor.getValue();
            assertThat(entry.getType()).isEqualTo(PointEntryType.REVOKE);
            assertThat(entry.getReferenceType()).isEqualTo("MANUAL");
            assertThat(entry.getMemo()).isEqualTo("오지급 회수");
        }

        @Test
        @DisplayName("만료 임박 로트부터 소비한다 — 사용과 같은 순서다")
        void consumesEarliestExpiryFirst() {
            PointAccount account = accountWith("5000", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            PointLot soon = lot(1L, "2000", GRANTED_AT.plusDays(10));
            PointLot later = lot(2L, "3000", GRANTED_AT.plusDays(20));
            when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(later, soon));

            service.deduct(command("2000"));

            assertThat(soon.getRemainingAmount()).isEqualByComparingTo("0");
            assertThat(later.getRemainingAmount()).isEqualByComparingTo("3000");
        }

        @Test
        @DisplayName("잔고를 잠그고 읽는다 — 결제와 겹치면 같은 포인트를 두 번 뺀다")
        void usesPessimisticLock() {
            PointAccount account = accountWith("1000", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "1000", null)));

            service.deduct(command("500"));

            verify(accountPort).loadForUpdate(USER_ID);
            verify(accountPort, never()).load(anyLong());
        }

        @Test
        @DisplayName("정지 계정에서도 차감된다 — 부정 적립은 계정을 잠근 뒤 거둬들인다")
        void worksOnSuspendedAccount() {
            PointAccount account = accountWith("1000", PointAccountStatus.SUSPENDED);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "1000", null)));

            DeductPointResult result = service.deduct(command("400"));

            assertThat(result.remainingBalance()).isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("거절")
    class Rejection {

        @Test
        @DisplayName("계정이 없으면 잔액 부족으로 거절한다")
        void missingAccount() {
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deduct(command("100")))
                    .isInstanceOf(InsufficientPointException.class);
        }

        @Test
        @DisplayName("잔액을 넘는 차감은 거절하고 아무것도 저장하지 않는다")
        void overBalance() {
            PointAccount account = accountWith("100", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.deduct(command("101")))
                    .isInstanceOf(InsufficientPointException.class);

            verify(entryPort, never()).append(any());
            verify(accountPort, never()).save(any());
        }

        @Test
        @DisplayName("사유가 비면 거절한다 — 근거 없는 감액은 원장에 들어갈 수 없다")
        void blankReason() {
            PointAccount account = accountWith("1000", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            when(lotPort.loadConsumable(ACCOUNT_ID)).thenReturn(List.of(lot(1L, "1000", null)));

            assertThatThrownBy(() -> service.deduct(new DeductPointCommand(
                    USER_ID, new BigDecimal("100"), "recall-1", "   ", "admin:1")))
                    .isInstanceOf(PointInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 참조로 두 번 눌러도 한 번만 빠진다")
        void shortCircuitsOnDuplicate() {
            PointAccount account = accountWith("1000", PointAccountStatus.ACTIVE);
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
            when(entryPort.existsByReference(ACCOUNT_ID, PointEntryType.REVOKE, "MANUAL", "recall-1"))
                    .thenReturn(true);

            DeductPointResult result = service.deduct(command("500"));

            assertThat(result.entryId()).isNull();
            assertThat(result.remainingBalance()).isEqualByComparingTo("1000");
            assertThat(account.getAvailable()).isEqualByComparingTo("1000");
            verify(entryPort, never()).append(any());
            verify(accountPort, never()).save(any());
        }
    }
}
