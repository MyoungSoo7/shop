package github.lms.lemuel.order.adapter.out.point;

import github.lms.lemuel.order.application.port.out.OrderPointRewardPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.point.application.port.in.EarnPointOnOrderUseCase;
import github.lms.lemuel.point.application.port.in.EarnPointOnOrderUseCase.EarnPointCommand;
import github.lms.lemuel.point.application.port.in.RevokeOrderPointUseCase;
import github.lms.lemuel.point.application.port.in.RevokeOrderPointUseCase.RevokeOrderPointCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 주문 ↔ 포인트 적립 연결 어댑터.
 *
 * <p><b>적립 대상 금액에서 배송비를 뺀다.</b> 배송비는 상품 대금이 아니라 운송 원가를 고객이 부담한
 * 몫이라, 여기에 적립률을 곱하면 회사가 배송비의 일부를 판촉비로 되돌려 주는 셈이 된다.
 * ({@code Order.shippingFee} 는 "결제에 포함된 배송비"로 이미 분리 보관돼 있다.)
 */
@Component
public class OrderPointRewardAdapter implements OrderPointRewardPort {

    private final EarnPointOnOrderUseCase earnPointOnOrderUseCase;
    private final RevokeOrderPointUseCase revokeOrderPointUseCase;

    public OrderPointRewardAdapter(EarnPointOnOrderUseCase earnPointOnOrderUseCase,
                                   RevokeOrderPointUseCase revokeOrderPointUseCase) {
        this.earnPointOnOrderUseCase = earnPointOnOrderUseCase;
        this.revokeOrderPointUseCase = revokeOrderPointUseCase;
    }

    @Override
    public void earnOnDelivered(Order order) {
        if (order.getUserId() == null) {
            // 회원 없는 주문(레거시·게스트)에는 적립할 계정이 없다.
            return;
        }
        earnPointOnOrderUseCase.earn(new EarnPointCommand(
                order.getId(), order.getUserId(), eligibleAmount(order),
                LocalDate.now(), "order:" + order.getId()));
    }

    @Override
    public void revokeOnCanceled(Order order) {
        revokeOrderPointUseCase.revoke(new RevokeOrderPointCommand(
                order.getId(), "order:" + order.getId()));
    }

    /** 상품 금액 = 결제금액 − 배송비. 음수가 되면(데이터 이상) 0 으로 클램프해 적립을 만들지 않는다. */
    private static BigDecimal eligibleAmount(Order order) {
        BigDecimal amount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
        BigDecimal shippingFee = order.getShippingFee() == null ? BigDecimal.ZERO : order.getShippingFee();
        BigDecimal eligible = amount.subtract(shippingFee);
        return eligible.signum() > 0 ? eligible : BigDecimal.ZERO;
    }
}
