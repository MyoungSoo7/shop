package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardAmountException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기프트카드 선점 — 입금 대기 결제가 붙잡아 두는 카드 잔액.
 *
 * <p><b>포인트와 결정적으로 다른 점</b>: 잠긴 금액을 <b>저장하지 않는다</b>. 기프트카드에는 잔액
 * 요약 테이블이 없고(gift-card-ledger.md §3), 거기에 {@code locked} 컬럼을 더하면 "저장된 값과
 * 선점 행의 합이 어긋날 수 있다"는 손상 축을 새로 만든다 — 그 축이 없다는 것이 이 원장의 설계
 * 자산이다. 그래서 가용액은 언제나 {@code remaining − Σ(활성 선점)} 으로 <b>계산</b>한다.
 */
class GiftCardHoldTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-22T10:00:00+09:00");
    private static final OffsetDateTime LATER = NOW.plusHours(3);

    private static GiftCard card(long id, String face, String remaining, String expiresAt) {
        return GiftCard.rehydrate(id, "hash-" + id, "1234", new BigDecimal(face),
                new BigDecimal(remaining), GiftCardStatus.REGISTERED, 42L,
                NOW.minusDays(30), NOW.minusDays(30), NOW.minusDays(29),
                OffsetDateTime.parse(expiresAt), "admin", null, 0L);
    }

    @Nested
    @DisplayName("선점 레코드")
    class Hold {

        private static GiftCardHold active() {
            return GiftCardHold.place(11L, new BigDecimal("5000"), "PAYMENT_TENDER", "77", NOW);
        }

        @Test
        @DisplayName("ACTIVE 로 시작하고 해소 시각은 비어 있다")
        void startsActive() {
            GiftCardHold hold = active();

            assertThat(hold.getStatus()).isEqualTo(GiftCardHoldStatus.ACTIVE);
            assertThat(hold.isActive()).isTrue();
            assertThat(hold.getResolvedAt()).isNull();
        }

        @Test
        @DisplayName("금액은 양수 1원 단위 정수, 카드와 참조는 필수")
        void validatesInputs() {
            assertThatThrownBy(() -> GiftCardHold.place(11L, BigDecimal.ZERO, "T", "1", NOW))
                    .isInstanceOf(InvalidGiftCardAmountException.class);
            assertThatThrownBy(() -> GiftCardHold.place(11L, new BigDecimal("10.5"), "T", "1", NOW))
                    .isInstanceOf(InvalidGiftCardAmountException.class);
            assertThatThrownBy(() -> GiftCardHold.place(null, BigDecimal.TEN, "T", "1", NOW))
                    .isInstanceOf(InvalidGiftCardStateException.class);
            assertThatThrownBy(() -> GiftCardHold.place(11L, BigDecimal.TEN, "T", "  ", NOW))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("확정·해제·만료는 종단으로 한 번만 간다")
        void resolvesOnce() {
            GiftCardHold captured = active();
            captured.capture(LATER);
            assertThat(captured.getStatus()).isEqualTo(GiftCardHoldStatus.CAPTURED);
            assertThat(captured.getResolvedAt()).isEqualTo(LATER);

            GiftCardHold released = active();
            released.release(LATER);
            assertThat(released.getStatus()).isEqualTo(GiftCardHoldStatus.RELEASED);

            GiftCardHold expired = active();
            expired.expire(LATER);
            assertThat(expired.getStatus()).isEqualTo(GiftCardHoldStatus.EXPIRED);
        }

        @Test
        @DisplayName("같은 상태 재적용은 멱등 no-op — 최초 해소 시각을 보존한다")
        void sameStateIsIdempotent() {
            GiftCardHold hold = active();
            hold.capture(LATER);

            hold.capture(LATER.plusHours(1));

            assertThat(hold.getResolvedAt()).isEqualTo(LATER);
        }

        /** 만료 배치가 푼 선점을 뒤늦은 입금이 확정하면 이미 돌아간 잔액을 한 번 더 쓴다. */
        @Test
        @DisplayName("종단끼리는 전이하지 않는다 — 입금 vs 만료 경합의 최종 방어선")
        void terminalCannotCrossTransition() {
            GiftCardHold expired = active();
            expired.expire(LATER);

            assertThatThrownBy(() -> expired.capture(LATER.plusHours(1)))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Nested
    @DisplayName("선점을 뺀 가용액")
    class AvailabilityWithHolds {

        /** 이 한 건이 기프트카드 선점의 전부다 — 잠긴 몫은 다른 주문이 계획에 넣을 수 없다. */
        @Test
        @DisplayName("잠긴 몫은 가용에서 빠진다 — 같은 카드를 두 주문이 함께 쓰지 못한다")
        void heldAmountIsNotAvailable() {
            List<GiftCard> cards = List.of(card(1L, "10000", "10000", "2026-12-31T23:59:59+09:00"));
            Map<Long, BigDecimal> held = Map.of(1L, new BigDecimal("6000"));

            assertThat(GiftCardSelector.spendableBalance(cards, held)).isEqualByComparingTo("4000");
            assertThatThrownBy(() -> GiftCardSelector.plan(cards, new BigDecimal("4001"), held))
                    .isInstanceOf(InsufficientGiftCardBalanceException.class);
        }

        @Test
        @DisplayName("계획은 만료 임박 순으로 서고, 카드를 변경하지 않는다")
        void planIsOrderedAndNonMutating() {
            GiftCard soon = card(2L, "5000", "5000", "2026-09-30T23:59:59+09:00");
            GiftCard later = card(3L, "5000", "5000", "2026-12-31T23:59:59+09:00");

            List<GiftCardCharge> plan = GiftCardSelector.plan(
                    List.of(later, soon), new BigDecimal("7000"), Map.of());

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).giftCardId()).isEqualTo(2L);
            assertThat(plan.get(0).amount()).isEqualByComparingTo("5000");
            assertThat(plan.get(1).giftCardId()).isEqualTo(3L);
            assertThat(plan.get(1).amount()).isEqualByComparingTo("2000");
            // 선점은 잔액을 건드리지 않는다 — 확정 시점에야 카드가 깎인다.
            assertThat(soon.getRemainingAmount()).isEqualByComparingTo("5000");
            assertThat(later.getRemainingAmount()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("부분 선점된 카드는 남은 몫만큼만 계획에 들어간다")
        void partiallyHeldCardContributesRemainder() {
            GiftCard a = card(4L, "10000", "10000", "2026-09-30T23:59:59+09:00");
            GiftCard b = card(5L, "10000", "10000", "2026-12-31T23:59:59+09:00");

            List<GiftCardCharge> plan = GiftCardSelector.plan(
                    List.of(a, b), new BigDecimal("9000"), Map.of(4L, new BigDecimal("7000")));

            assertThat(plan.get(0).giftCardId()).isEqualTo(4L);
            assertThat(plan.get(0).amount()).isEqualByComparingTo("3000");
            assertThat(plan.get(1).giftCardId()).isEqualTo(5L);
            assertThat(plan.get(1).amount()).isEqualByComparingTo("6000");
        }

        @Test
        @DisplayName("전부 잠긴 카드는 후보에서 빠진다")
        void fullyHeldCardIsSkipped() {
            List<GiftCard> cards = List.of(
                    card(6L, "5000", "5000", "2026-09-30T23:59:59+09:00"),
                    card(7L, "5000", "5000", "2026-12-31T23:59:59+09:00"));
            Map<Long, BigDecimal> held = Map.of(6L, new BigDecimal("5000"));

            List<GiftCardCharge> plan = GiftCardSelector.plan(cards, new BigDecimal("5000"), held);

            assertThat(plan).singleElement()
                    .satisfies(c -> assertThat(c.giftCardId()).isEqualTo(7L));
        }

        @Test
        @DisplayName("선점이 없으면 기존 잔액 계산과 같다")
        void noHoldsMatchesPlainBalance() {
            List<GiftCard> cards = List.of(
                    card(8L, "5000", "3000", "2026-12-31T23:59:59+09:00"),
                    card(9L, "5000", "1000", "2026-12-31T23:59:59+09:00"));

            assertThat(GiftCardSelector.spendableBalance(cards, Map.of()))
                    .isEqualByComparingTo(GiftCardSelector.spendableBalance(cards));
        }
    }
}
