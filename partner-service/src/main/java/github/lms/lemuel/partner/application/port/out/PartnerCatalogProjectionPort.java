package github.lms.lemuel.partner.application.port.out;

import github.lms.lemuel.partner.domain.SellerTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** 주문·상품·셀러등급 보조 프로젝션 쓰기. */
public interface PartnerCatalogProjectionPort {

    void upsertOrder(long orderId, long userId, Long productId, String status,
                     BigDecimal amount, LocalDateTime orderedAt);

    void upsertProduct(long productId, String name);

    /**
     * 셀러 등급 스냅샷 갱신.
     *
     * <p>구현은 {@code effective_from} 이 더 이른 이벤트를 <b>무시</b>해야 한다. 재전달·재처리로
     * 낡은 등급 변경이 최신을 덮으면 화면 등급이 과거로 되돌아가고, 사용자는 강등된 줄 안다.
     */
    void upsertSellerTier(long sellerId, SellerTier tier, String reason,
                          LocalDate effectiveFrom, OffsetDateTime occurredAt);
}
