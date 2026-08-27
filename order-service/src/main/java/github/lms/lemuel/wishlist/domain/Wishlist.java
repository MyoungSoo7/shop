package github.lms.lemuel.wishlist.domain;

import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;

import java.util.List;
import java.util.Objects;

/**
 * 한 사용자의 찜 목록 전체.
 *
 * <p>목록은 <b>거르지 않는다.</b> 살 수 없는 상품도 사유와 함께 그대로 들어 있고, 화면이 회색으로
 * 그린다. 거르는 판단을 조회 쿼리에 넣으면 사용자는 자기 찜이 몇 개인지조차 알 수 없게 된다.
 *
 * @param userId  소유자
 * @param entries 담은 시각 역순(최근이 위). 정렬은 저장소가 보장한다
 */
public record Wishlist(Long userId, List<WishlistEntry> entries) {

    /**
     * 한 사용자가 담을 수 있는 최대 개수.
     *
     * <p>레거시 찜 테이블에는 상한이 없었다. 상한이 없는 목록은 조회 한 번에 상품 조인이 무한정
     * 늘어나고, 계정 하나로 테이블을 부풀리는 것도 막지 못한다. 사람이 실제로 관리하는 찜의 규모를
     * 한참 넘는 값이라 정상 사용에는 걸리지 않는다.
     */
    public static final int MAX_ITEMS = 300;

    public Wishlist {
        Objects.requireNonNull(userId, "userId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public static Wishlist empty(Long userId) {
        return new Wishlist(userId, List.of());
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 지금 살 수 있는 것들. */
    public List<WishlistEntry> available() {
        return entries.stream().filter(WishlistEntry::isAvailable).toList();
    }

    /**
     * 일괄 정리로 사라질 것들 — 단종·삭제만이다.
     *
     * <p>이 목록이 곧 "정리" 버튼이 지울 대상이며, 화면은 <b>지우기 전에</b> 이걸 보여 준다.
     * 무엇이 지워지는지 모르는 채 누르는 버튼이 레거시의 실제 문제였다.
     */
    public List<WishlistEntry> gone() {
        return entries.stream().filter(WishlistEntry::isGone).toList();
    }

    /** 담을 자리가 남았는지. 넘으면 {@link #requireRoom()} 이 거부한다. */
    public boolean isFull() {
        return entries.size() >= MAX_ITEMS;
    }

    /** 상한 검사. 이미 담긴 상품을 다시 담는 경우는 개수가 늘지 않으므로 호출부가 먼저 걸러낸다. */
    public void requireRoom() {
        if (isFull()) {
            throw new WishlistInvariantViolationException(
                    "찜은 최대 " + MAX_ITEMS + "개까지 담을 수 있습니다. 일부를 정리한 뒤 다시 시도하세요.");
        }
    }

    public boolean contains(Long productId) {
        return entries.stream().anyMatch(e -> Objects.equals(e.productId(), productId));
    }
}
