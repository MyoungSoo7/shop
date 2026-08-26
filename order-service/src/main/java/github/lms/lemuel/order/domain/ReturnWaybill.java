package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

/**
 * 송장 한 장 — 택배사와 운송장 번호.
 *
 * <p>같은 모양이 두 방향으로 쓰인다: <b>회수</b>(고객 → 판매자, 반품·교환 공통)와
 * <b>교환 재배송</b>(판매자 → 고객). 방향은 이 값이 아니라 어느 칸에 담기느냐가 정한다.
 *
 * <p>이 값이 있기 전에는 "고객이 물건을 보냈는가"가 어디에도 없어 회수 확인이 전화 통화였다.
 * 출고 송장({@code shipping.domain.TrackingNumberRegistration})과 타입을 공유하지 않는 이유는
 * 그쪽이 배송 슬라이스의 등록 명령이라 주문 ID·검증 결과를 함께 들고 다니기 때문이다.
 */
public record ReturnWaybill(String carrier, String trackingNumber) {

    private static final int MAX_CARRIER = 40;
    private static final int MAX_TRACKING_NUMBER = 60;

    public ReturnWaybill {
        carrier = require(carrier, "택배사", MAX_CARRIER);
        trackingNumber = require(trackingNumber, "운송장 번호", MAX_TRACKING_NUMBER);
    }

    /** 둘 다 비어 있으면 {@code null}(아직 송장이 없는 신청). 한쪽만 있으면 예외다. */
    public static ReturnWaybill ofNullable(String carrier, String trackingNumber) {
        if (isBlank(carrier) && isBlank(trackingNumber)) {
            return null;
        }
        return new ReturnWaybill(carrier, trackingNumber);
    }

    private static String require(String value, String label, int maxLength) {
        if (isBlank(value)) {
            throw new OrderInvariantViolationException(label + " 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
