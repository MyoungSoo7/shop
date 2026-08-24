package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.HoldGiftCardUseCase.HoldCommand;
import github.lms.lemuel.giftcard.application.port.in.HoldGiftCardUseCase.HoldResult;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardHoldPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardHold;
import github.lms.lemuel.giftcard.domain.GiftCardHoldStatus;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기프트카드 선점 유스케이스 — 잠그고, 확정하고, 푸는 세 경로.
 *
 * <p>포인트와 다른 점 둘: 한 근거가 <b>카드 여러 장</b>에 걸치고, 잠긴 금액을 어디에도
 * <b>저장하지 않는다</b>(선점 행의 합으로 계산한다). 그래서 선점 시점에는 카드 잔액이 전혀
 * 움직이지 않고, 확정 시점에야 깎인다.
 */
class HoldGiftCardServiceTest {

    private static final Long USER_ID = 42L;
    private static final String REF_TYPE = "PAYMENT_TENDER";
    private static final String REF_ID = "77";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-22T10:00:00+09:00");

    private GiftCardPort giftCardPort;
    private GiftCardHoldPort holdPort;
    private GiftCardEntryPort entryPort;
    private PublishGiftCardEventPort eventPort;
    private HoldGiftCardService service;

    private GiftCard cardA;
    private GiftCard cardB;
    private final List<GiftCardHold> saved = new ArrayList<>();

    private static GiftCard card(long id, String remaining, String expiresAt) {
        return GiftCard.rehydrate(id, "hash-" + id, "1234", new BigDecimal("10000"),
                new BigDecimal(remaining), GiftCardStatus.REGISTERED, USER_ID,
                NOW.minusDays(30), NOW.minusDays(30), NOW.minusDays(29),
                OffsetDateTime.parse(expiresAt), "admin", null, 0L);
    }

    @BeforeEach
    void setUp() {
        giftCardPort = mock(GiftCardPort.class);
        holdPort = mock(GiftCardHoldPort.class);
        entryPort = mock(GiftCardEntryPort.class);
        eventPort = mock(PublishGiftCardEventPort.class);
        service = new HoldGiftCardService(giftCardPort, holdPort, entryPort, eventPort);

        cardA = card(1L, "5000", "2026-09-30T23:59:59+09:00");
        cardB = card(2L, "5000", "2026-12-31T23:59:59+09:00");

        when(giftCardPort.loadSpendable(USER_ID)).thenReturn(List.of(cardA, cardB));
        when(giftCardPort.loadForUpdate(anyCollection())).thenReturn(List.of(cardA, cardB));
        when(holdPort.activeAmountsByCardIds(anyCollection())).thenReturn(Map.of());
        when(holdPort.findCardIdsByReference(anyString(), anyString())).thenReturn(List.of());
        when(holdPort.findByReference(anyString(), anyString())).thenReturn(List.of());
        when(holdPort.saveHolds(any())).thenAnswer(c -> {
            List<GiftCardHold> holds = c.getArgument(0);
            long next = 500L;
            for (GiftCardHold h : holds) {
                if (h.getId() == null) {
                    h.assignId(next++);
                }
                saved.add(h);
            }
            return holds;
        });
        when(holdPort.save(any())).thenAnswer(c -> c.getArgument(0));
    }

    private GiftCardHold hold(long id, long cardId, String amount) {
        GiftCardHold h = GiftCardHold.place(cardId, new BigDecimal(amount), REF_TYPE, REF_ID, NOW);
        h.assignId(id);
        return h;
    }

    private void stubEntryPath() {
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.append(any())).thenAnswer(c -> c.getArgument(0));
    }

    @Nested
    @DisplayName("선점")
    class Hold {

        /** 카드 잔액이 움직이지 않는 것이 요점이다 — 실제 차감은 입금 확인 시점에만. */
        @Test
        @DisplayName("만료 임박 순으로 여러 장에 걸쳐 잠그되 카드 잔액은 건드리지 않는다")
        void spansCardsWithoutTouchingBalance() {
            HoldResult result = service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("7000"), REF_TYPE, REF_ID));

            assertThat(result.cardCount()).isEqualTo(2);
            assertThat(result.heldAmount()).isEqualByComparingTo("7000");
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getGiftCardId()).isEqualTo(1L);
            assertThat(saved.get(0).getAmount()).isEqualByComparingTo("5000");
            assertThat(saved.get(1).getGiftCardId()).isEqualTo(2L);
            assertThat(saved.get(1).getAmount()).isEqualByComparingTo("2000");
            assertThat(cardA.getRemainingAmount()).isEqualByComparingTo("5000");
            assertThat(cardB.getRemainingAmount()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("이미 잠긴 몫은 다시 잠글 수 없다 — 같은 카드의 이중 사용 차단")
        void heldAmountIsNotAvailableAgain() {
            when(holdPort.activeAmountsByCardIds(anyCollection()))
                    .thenReturn(Map.of(1L, new BigDecimal("5000"), 2L, new BigDecimal("4000")));

            assertThatThrownBy(() -> service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("1001"), REF_TYPE, REF_ID)))
                    .isInstanceOf(InsufficientGiftCardBalanceException.class);

            verify(holdPort, never()).saveHolds(any());
        }

        @Test
        @DisplayName("같은 근거로 다시 부르면 잠그지 않는다 — 결제 재시도가 두 벌을 만들지 않는다")
        void isIdempotent() {
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L, 2L));

            HoldResult result = service.hold(new HoldCommand(
                    USER_ID, new BigDecimal("7000"), REF_TYPE, REF_ID));

            assertThat(result.cardCount()).isEqualTo(2);
            verify(holdPort, never()).saveHolds(any());
        }
    }

    @Nested
    @DisplayName("확정")
    class Capture {

        @Test
        @DisplayName("잠근 장들을 실제로 깎고 카드마다 USE 엔트리를 남긴다")
        void spendsEachHeldCard() {
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L, 2L));
            when(holdPort.findByReference(REF_TYPE, REF_ID))
                    .thenReturn(List.of(hold(500L, 1L, "5000"), hold(501L, 2L, "2000")));
            stubEntryPath();

            service.capture(REF_TYPE, REF_ID, "user:42");

            assertThat(cardA.getRemainingAmount()).isEqualByComparingTo("0");
            assertThat(cardA.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
            assertThat(cardB.getRemainingAmount()).isEqualByComparingTo("3000");
            verify(entryPort, org.mockito.Mockito.times(2)).append(any());
            verify(giftCardPort).saveAll(any());
        }

        @Test
        @DisplayName("이미 확정된 선점은 멱등 no-op")
        void alreadyCapturedIsNoOp() {
            GiftCardHold captured = hold(500L, 1L, "5000");
            captured.capture(NOW.plusHours(1));
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(List.of(captured));

            service.capture(REF_TYPE, REF_ID, "user:42");

            assertThat(cardA.getRemainingAmount()).isEqualByComparingTo("5000");
            verify(entryPort, never()).append(any());
        }

        /** 만료 배치가 먼저 이겼다 — 여기서 확정하면 이미 돌아간 잔액을 한 번 더 쓴다. */
        @Test
        @DisplayName("이미 풀린 선점은 확정할 수 없다")
        void cannotCaptureReleased() {
            GiftCardHold expired = hold(500L, 1L, "5000");
            expired.expire(NOW.plusHours(50));
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(List.of(expired));

            assertThatThrownBy(() -> service.capture(REF_TYPE, REF_ID, "user:42"))
                    .isInstanceOf(InvalidGiftCardStateException.class);

            assertThat(cardA.getRemainingAmount()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("선점이 아예 없으면 예외 — 안 받은 상품권을 받은 셈 칠 수 없다")
        void missingHoldThrows() {
            assertThatThrownBy(() -> service.capture(REF_TYPE, REF_ID, "user:42"))
                    .isInstanceOf(GiftCardInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        /** 카드 잔액은 애초에 건드리지 않았으므로 되돌릴 잔액이 없다 — 상태만 바뀐다. */
        @Test
        @DisplayName("선점 상태만 바뀐다 — 카드 잔액은 처음부터 그대로였다")
        void onlyHoldStatusChanges() {
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L));
            GiftCardHold active = hold(500L, 1L, "5000");
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(List.of(active));

            service.release(REF_TYPE, REF_ID, true);

            assertThat(active.getStatus()).isEqualTo(GiftCardHoldStatus.EXPIRED);
            assertThat(cardA.getRemainingAmount()).isEqualByComparingTo("5000");
            verify(entryPort, never()).append(any());
        }

        @Test
        @DisplayName("기한 경과가 아니면 RELEASED 로 남긴다")
        void explicitReleaseKeepsReason() {
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L));
            GiftCardHold active = hold(500L, 1L, "5000");
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(List.of(active));

            service.release(REF_TYPE, REF_ID, false);

            assertThat(active.getStatus()).isEqualTo(GiftCardHoldStatus.RELEASED);
        }

        @Test
        @DisplayName("선점이 없으면 조용히 넘어간다 — 만료 배치를 세우지 않는다")
        void missingHoldIsNoOp() {
            service.release(REF_TYPE, REF_ID, true);

            verify(giftCardPort, never()).loadForUpdate(anyCollection());
        }

        @Test
        @DisplayName("확정된 선점은 풀 수 없다 — 입금이 먼저 이긴 경우")
        void cannotReleaseCaptured() {
            GiftCardHold captured = hold(500L, 1L, "5000");
            captured.capture(NOW.plusHours(1));
            when(holdPort.findCardIdsByReference(REF_TYPE, REF_ID)).thenReturn(List.of(1L));
            when(holdPort.findByReference(REF_TYPE, REF_ID)).thenReturn(List.of(captured));

            assertThatThrownBy(() -> service.release(REF_TYPE, REF_ID, true))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }
}
