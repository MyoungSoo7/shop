package github.lms.lemuel.partner.adapter.out.persistence;

import github.lms.lemuel.partner.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.partner.application.port.out.PartnerCatalogProjectionPort;
import github.lms.lemuel.partner.domain.SellerTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 주문·상품·셀러등급 보조 프로젝션. 매출의 근거가 아니라 매출 옆에 붙는 표기용 데이터다. */
@Component
@RequiredArgsConstructor
class PartnerCatalogPersistenceAdapter implements PartnerCatalogProjectionPort, LoadSellerTierPort {

    private final PartnerOrderJpaRepository orderRepository;
    private final PartnerProductJpaRepository productRepository;
    private final PartnerSellerTierJpaRepository tierRepository;

    @Override
    public void upsertOrder(long orderId, long userId, Long productId, String status,
                            BigDecimal amount, LocalDateTime orderedAt) {
        orderRepository.upsert(orderId, userId, productId, status, amount, orderedAt);
    }

    @Override
    public void upsertProduct(long productId, String name) {
        productRepository.upsert(productId, name);
    }

    @Override
    public void upsertSellerTier(long sellerId, SellerTier tier, String reason,
                                 LocalDate effectiveFrom, OffsetDateTime occurredAt) {
        tierRepository.upsertIfNewer(sellerId, tier.name(), effectiveFrom, reason, occurredAt);
    }

    @Override
    public Optional<TierSnapshot> findBySellerId(long sellerId) {
        List<Object[]> rows = tierRepository.findTierRows(sellerId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new TierSnapshot(
                SellerTier.valueOf(Rows.stringAt(row, 0)),
                Rows.dateAt(row, 1)));
    }
}
