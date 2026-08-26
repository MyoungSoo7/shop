package github.lms.lemuel.payment.adapter.out.order;

import github.lms.lemuel.order.application.port.out.LoadOrderRefundRoutePort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * order 가 선언한 {@link LoadOrderRefundRoutePort} 를 payment 슬라이스가 구현한다 —
 * "이 주문의 환불은 계좌 송금으로만 되는가"에 답한다.
 *
 * <p>{@link OrderPaymentRefundAdapter} 와 같은 자리에 사는 이유도 같다: 인터페이스는 필요한
 * 쪽(order)이 소유하고 구현은 능력을 가진 쪽(payment)이 제공해야 결합이 payment→order 한 방향으로
 * 모인다.
 *
 * <p>판정은 {@code PaymentDomain.awaitsDeposit()} 하나에 맡긴다. 여기서 결제 수단 문자열을 다시
 * 뜯어보면 분할결제(카드 90,000 + 가상계좌 10,000)에서 그 판정이 갈린다 — 같은 질문에 두 개의
 * 답이 생기는 셈이다.
 */
@Component
public class OrderRefundRouteAdapter implements LoadOrderRefundRoutePort {

    private final GetPaymentPort getPaymentPort;

    public OrderRefundRouteAdapter(@Lazy GetPaymentPort getPaymentPort) {
        this.getPaymentPort = getPaymentPort;
    }

    @Override
    public boolean requiresBankRefund(Long orderId) {
        Optional<PaymentDomain> payment = getPaymentPort.findByOrderId(orderId);
        // 결제가 없는 주문(미결제 취소)은 되돌릴 돈이 없다 — 계좌를 요구하면 취소를 막는 셈이 된다.
        return payment.isPresent() && payment.get().awaitsDeposit();
    }
}
