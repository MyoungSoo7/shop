package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GiftCardSelector 소비 순서 단위 테스트.
 *
 * <p>규칙은 포인트 로트와 같다 — <b>만료 임박 순</b>, 동률이면 카드 id 순. 상품권은 만료가
 * 곧 고객 손실이라 오래된 것부터 쓰는 편이 고객에게 유리하다.
 */
class GiftCardSelectorTest {

    private static final OffsetDateTime ISSUED_AT =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final Long USER_ID = 42L;

    private GiftCard card(long id, String face, OffsetDateTime expiresAt) {
        GiftCard card = GiftCard.issue("hash-" + id, "000" + id, new BigDecimal(face),
                ISSUED_AT, expiresAt, "admin:1", null);
        card.assignId(id);
        card.activate();
        card.registerTo(USER_ID, ISSUED_AT.plusDays(1));
        return card;
    }

    @Test
    @DisplayName("만료가 이른 카드부터 소비한다")
    void consumesEarliestExpiryFirst() {
        GiftCard later = card(1L, "30000", ISSUED_AT.plusDays(200));
        GiftCard sooner = card(2L, "30000", ISSUED_AT.plusDays(30));

        List<GiftCardCharge> plan = GiftCardSelector.consume(
                List.of(later, sooner), new BigDecimal("10000"));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).giftCardId()).isEqualTo(2L);
        assertThat(sooner.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(later.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    @DisplayName("한 장으로 모자라면 다음 장으로 이어서 소비한다")
    void spansMultipleCards() {
        GiftCard first = card(1L, "10000", ISSUED_AT.plusDays(30));
        GiftCard second = card(2L, "30000", ISSUED_AT.plusDays(60));

        List<GiftCardCharge> plan = GiftCardSelector.consume(
                List.of(second, first), new BigDecimal("25000"));

        assertThat(plan).extracting(GiftCardCharge::giftCardId).containsExactly(1L, 2L);
        assertThat(plan).extracting(GiftCardCharge::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("15000"));
        assertThat(first.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
        assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    @DisplayName("잔액 합계보다 1원이라도 많으면 거절하고 카드를 건드리지 않는다 (경계값)")
    void rejectsOverTotalWithoutMutating() {
        GiftCard first = card(1L, "10000", ISSUED_AT.plusDays(30));
        GiftCard second = card(2L, "20000", ISSUED_AT.plusDays(60));

        assertThatThrownBy(() -> GiftCardSelector.consume(
                List.of(first, second), new BigDecimal("30001")))
                .isInstanceOf(InsufficientGiftCardBalanceException.class);

        assertThat(first.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(first.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
    }

    @Test
    @DisplayName("잔액 합계와 정확히 같은 요청은 전부 소비한다 (경계값)")
    void consumesExactTotal() {
        GiftCard first = card(1L, "10000", ISSUED_AT.plusDays(30));
        GiftCard second = card(2L, "20000", ISSUED_AT.plusDays(60));

        List<GiftCardCharge> plan = GiftCardSelector.consume(
                List.of(first, second), new BigDecimal("30000"));

        assertThat(plan).hasSize(2);
        assertThat(first.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
        assertThat(second.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
    }

    @Test
    @DisplayName("소진·정지된 카드는 재원에서 제외한다")
    void skipsUnusableCards() {
        GiftCard usedUp = card(1L, "10000", ISSUED_AT.plusDays(30));
        usedUp.use(new BigDecimal("10000"));
        GiftCard suspended = card(2L, "20000", ISSUED_AT.plusDays(40));
        suspended.suspend();
        GiftCard usable = card(3L, "20000", ISSUED_AT.plusDays(60));

        List<GiftCardCharge> plan = GiftCardSelector.consume(
                List.of(usedUp, suspended, usable), new BigDecimal("5000"));

        assertThat(plan).extracting(GiftCardCharge::giftCardId).containsExactly(3L);
    }

    @Test
    @DisplayName("쓸 수 있는 카드가 없으면 잔액 부족으로 거절한다")
    void rejectsWhenNoCards() {
        assertThatThrownBy(() -> GiftCardSelector.consume(List.of(), new BigDecimal("1000")))
                .isInstanceOf(InsufficientGiftCardBalanceException.class);
    }

    @Test
    @DisplayName("잔액 합계는 쓸 수 있는 카드만 센다")
    void spendableBalanceIgnoresUnusable() {
        GiftCard usable = card(1L, "10000", ISSUED_AT.plusDays(30));
        GiftCard suspended = card(2L, "20000", ISSUED_AT.plusDays(40));
        suspended.suspend();

        assertThat(GiftCardSelector.spendableBalance(List.of(usable, suspended)))
                .isEqualByComparingTo(new BigDecimal("10000"));
    }
}
