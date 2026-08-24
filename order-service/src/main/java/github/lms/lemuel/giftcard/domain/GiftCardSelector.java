package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 기프트카드 소비 순서 결정 — 어떤 장부터 쓸지를 정하는 유일한 지점.
 *
 * <p><b>만료 임박 순</b>으로 쓴다. 상품권은 만료가 곧 고객 손실이라 오래된 것부터 소진하는 편이
 * 고객에게 유리하다. 만료일이 같으면 먼저 발급된(id 가 작은) 장이 앞선다.
 *
 * <p><b>부족하면 아무것도 건드리지 않는다.</b> 계획을 먼저 세워 총액을 확인한 뒤에 적용하므로,
 * 잔액 부족으로 거절될 때 일부 카드만 깎여 있는 중간 상태가 생기지 않는다.
 */
public final class GiftCardSelector {

    private static final Comparator<GiftCard> CONSUME_ORDER =
            Comparator.comparing(GiftCard::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(GiftCard::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private GiftCardSelector() {
    }

    /**
     * {@code requested} 만큼을 소비 순서대로 카드에서 차감하고, 어느 장을 얼마나 썼는지 반환한다.
     * 전달된 카드들은 이 호출로 <b>변경된다</b> — 계획과 적용을 분리하면 적용을 잊는 경로가 생긴다.
     *
     * @throws InsufficientGiftCardBalanceException 쓸 수 있는 카드의 잔액 합이 요청액에 못 미칠 때
     */
    public static List<GiftCardCharge> consume(List<GiftCard> cards, BigDecimal requested) {
        return consume(cards, requested, Map.of());
    }

    /**
     * 선점을 뺀 가용액에서 소비한다.
     *
     * <p>즉시 결제도 <b>반드시</b> 선점을 빼고 봐야 한다 — 입금 대기 결제가 잠가 둔 몫을 여기서
     * 써 버리면, 나중에 그 입금이 도착했을 때 확정할 잔액이 남아 있지 않다.
     */
    public static List<GiftCardCharge> consume(List<GiftCard> cards, BigDecimal requested,
                                               Map<Long, BigDecimal> heldByCardId) {
        // 1단계 — 계획만 세운다(카드 미변경). 총액 부족은 여기서 끝난다.
        List<GiftCardCharge> plan = plan(cards, requested, heldByCardId);

        // 2단계 — 총액이 확인된 뒤에만 적용한다.
        java.util.Map<Long, GiftCard> byId = new java.util.HashMap<>();
        for (GiftCard card : cards) {
            byId.put(card.getId(), card);
        }
        for (GiftCardCharge charge : plan) {
            byId.get(charge.giftCardId()).use(charge.amount());
        }
        return plan;
    }

    /**
     * 소비 계획만 세운다 — <b>카드를 변경하지 않는다</b>. 선점이 쓰는 경로다.
     *
     * <p>가용액에서 {@code heldByCardId} 를 뺀다. 입금 대기 결제가 잠가 둔 몫을 다른 주문이 다시
     * 계획에 넣으면 같은 카드 잔액을 두 주문이 함께 쓰게 된다 — 기프트카드 선점의 존재 이유가
     * 정확히 이 한 줄이다.
     *
     * <p>잠긴 금액을 카드에 저장하지 않으므로(gift-card-ledger.md §3 — 요약을 두지 않는다)
     * 여기서 <b>계산</b>해 넘긴다. 저장했다면 그 값과 선점 행의 합이 어긋날 수 있었다.
     *
     * @throws InsufficientGiftCardBalanceException 선점을 뺀 가용액 합이 요청액에 못 미칠 때
     */
    public static List<GiftCardCharge> plan(List<GiftCard> cards, BigDecimal requested,
                                            Map<Long, BigDecimal> heldByCardId) {
        BigDecimal target = GiftCardAmounts.require(requested, "plan");
        Map<Long, BigDecimal> held = heldByCardId == null ? Map.of() : heldByCardId;

        List<GiftCard> candidates = cards.stream()
                .filter(card -> card.getStatus().isSpendable())
                .filter(card -> usable(card, held).signum() > 0)
                .sorted(CONSUME_ORDER)
                .toList();

        BigDecimal available = candidates.stream()
                .map(card -> usable(card, held))
                .reduce(GiftCardAmounts.zero(), BigDecimal::add);
        if (available.compareTo(target) < 0) {
            throw new InsufficientGiftCardBalanceException(
                    "기프트카드 잔액 부족: 요청 " + target + ", 가용 " + available, target, available);
        }

        List<GiftCardCharge> plan = new ArrayList<>();
        BigDecimal remaining = target;
        for (GiftCard card : candidates) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = usable(card, held).min(remaining);
            plan.add(new GiftCardCharge(card.getId(), take));
            remaining = remaining.subtract(take);
        }
        return List.copyOf(plan);
    }

    /** 선점을 뺀 "지금 쓸 수 있는" 상품권 잔액 — 화면과 결제가 함께 보는 값이다. */
    public static BigDecimal spendableBalance(List<GiftCard> cards, Map<Long, BigDecimal> heldByCardId) {
        Map<Long, BigDecimal> held = heldByCardId == null ? Map.of() : heldByCardId;
        return cards.stream()
                .filter(card -> card.getStatus().isSpendable())
                .map(card -> usable(card, held))
                .reduce(GiftCardAmounts.zero(), BigDecimal::add);
    }

    /** 이 카드에서 지금 쓸 수 있는 금액 — 잔액에서 잠긴 몫을 뺀다(음수로 내려가지 않는다). */
    private static BigDecimal usable(GiftCard card, Map<Long, BigDecimal> held) {
        BigDecimal locked = held.getOrDefault(card.getId(), GiftCardAmounts.zero());
        return card.getRemainingAmount().subtract(locked).max(GiftCardAmounts.zero());
    }

    /** 쓸 수 있는 카드의 잔액 합 — 사용자에게 보여 줄 "상품권 잔액"이다. */
    public static BigDecimal spendableBalance(List<GiftCard> cards) {
        return cards.stream()
                .filter(card -> card.getStatus().isSpendable())
                .map(GiftCard::getRemainingAmount)
                .reduce(GiftCardAmounts.zero(), BigDecimal::add);
    }
}
