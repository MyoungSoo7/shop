package github.lms.lemuel.order.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회수 대기 재고 조회 — 배송된 물건에 환불·취소가 끝났는데 아직 물건이 돌아오지 않은 주문.
 *
 * <p>이 수량은 어느 쪽에도 잡혀 있지 않다: 판매 가능 재고로 복귀하지 않았고(회수 미확인), 고객에게는
 * 이미 환불됐다. 방치하면 팔 수 있는 물건이 영영 묶이므로, 운영자가 "얼마나 오래·얼마나 많이" 묶여
 * 있는지 보고 택배 회수를 독촉하거나 손실 처리를 결정할 수 있어야 한다.
 */
public interface GetPendingStockReclaimUseCase {

    /**
     * 회수 대기 주문을 오래 묶인 순으로 조회한다.
     *
     * @param now   경과일 계산 기준 시각
     * @param limit 최대 건수
     */
    List<PendingReclaim> findPending(LocalDateTime now, int limit);

    /**
     * 회수 대기 1건.
     *
     * @param pendingDays    종단(환불·취소) 이후 경과일 — 클수록 회수가 지연된 건
     * @param totalQuantity  이 주문으로 묶여 있는 총 수량
     */
    record PendingReclaim(Long orderId, Long userId, String status,
                          LocalDateTime terminalAt, long pendingDays,
                          int totalQuantity, BigDecimal orderAmount,
                          List<PendingLine> lines) {
    }

    /** 회수 대기 라인 — 어떤 상품이 몇 개 묶여 있는지. */
    record PendingLine(Long productId, Long variantId, String sku, String productName, int quantity) {
    }
}
