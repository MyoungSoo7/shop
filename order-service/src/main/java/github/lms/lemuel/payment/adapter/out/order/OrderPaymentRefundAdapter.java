package github.lms.lemuel.payment.adapter.out.order;

import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * order 가 선언한 {@link RefundOrderPaymentPort} 를 payment 슬라이스가 구현한다 —
 * 주문 취소·환불 승인 경로에서 실제 결제 환불을 수행한다.
 *
 * <p>Payment 의 JPA 엔티티·리포지토리를 직접 참조하지 않고 자기 슬라이스의 inbound use case
 * ({@link GetPaymentPort}, {@link RefundPaymentPort})만 호출한다. 두 포트는 각각 별도 빈이라
 * Spring 프록시를 거치므로 RefundPaymentUseCase 의 {@code @Transactional}/{@code @Auditable} 가 정상 적용된다.
 *
 * <p>이 클래스가 <b>payment 쪽에 사는 이유</b>: order 슬라이스에 두면 order→payment 간선이 생기고,
 * 이미 있는 {@code payment.adapter.out.persistence.OrderAdapter}(payment→order)와 만나
 * {@code order ↔ payment} 순환이 된다. 인터페이스는 필요한 쪽(order)이 소유하고 구현은 능력을 가진
 * 쪽(payment)이 제공하면 결합은 payment→order 한 방향으로 모인다.
 *
 * <p><b>주의 — {@code @Lazy} 는 남는다.</b> 위 이동은 <b>컴파일 시점 슬라이스 그래프</b>를 정리할 뿐
 * 스프링 <b>빈 그래프</b>를 바꾸지 않는다. 런타임 생성자 주입 사이클
 * (Change→본 어댑터→RefundUseCase→OrderAdapter→Change)은 그대로이므로 이 간선의 지연 주입이 계속 필요하다.
 */
@Component
public class OrderPaymentRefundAdapter implements RefundOrderPaymentPort {

    private final GetPaymentPort getPaymentPort;
    private final RefundPaymentPort refundPaymentPort;

    public OrderPaymentRefundAdapter(@Lazy GetPaymentPort getPaymentPort,
                                     @Lazy RefundPaymentPort refundPaymentPort) {
        this.getPaymentPort = getPaymentPort;
        this.refundPaymentPort = refundPaymentPort;
    }

    @Override
    public void refundOrderPayment(Long orderId, BigDecimal amount, String idempotencyKey) {
        PaymentDomain payment = getPaymentPort.getPaymentByOrderId(orderId);
        // amount=null → 전액 환불(payment-{id}-full 기본 멱등 키), amount 지정 → 부분 환불(호출자 멱등 키)
        refundPaymentPort.refundPayment(payment.getId(), amount, idempotencyKey);
    }

    @Override
    public boolean refundOrderPaymentFullyIfPresent(Long orderId) {
        Optional<PaymentDomain> payment = getPaymentPort.findByOrderId(orderId);
        if (payment.isEmpty() || payment.get().getStatus() != PaymentStatus.CAPTURED) {
            return false; // 미결제이거나 환불 가능 상태(CAPTURED)가 아니면 환불 대상 없음
        }
        refundPaymentPort.refundPayment(payment.get().getId(), null, null);
        return true;
    }
}
