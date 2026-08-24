package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 포인트 로트 소비 순서 결정 — 어떤 적립분부터 쓸지를 정하는 유일한 지점.
 *
 * <p><b>만료 임박 순</b>으로 먹는다. 무기한 로트는 마지막이고, 만료일이 같으면 먼저 발급된
 * (id 가 작은) 로트가 앞선다. 출처(현금/보너스)로 우선순위를 주지 않는다 — 보너스 우선 소진은
 * 고객에게 불리하게 보이고, 동률 상황이 드물어 얻는 것도 없다.
 *
 * <p><b>부족하면 아무것도 건드리지 않는다.</b> 계획을 먼저 세워 총액을 확인한 뒤에 적용하므로,
 * 잔액 부족으로 거절될 때 일부 로트만 깎여 있는 중간 상태가 생기지 않는다.
 */
public final class PointLotSelector {

    /**
     * 만료 임박 순 → 로트 id 순. {@code expiresAt} 이 null(무기한)인 로트는 언제나 뒤로 민다.
     * id 가 아직 없는 로트는 비교에서 뒤로 보낸다(영속 전 로트가 섞이는 경우의 방어).
     */
    private static final Comparator<PointLot> CONSUME_ORDER =
            Comparator.comparing(PointLot::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PointLot::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private PointLotSelector() {
    }

    /**
     * {@code requested} 만큼을 소비 순서대로 로트에서 차감하고, 어느 로트를 얼마나 먹었는지 반환한다.
     * 전달된 로트들은 이 호출로 <b>변경된다</b> — 계획과 적용을 분리하면 적용을 잊는 경로가 생긴다.
     *
     * @throws InsufficientPointException 소비 가능한 로트의 잔량 합이 요청액에 못 미칠 때
     */
    public static List<PointLotConsumption> consume(List<PointLot> lots, BigDecimal requested) {
        BigDecimal target = PointAmounts.requirePoint(requested, "consume");

        List<PointLot> candidates = lots.stream()
                .filter(PointLot::isConsumable)
                .sorted(CONSUME_ORDER)
                .toList();

        BigDecimal availableTotal = candidates.stream()
                .map(PointLot::getRemainingAmount)
                .reduce(PointAmounts.zero(), BigDecimal::add);
        if (availableTotal.compareTo(target) < 0) {
            throw new InsufficientPointException(
                    "로트 재원 부족: 요청 " + target + ", 가용 " + availableTotal, target, availableTotal);
        }

        // 1단계 — 계획만 세운다(로트 미변경).
        List<PointLotConsumption> plan = new ArrayList<>();
        BigDecimal remaining = target;
        for (PointLot lot : candidates) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = lot.getRemainingAmount().min(remaining);
            plan.add(new PointLotConsumption(lot.getId(), take));
            remaining = remaining.subtract(take);
        }

        // 2단계 — 총액이 확인된 뒤에만 적용한다.
        int index = 0;
        for (PointLotConsumption consumption : plan) {
            candidates.get(index++).consume(consumption.amount());
        }
        return List.copyOf(plan);
    }
}
