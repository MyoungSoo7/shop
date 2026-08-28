package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.domain.SellerTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 주문·상품·셀러등급 보조 프로젝션 적재.
 *
 * <p>셋 다 <b>매출을 만들지 않는다</b> — 결제 행에 이름과 상태를 붙여 주는 곁가지다. 그래서
 * 이 셋이 늦게 오거나 영영 안 와도 매출 합계는 정확하고, 화면에는 이름 대신 ID 가 보인다.
 */
public interface RecordCatalogUseCase {

    void orderCreated(OrderCreated event);

    void productChanged(long productId, String name);

    void sellerTierChanged(SellerTierChanged event);

    record OrderCreated(long orderId, long userId, Long productId, String status,
                        BigDecimal amount, LocalDateTime createdAt) {
    }

    /**
     * @param backfill {@code reason='BACKFILL'} — 변경이 아니라 이미 확정된 등급의 재발행이다.
     *                 등급 값은 반영하되 "이때 바뀌었다" 로 읽지 않는다.
     */
    record SellerTierChanged(long sellerId, SellerTier newTier, String reason, LocalDate effectiveFrom,
                             OffsetDateTime occurredAt, boolean backfill) {
    }
}
