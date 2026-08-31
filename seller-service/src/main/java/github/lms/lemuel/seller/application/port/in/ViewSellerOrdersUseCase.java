package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.application.port.dto.SellerOrderPage;
import github.lms.lemuel.seller.application.port.dto.SellerOrderQuery;
import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.domain.SellerScope;

import java.util.Optional;

/**
 * 내 상품이 주문된 것 — 셀러 백오피스의 두 번째 축.
 *
 * <p><b>행의 기준은 주문이 아니라 결제다.</b> {@code sellerId} 를 실어 오는 이벤트가
 * {@code payment.captured} 뿐이라(ADR 0020), 결제가 확정되지 않은 주문은 여기에 나타나지
 * 않는다. 출고 대상은 대체로 결제된 주문이니 실무적으로는 맞지만, "맞아서" 가 아니라
 * "그것밖에 없어서" 라는 걸 화면에도 적는다.
 */
public interface ViewSellerOrdersUseCase {

    SellerOrderPage orders(SellerScope scope, SellerOrderQuery query);

    /** 단건. 목록과 마찬가지로 처음부터 내 셀러로 필터한다 — 소유자 검사를 빠뜨릴 자리가 없다. */
    Optional<SellerOrderView> order(SellerScope scope, long orderId);
}
