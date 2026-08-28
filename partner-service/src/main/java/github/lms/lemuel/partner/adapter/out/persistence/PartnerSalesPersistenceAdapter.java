package github.lms.lemuel.partner.adapter.out.persistence;

import github.lms.lemuel.partner.application.port.dto.BestProductView;
import github.lms.lemuel.partner.application.port.dto.DailySalesView;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.application.port.dto.SalesSummaryView;
import github.lms.lemuel.partner.application.port.out.PartnerSalesProjectionPort;
import github.lms.lemuel.partner.application.port.out.PartnerSalesQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 매출 프로젝션 적재 + 조회. 화면의 모든 금액이 이 클래스를 지난다. */
@Component
@RequiredArgsConstructor
class PartnerSalesPersistenceAdapter implements PartnerSalesQueryPort, PartnerSalesProjectionPort {

    private final PartnerSaleJpaRepository saleRepository;
    private final PartnerRefundJpaRepository refundRepository;

    @Override
    public SalesSummaryView summary(long sellerId, LocalDate from, LocalDate to) {
        List<Object[]> rows = saleRepository.summaryRows(sellerId, from, to);
        if (rows.isEmpty()) {
            return SalesSummaryView.empty();
        }
        Object[] row = rows.get(0);
        return new SalesSummaryView(
                Rows.decimalAt(row, 0),
                Rows.decimalAt(row, 1),
                Rows.decimalAt(row, 2),
                Rows.longAt(row, 3));
    }

    @Override
    public List<DailySalesView> daily(long sellerId, LocalDate from, LocalDate to) {
        // 매출이 0 인 날은 행이 없다. 빈 날을 0 으로 채우는 것은 화면(그래프)의 몫으로 둔다 —
        // 여기서 채우면 366일 조회가 366행을 만들고, 그 대부분이 의미 없는 0 이다.
        return saleRepository.dailyRows(sellerId, from, to).stream()
                .map(row -> new DailySalesView(
                        Rows.dateAt(row, 0),
                        Rows.decimalAt(row, 1),
                        Rows.decimalAt(row, 2),
                        Rows.decimalAt(row, 3),
                        Rows.longAt(row, 4)))
                .toList();
    }

    @Override
    public List<BestProductView> bestProducts(long sellerId, LocalDate from, LocalDate to, int limit) {
        return saleRepository.bestProductRows(sellerId, from, to, limit).stream()
                .map(row -> new BestProductView(
                        Rows.nullableLongAt(row, 0),
                        Rows.stringAt(row, 1),
                        Rows.decimalAt(row, 2),
                        Rows.longAt(row, 3)))
                .toList();
    }

    @Override
    public boolean hasEstimatedCaptureDates(long sellerId, LocalDate from, LocalDate to) {
        return saleRepository.hasEstimatedCaptureDates(sellerId, from, to);
    }

    @Override
    public long countOrders(long sellerId, LocalDate from, LocalDate to, Long orderId) {
        return saleRepository.countOrders(sellerId, from, to, orderId);
    }

    @Override
    public List<PartnerOrderView> findOrders(long sellerId, LocalDate from, LocalDate to, Long orderId,
                                             int limit, long offset) {
        return saleRepository.orderRows(sellerId, from, to, orderId, limit, offset).stream()
                .map(PartnerSalesPersistenceAdapter::toOrderView)
                .toList();
    }

    @Override
    public void upsertSale(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                           String sellerTier, String settlementCycle, String paymentMethod,
                           LocalDateTime capturedAt, boolean capturedAtEstimated) {
        saleRepository.upsert(paymentId, orderId, sellerId, amount, sellerTier, settlementCycle,
                paymentMethod, capturedAt, capturedAtEstimated);
    }

    @Override
    public void upsertRefund(long paymentId, String refundKey, long orderId,
                             BigDecimal refundAmount, BigDecimal refundedTotal) {
        refundRepository.upsert(paymentId, refundKey, orderId, refundAmount, refundedTotal);
    }

    private static PartnerOrderView toOrderView(Object[] row) {
        BigDecimal amount = Rows.decimalAt(row, 4);
        BigDecimal refunded = Rows.decimalAt(row, 5);
        return new PartnerOrderView(
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
                Rows.stringAt(row, 9));
    }
}
