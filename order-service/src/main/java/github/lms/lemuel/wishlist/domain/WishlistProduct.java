package github.lms.lemuel.wishlist.domain;

import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 찜 목록이 상품에 대해 <b>알아야 하는 만큼만</b>의 사본.
 *
 * <p><b>왜 상품 도메인을 그대로 쓰지 않나.</b> 찜 목록이 필요로 하는 것은 이름·가격·살 수 있는지
 * 세 가지다. 상품 애그리거트 전체를 끌어오면 찜 슬라이스가 상품의 모든 변경(옵션 축, 변형, 태그,
 * 카테고리)에 묶이고, 그중 어느 것도 찜 목록의 화면을 바꾸지 않는다.
 *
 * <p>이 값은 <b>저장되지 않는다.</b> 조회할 때마다 상품에서 다시 읽는다. 찜 행에 이름·가격을
 * 복제해 두면 가격이 바뀌어도 목록은 옛 가격을 보여 주고, 사용자는 그 가격으로 살 수 있다고
 * 믿는다 — 같은 이유로 이 저장소의 장바구니도 상품 상세를 복제하지 않는다.
 *
 * @param productId       상품 식별자
 * @param name            상품명. 삭제된 상품이면 대체 문구가 들어간다
 * @param price           가격. 삭제된 상품이면 {@code null}
 * @param availability    지금 살 수 있는지, 없다면 왜인지
 * @param primaryImageUrl 대표 이미지. 없으면 {@code null}
 */
public record WishlistProduct(
        Long productId,
        String name,
        BigDecimal price,
        WishlistAvailability availability,
        String primaryImageUrl) {

    /** 상품이 삭제돼 이름조차 없을 때 목록에 적는 말. 빈 줄로 두면 무엇을 찜했는지 알 수 없다. */
    public static final String REMOVED_NAME = "삭제된 상품";

    public WishlistProduct {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(availability, "availability");
        if (name == null || name.isBlank()) {
            throw new WishlistInvariantViolationException("name 필수");
        }
    }

    /**
     * 찜 행은 남아 있는데 상품이 사라진 경우.
     *
     * <p>이 자리를 {@code null} 로 두지 않는 이유 — 화면·서비스·응답 매핑 세 곳이 각자 null 을
     * 확인해야 하고, 한 곳이라도 빠뜨리면 목록 전체가 터진다. 사라졌다는 사실 자체를 값으로 만든다.
     */
    public static WishlistProduct removed(Long productId) {
        return new WishlistProduct(productId, REMOVED_NAME, null, WishlistAvailability.REMOVED, null);
    }

    public boolean isAvailable() {
        return availability.isAvailable();
    }

    public boolean isGone() {
        return availability.isGone();
    }
}
