package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.application.port.dto.SellerOrderView;

import java.time.LocalDate;
import java.util.List;

/**
 * 셀러 주문 읽기 모델 조회.
 *
 * <p><b>모든 메서드가 {@code sellerId} 를 첫 인자로 받고, 구현은 그것을 WHERE 절에 강제로
 * 넣는다.</b> "전체 조회 후 필터" 형태의 메서드를 두지 않는 이유는, 그런 메서드는 필터를
 * 빠뜨려도 컴파일되고 테스트도 통과하기 때문이다 — 운영에서 남의 주문이 보일 때까지.
 *
 * <p>여기서 {@code sellerId} 가 {@code long} 인 것은, 이 계층에 오기 전에
 * {@code SellerScope.requireSellerId()} 를 이미 통과했다는 뜻이다.
 */
public interface SellerOrderQueryPort {

    long countOrders(long sellerId, LocalDate from, LocalDate to, Long orderId, boolean unshippedOnly);

    List<SellerOrderView> findOrders(long sellerId, LocalDate from, LocalDate to, Long orderId,
                                     boolean unshippedOnly, int limit, long offset);
}
