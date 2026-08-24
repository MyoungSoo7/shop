package github.lms.lemuel.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 라인 단위 부분 취소.
 *
 * <p>주문 전체 취소(모두 아니면 전무)와 금액 단위 부분 환불 사이에 비어 있던 자리를 채운다 —
 * "3 개 중 1 개만 취소" 는 어떤 상품이 빠졌는지 주문서가 알아야 성립하고, 그 정보가 있어야
 * 배송비를 다시 계산할 수 있다.
 */
public interface CancelOrderItemsUseCase {

    /**
     * 지정한 라인을 취소하고, 재고 복원 · 배송비 재산정 · 부분 환불까지 한 트랜잭션에서 수행한다.
     *
     * @param orderId  대상 주문
     * @param itemIds  취소할 주문 라인 id
     * @param reason   취소 사유(감사 이력)
     * @param operator 수행 주체(사용자 또는 운영자)
     */
    Result cancelItems(Long orderId, List<Long> itemIds, String reason, String operator);

    /**
     * @param canceledSubtotal      취소된 라인 금액 합
     * @param additionalShippingFee 무료배송 조건이 깨져 되살아난 배송비(없으면 0)
     * @param refundedAmount        실제 환불한 금액({@code canceledSubtotal + 기존배송비 - 새배송비}, 0 하한)
     * @param orderFullyCanceled    남은 라인이 하나도 없는지
     */
    record Result(Long orderId,
                  BigDecimal canceledSubtotal,
                  BigDecimal additionalShippingFee,
                  BigDecimal refundedAmount,
                  boolean orderFullyCanceled) {
    }
}
