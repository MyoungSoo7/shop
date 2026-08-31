package github.lms.lemuel.seller.application.port.out;

/**
 * 송장 등록 요청 원장 — 이 서비스가 소유한 두 테이블 중 나머지 하나.
 *
 * <p>{@link #record} 가 성공을 boolean 으로 돌려주는 이유는, 중복 판정을
 * <b>DB 유니크 제약이 하도록</b> 남겨 두기 위해서다. 애플리케이션에서 먼저 조회해 없으면 넣는
 * 방식은 동시 요청 두 개 사이에서 그대로 뚫린다 — 그리고 그 창은 사용자가 버튼을 두 번 누르는
 * 바로 그 순간이다.
 */
public interface ShipmentRequestPort {

    /**
     * @return 이번 호출이 실제로 행을 남겼으면 true, 이미 등록돼 있었으면 false
     */
    boolean record(long orderId, long sellerId, String carrier, String trackingNumber,
                   long requestedByUserId);
}
