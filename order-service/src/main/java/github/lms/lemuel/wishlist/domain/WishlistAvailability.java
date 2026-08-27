package github.lms.lemuel.wishlist.domain;

/**
 * 찜한 상품을 <b>지금 살 수 있는가</b>, 없다면 <b>왜인가</b>.
 *
 * <p><b>왜 사유를 값으로 들고 있나.</b> 이식 대상이던 레거시 찜 목록은 살 수 없는 상품을 조회
 * 쿼리에서 아예 걸러냈다({@code PRD.PRABSYN NOT IN (5, 6)}, {@code stock > 0}). 사용자 입장에서는
 * 찜해 둔 물건이 <i>말없이 사라지는</i> 것이고, 품절이라 잠깐 빠진 것인지 단종이라 영영 없는 것인지
 * 구분할 방법이 없었다. 기다리려고 찜한 사람에게 그 구분은 목록에 있고 없고보다 중요하다.
 *
 * <p>그래서 여기서는 <b>거르지 않고 사유를 붙인다.</b> 화면은 전부 보여 주고, 각 줄이 왜 회색인지
 * 말한다.
 *
 * <p>{@link #isGone()} 이 참인 것만 일괄 정리의 대상이다. 품절은 포함하지 않는다 — 재입고를
 * 기다리는 것이 찜의 존재 이유인데, 그 사이에 지워 버리면 기능이 스스로를 무효화한다.
 * (레거시의 일괄 정리는 재고 0 인 행까지 함께 지웠고, 그 행들은 목록에서 숨겨져 있었으므로
 * 사용자는 자기가 무엇을 지우는지 볼 수 없었다.)
 */
public enum WishlistAvailability {

    /** 지금 구매 가능. */
    AVAILABLE("구매 가능"),

    /** 일시 품절 — 재입고되면 다시 살 수 있다. 정리 대상이 아니다. */
    OUT_OF_STOCK("품절"),

    /** 판매자가 내린 상품. 다시 올릴 수 있으므로 정리 대상이 아니다. */
    NOT_SELLING("판매 중지"),

    /** 단종 — 되살아나지 않는다. */
    DISCONTINUED("단종"),

    /** 상품 자체가 사라졌다(삭제). 찜 행만 남아 참조가 깨진 상태. */
    REMOVED("삭제된 상품");

    private final String label;

    WishlistAvailability(String label) {
        this.label = label;
    }

    /** 사용자에게 보여 줄 사유. enum 이름을 그대로 노출하지 않는다. */
    public String label() {
        return label;
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }

    /**
     * 되살아날 여지가 없는가. 일괄 정리는 <b>이것만</b> 지운다.
     *
     * <p>품절·판매 중지는 판매자의 조작 하나로 되돌아오므로 제외한다. 사용자가 명시적으로
     * 한 건씩 지우는 것은 언제나 가능하다.
     */
    public boolean isGone() {
        return this == DISCONTINUED || this == REMOVED;
    }
}
