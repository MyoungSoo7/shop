package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.HoldPointUseCase.HoldCommand;
import github.lms.lemuel.point.application.port.in.HoldPointUseCase.HoldResult;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointHoldPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.PointHoldStatus;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 포인트 선점 유스케이스 — 잠그고, 확정하고, 푸는 세 경로.
 *
 * <p>이 테스트가 지키는 것은 <b>입금 vs 만료 경합</b>이다. 만료 배치가 먼저 풀어 버린 선점을
 * 뒤늦은 입금이 확정하면 이미 가용으로 돌아간 포인트를 한 번 더 쓰게 되고, 반대로 확정된 선점을
 * 배치가 풀면 없는 잔고가 생긴다. 둘 다 여기서 막힌다.
 */
class HoldPointServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final String REF_TYPE = "PAYMENT_TENDER";
    private static final String REF_ID = "77";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-21T10:00:00+09:00");

    private PointAccountPort accountPort;
    private PointHoldPort holdPort;
    private PointLotPort lotPort;
    private PointEntryPort entryPort;
    private PublishPointEventPort eventPort;
    private HoldPointService service;

    private PointAccount account;
    private final List<PointHold> savedHolds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accountPort = mock(PointAccountPort.class);
        holdPort = mock(PointHoldPort.class);
        lotPort = mock(PointLotPort.class);
        entryPort = mock(PointEntryPort.class);
        eventPort = mock(PublishPointEventPort.class);
        service = new HoldPointService(accountPort, holdPort,
                new PointSpendRecorder(accountPort, lotPort, entryPort, eventPort));

        account = PointAccount.rehydrate(ACCOUNT_ID, USER_ID,
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("10000"),
                github.lms.lemuel.point.domain.PointAccountStatus.ACTIVE, 0L, NOW, NOW);

        when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        when(accountPort.loadByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountPort.save(any())).thenAnswer(c -> c.getArgument(0));
        when(holdPort.save(any())).thenAnswer(c -> {
            PointHold h = c.getArgument(0);
            if (h.getId() == null) {
                h.assignId(500L);
            }
            savedHolds.add(h);
            return h;
        });
        when(holdPort.findByReference(anyString(), anyString())).thenReturn(Optional.empty());
        // 확정·해제는 계정 잠금을 먼저 얻어야 해서 계정 id 를 스칼라로 먼저 묻는다
        // (선점 엔티티를 잠금 전에 적재하면 잠금 뒤 재조회가 낡은 상태를 돌려준다).
        when(holdPort.findAccountIdByReference(anyString(), anyString()))
                .thenReturn(Optional.of(ACCOUNT_ID));
    }

    /** 확정 경로가 쓰는 로트·원장 목 — 확정 테스트에서만 필요하다. */
    private void stubSpendPath() {
        when(lotPort.loadConsumable(ACCOUNT_ID)).thenAnswer(c -> new ArrayList<>(List.of(
                PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN, new BigDecimal("10000"),
                        NOW, null, "SEED", "1"))));
        when(lotPort.saveAll(any())).thenAnswer(c -> c.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(c -> {
            PointEntry e = c.getArgument(0);
            e.assignId(900L);
            return e;
        });
    }

    private PointHold activeHold() {
        PointHold hold = PointHold.place(ACCOUNT_ID, new BigDecimal("3000"), REF_TYPE, REF_ID, NOW);
        hold.assignId(500L);
        return hold;
    }

    @Nested
    @DisplayName("선점")
    class Hold {

        @Test
        @DisplayName("가용에서 빼서 잠그고 선점 레코드를 남긴다")
        void locksBalance() {
            HoldResult result = service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("3000"), REF_TYPE, REF_ID));

            assertThat(account.getAvailable()).isEqualByComparingTo("7000");
            assertThat(account.getLocked()).isEqualByComparingTo("3000");
            assertThat(account.getTotal()).isEqualByComparingTo("10000");
            assertThat(result.remainingAvailable()).isEqualByComparingTo("7000");
            assertThat(savedHolds).singleElement()
                    .satisfies(h -> assertThat(h.getStatus()).isEqualTo(PointHoldStatus.ACTIVE));
        }

        /** 결제 재시도가 선점을 두 벌 만들면 같은 잔고를 두 번 잠근다. */
        @Test
        @DisplayName("같은 근거로 다시 부르면 기존 선점을 돌려준다 — 두 번 잠그지 않는다")
        void isIdempotent() {
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(activeHold()));

            HoldResult result = service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("3000"), REF_TYPE, REF_ID));

            assertThat(result.holdId()).isEqualTo(500L);
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            verify(holdPort, never()).save(any());
        }

        @Test
        @DisplayName("잔액이 모자라면 거절 — 결제가 실패해야 한다")
        void rejectsWhenInsufficient() {
            assertThatThrownBy(() -> service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("10001"), REF_TYPE, REF_ID)))
                    .isInstanceOf(InsufficientPointException.class);

            verify(holdPort, never()).save(any());
        }

        @Test
        @DisplayName("계정이 없으면 잔액 부족으로 본다")
        void noAccount() {
            when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("1000"), REF_TYPE, REF_ID)))
                    .isInstanceOf(InsufficientPointException.class);
        }
    }

    @Nested
    @DisplayName("확정")
    class Capture {

        @Test
        @DisplayName("잠근 몫을 실제로 쓴다 — 총액이 줄고 USE 엔트리가 남는다")
        void spendsAndRecords() {
            account.hold(new BigDecimal("3000"));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(activeHold()));
            stubSpendPath();

            service.capture(REF_TYPE, REF_ID, "user:42");

            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("7000");
            assertThat(account.getAvailable()).isEqualByComparingTo("7000");
            verify(entryPort).append(any());
            verify(eventPort).pointUsed(any(), any());
        }

        @Test
        @DisplayName("이미 확정된 선점은 멱등 no-op — 두 번 차감하지 않는다")
        void alreadyCapturedIsNoOp() {
            PointHold hold = activeHold();
            hold.capture(NOW.plusHours(1));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(hold));

            service.capture(REF_TYPE, REF_ID, "user:42");

            assertThat(account.getTotal()).isEqualByComparingTo("10000");
            verify(entryPort, never()).append(any());
        }

        /** 경합의 핵심 — 배치가 먼저 풀어 버린 선점을 뒤늦은 입금이 확정하면 이중 사용이 된다. */
        @Test
        @DisplayName("이미 풀린 선점은 확정할 수 없다 — 만료가 먼저 이긴 경우")
        void cannotCaptureReleased() {
            PointHold hold = activeHold();
            hold.expire(NOW.plusHours(50));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(hold));

            assertThatThrownBy(() -> service.capture(REF_TYPE, REF_ID, "user:42"))
                    .isInstanceOf(InvalidPointStateException.class);

            assertThat(account.getTotal()).isEqualByComparingTo("10000");
        }

        /** 조용히 넘기면 고객 포인트를 받지 않은 채 주문이 확정된다 — 회사가 손해를 본다. */
        @Test
        @DisplayName("선점이 아예 없으면 예외 — 안 받은 포인트를 받은 셈 칠 수 없다")
        void missingHoldThrows() {
        when(holdPort.findAccountIdByReference(anyString(), anyString()))
                .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.capture(REF_TYPE, REF_ID, "user:42"))
                    .isInstanceOf(PointInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("잠금을 풀어 가용으로 되돌린다 — 총액은 그대로")
        void returnsToAvailable() {
            account.hold(new BigDecimal("3000"));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(activeHold()));

            service.release(REF_TYPE, REF_ID, true);

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("10000");
            assertThat(savedHolds).singleElement()
                    .satisfies(h -> assertThat(h.getStatus()).isEqualTo(PointHoldStatus.EXPIRED));
        }

        @Test
        @DisplayName("기한 경과가 아니면 RELEASED 로 남긴다 — 왜 풀렸는지가 운영 판단을 가른다")
        void explicitReleaseKeepsReason() {
            account.hold(new BigDecimal("3000"));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(activeHold()));

            service.release(REF_TYPE, REF_ID, false);

            assertThat(savedHolds).singleElement()
                    .satisfies(h -> assertThat(h.getStatus()).isEqualTo(PointHoldStatus.RELEASED));
        }

        /** 여기서 막으면 미입금 만료 배치가 함께 멈춰 재고까지 못 되돌린다. */
        @Test
        @DisplayName("선점이 없으면 조용히 넘어간다 — 만료 배치를 세우지 않는다")
        void missingHoldIsNoOp() {
        when(holdPort.findAccountIdByReference(anyString(), anyString()))
                .thenReturn(Optional.empty());
            service.release(REF_TYPE, REF_ID, true);

            assertThat(account.getTotal()).isEqualByComparingTo("10000");
            verify(accountPort, never()).save(any());
        }

        @Test
        @DisplayName("이미 풀린 선점을 또 풀지 않는다 — 배치 재실행이 잔고를 늘리면 안 된다")
        void alreadyReleasedIsNoOp() {
            PointHold hold = activeHold();
            hold.release(NOW.plusHours(1));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(hold));

            service.release(REF_TYPE, REF_ID, true);

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            verify(accountPort, never()).save(any());
        }

        /** 반대 방향 경합 — 입금이 먼저 이겼는데 배치가 뒤늦게 풀면 없는 잔고가 생긴다. */
        @Test
        @DisplayName("확정된 선점은 풀 수 없다 — 입금이 먼저 이긴 경우")
        void cannotReleaseCaptured() {
            PointHold hold = activeHold();
            hold.capture(NOW.plusHours(1));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(Optional.of(hold));

            assertThatThrownBy(() -> service.release(REF_TYPE, REF_ID, true))
                    .isInstanceOf(InvalidPointStateException.class);

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
        }
    }
}
