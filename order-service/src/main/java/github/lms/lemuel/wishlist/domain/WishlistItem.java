package github.lms.lemuel.wishlist.domain;

import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 찜 한 줄 — "누가 어떤 상품을 언제 담아 두었는가".
 *
 * <p>담는 것 외에 아무 상태도 없다. 수량도 옵션도 없다 — 찜은 <b>사겠다는 결정</b>이 아니라
 * <b>기억해 두겠다는 표시</b>이고, 수량·옵션은 살 때 정해진다. 레거시 찜 테이블은 옵션코드와
 * 수량({@code OPTCODE}, {@code CRNUM})을 들고 있었는데, 그 값이 담을 때 그대로 굳어 버려서
 * 옵션 구성이 바뀐 상품은 찜에서 담긴 옵션이 더는 존재하지 않는 상태가 되곤 했다.
 *
 * @param id        저장된 찜 행 식별자. 저장 전이면 {@code null}
 * @param userId    소유자. 이 값이 곧 접근 권한의 근거다
 * @param productId 찜한 상품
 * @param addedAt   담은 시각. 목록의 기본 정렬이 이것이다(최근 담은 것이 위)
 */
public record WishlistItem(
        Long id,
        Long userId,
        Long productId,
        LocalDateTime addedAt) {

    public WishlistItem {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(addedAt, "addedAt");
    }

    /** 새로 담는다. 담은 그 순간이 곧 담은 시각이다. */
    public static WishlistItem add(Long userId, Long productId) {
        if (userId == null || productId == null) {
            throw new WishlistInvariantViolationException("userId·productId 필수");
        }
        return new WishlistItem(null, userId, productId, LocalDateTime.now());
    }

    /** 저장된 행을 되살릴 때. */
    public static WishlistItem rehydrate(Long id, Long userId, Long productId, LocalDateTime addedAt) {
        return new WishlistItem(id, userId, productId, addedAt);
    }
}
