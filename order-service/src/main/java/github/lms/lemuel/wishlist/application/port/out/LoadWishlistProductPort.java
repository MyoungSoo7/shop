package github.lms.lemuel.wishlist.application.port.out;

import github.lms.lemuel.wishlist.domain.WishlistProduct;

import java.util.Collection;
import java.util.Map;

/**
 * 찜한 상품들의 현재 모습을 <b>한 번에</b> 읽는다.
 *
 * <p><b>왜 단건이 아니라 일괄인가.</b> 이 저장소의 상품 조회 관례를 그대로 따르면 찜 목록 한 장은
 * 항목마다 상품 1회 + 대표 이미지 1회를 부른다({@code ProductController} 가 실제로 그렇게 한다).
 * 항목이 50개면 100번이다. 상품 상세 화면에서는 문제가 아니지만 목록에서는 그 자체가 화면의
 * 응답 시간이 된다. 그래서 포트의 모양을 처음부터 일괄로 못박는다 — 단건 메서드를 두면
 * 반드시 루프에서 불린다.
 *
 * <p>돌아오는 맵에는 <b>없는 상품의 키가 들어 있지 않다.</b> 삭제된 상품을 여기서 지어내지 않고,
 * 없다는 사실을 서비스가 {@link WishlistProduct#removed(Long)} 로 번역한다.
 */
public interface LoadWishlistProductPort {

    Map<Long, WishlistProduct> findAllByIds(Collection<Long> productIds);
}
