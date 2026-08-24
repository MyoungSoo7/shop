package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.SellerShippingPolicy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 셀러 배송비 정책 조회 포트. 정책이 없는 셀러는 결과에서 빠지고, 기본배송비는 부과되지 않는다. */
public interface LoadSellerShippingPolicyPort {

    Map<Long, SellerShippingPolicy> loadBySellerIds(Collection<Long> sellerIds);

    Optional<SellerShippingPolicy> loadBySellerId(Long sellerId);

    /**
     * 등록된 정책 전체(셀러 ID 오름차순).
     *
     * <p>주문 계산 경로는 이걸 쓰지 않는다 — 거기서는 주문에 실린 셀러만 배치 조회한다.
     * 이 메서드는 <b>운영 콘솔 전용</b>이다. 정책은 셀러당 1 행이고 셀러 수만큼만 늘어나므로
     * 페이지네이션 없이 전량을 읽는다.
     */
    List<SellerShippingPolicy> loadAll();
}
