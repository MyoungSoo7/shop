package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.application.port.out.SellerCommerceProjectionPort;
import github.lms.lemuel.seller.application.port.out.SellerOrderQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 결제·환불·주문·상품 사본의 적재와, 그 위에 서 있는 셀러 주문 조회.
 *
 * <p>적재와 조회를 한 어댑터에 둔 것은 둘이 <b>같은 네 테이블</b>을 쓰기 때문이다. 나누면
 * 조회 쪽이 어느 컬럼을 믿어도 되는지가 파일 두 개에 흩어지고, 그 순간 "이 컬럼은 이벤트가
 * 안 실어 줘서 비어 있을 수 있다" 같은 사실이 한쪽에서만 지켜진다.
 */
@Component
@RequiredArgsConstructor
class SellerCommercePersistenceAdapter implements SellerCommerceProjectionPort, SellerOrderQueryPort {

    private final SellerSaleJpaRepository saleRepository;
    private final SellerRefundJpaRepository refundRepository;
    private final SellerOrderJpaRepository orderRepository;
    private final SellerProductJpaRepository productRepository;

    // ------------------------------------------------------------------ 적재

    @Override
    public void upsertSale(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                           String paymentMethod, LocalDateTime capturedAt, boolean capturedAtEstimated) {
        saleRepository.upsert(paymentId, orderId, sellerId, amount, paymentMethod,
                capturedAt, capturedAtEstimated);
    }

    @Override
    public void upsertRefund(long paymentId, String refundKey, long orderId,
                             BigDecimal refundAmount, BigDecimal refundedTotal) {
        refundRepository.upsert(paymentId, refundKey, orderId, refundAmount, refundedTotal);
    }

    @Override
    public void upsertOrder(long orderId, long userId, Long productId, String status,
                            BigDecimal amount, LocalDateTime createdAt) {
        orderRepository.upsert(orderId, userId, productId, status, amount, createdAt);
    }

    @Override
    public void upsertProduct(long productId, String name) {
        productRepository.upsert(productId, name);
    }

    @Override
    public void linkProduct(long productId, String name, long submissionId) {
        productRepository.linkProduct(productId, name, submissionId);
    }

    // ------------------------------------------------------------------ 조회

    @Override
    public long countOrders(long sellerId, LocalDate from, LocalDate to, Long orderId, boolean unshippedOnly) {
        return saleRepository.countOrders(sellerId, from, to, orderId, unshippedOnly);
    }

    @Override
    public List<SellerOrderView> findOrders(long sellerId, LocalDate from, LocalDate to, Long orderId,
                                            boolean unshippedOnly, int limit, long offset) {
        return saleRepository.orderRows(sellerId, from, to, orderId, unshippedOnly, limit, offset).stream()
                .map(SellerCommercePersistenceAdapter::toOrderView)
                .toList();
    }

    /**
     * 순수 매출(net)은 여기서 계산한다 — SQL 에서 빼도 되지만, 그러면 "환불이 결제보다 클 수
     * 있다"(부분환불 여러 건이 겹칠 때) 는 사실이 쿼리 안에 숨는다. 음수를 0 으로 자르지 않는
     * 이유도 같다: 실제로 음수인 정산 건을 0 으로 보이면 셀러는 손해를 못 본다.
     */
    private static SellerOrderView toOrderView(Object[] row) {
        BigDecimal amount = Rows.decimalAt(row, 4);
        BigDecimal refunded = Rows.decimalAt(row, 5);
        OffsetDateTime requestedAt = Rows.offsetDateTimeAt(row, 12);
        return new SellerOrderView(
                Rows.longAt(row, 0),
                Rows.longAt(row, 1),
                Rows.dateTimeAt(row, 2),
                Rows.boolAt(row, 3),
                amount,
                refunded,
                amount.subtract(refunded),
                Rows.stringAt(row, 6),
                Rows.stringAt(row, 7),
                Rows.nullableLongAt(row, 8),
                Rows.stringAt(row, 9),
                requestedAt != null,
                Rows.stringAt(row, 10),
                Rows.stringAt(row, 11),
                requestedAt);
    }
}
