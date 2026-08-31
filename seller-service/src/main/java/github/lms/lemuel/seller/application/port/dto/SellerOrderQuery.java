package github.lms.lemuel.seller.application.port.dto;

import java.time.LocalDate;

/**
 * 셀러 주문 목록 조회 조건.
 *
 * <p><b>셀러 식별자가 여기에 없다는 점이 이 타입의 요점이다.</b> 조회 대상은 항상
 * {@code SellerScope} 가 정하고, 이 레코드는 "그 안에서 무엇을 볼지" 만 담는다.
 *
 * @param unshippedOnly 송장이 아직 없는 건만. 셀러 백오피스에서 실제로 매일 쓰는 필터는 이것
 *                      하나뿐이라 기간·주문번호와 달리 화면 기본값이 true 다.
 */
public record SellerOrderQuery(LocalDate from, LocalDate to, Long orderId,
                               boolean unshippedOnly, int page, int size) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;
    /** 기본 조회 구간. 기간을 안 주면 최근 30일. */
    public static final int DEFAULT_DAYS = 30;

    /**
     * 값을 안전 범위로 접는다.
     *
     * <p>거절하지 않고 접는 이유는 이 조건들이 사용자가 손으로 만든 게 아니라 화면이 만든 것이고,
     * 여기서 400 을 내면 사용자는 고칠 방법이 없기 때문이다. 다만 <b>기간 역전은 접지 않고</b>
     * 그대로 둔다 — 조용히 뒤집으면 사용자가 고른 것과 다른 결과를 사실처럼 보여주게 된다.
     */
    public SellerOrderQuery normalized(LocalDate today) {
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new SellerOrderQuery(start, end, orderId, unshippedOnly, safePage, safeSize);
    }
}
