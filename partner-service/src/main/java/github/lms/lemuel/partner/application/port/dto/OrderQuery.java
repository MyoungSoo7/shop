package github.lms.lemuel.partner.application.port.dto;

import java.time.LocalDate;

/**
 * 주문 목록 조회 조건.
 *
 * <p><b>셀러 식별자가 여기에 없다는 점이 이 타입의 요점이다.</b> 조회 대상은 항상
 * {@code PartnerScope} 가 정하고, 이 레코드는 "그 안에서 무엇을 볼지" 만 담는다. 필터에
 * {@code sellerId} 를 넣는 순간 그 값을 요청에서 채우는 코드가 언젠가 생긴다.
 *
 * <p>{@link #normalized()} 로 상한을 강제한다. 열어 두면 {@code size=100000} 한 번에
 * 백오피스가 멎는다 — 레퍼런스에서 실제로 그랬다.
 */
public record OrderQuery(LocalDate from, LocalDate to, Long orderId, int page, int size) {

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
     * 이 경우 결과는 비고, 화면은 사용자가 고른 기간을 그대로 다시 보여준다.
     */
    public OrderQuery normalized(LocalDate today) {
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new OrderQuery(start, end, orderId, safePage, safeSize);
    }
}
