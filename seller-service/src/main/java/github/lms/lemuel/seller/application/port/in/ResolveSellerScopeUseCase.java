package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.domain.SellerScope;

/**
 * JWT 의 {@code userId} → 그 사람이 일할 수 있는 셀러 조직.
 *
 * <p>모든 컨트롤러가 첫 줄에서 이걸 부른다. 인가의 유일한 출처를 하나로 묶어 둔 것이라,
 * 이 메서드를 우회해 {@code sellerId} 를 만드는 경로가 생기면 그게 곧 IDOR 이다.
 *
 * <p>파트너 콘솔에서는 그 사고가 "남의 매출이 보인다" 였다. 여기서는 <b>남의 이름으로 상품이
 * 등록되고 남의 주문에 송장이 찍힌다.</b> 읽기 IDOR 은 노출이고 쓰기 IDOR 은 위조다.
 */
public interface ResolveSellerScopeUseCase {

    /**
     * @param userId JWT 에서 꺼낸 사용자 식별자. <b>요청 파라미터에서 온 값을 넣지 말 것.</b>
     * @throws github.lms.lemuel.seller.domain.exception.SellerScopeNotFoundException 소속 조직이 없을 때
     */
    SellerScope resolve(long userId);
}
