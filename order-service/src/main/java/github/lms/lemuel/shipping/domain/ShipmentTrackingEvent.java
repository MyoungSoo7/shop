package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송 추적 이벤트 한 줄 — "언제 무슨 일이 있었는가".
 *
 * <p><b>왜 별도 이력이 필요한가.</b> {@link Shipment} 에는 시각이 {@code shippedAt} 과
 * {@code deliveredAt} 둘뿐이다. 그 사이에 일어난 일(출고 준비, 택배사 첫 스캔, 배송지 변경)은
 * 상태 칸을 덮어쓰며 지나가고 아무 흔적도 남기지 않는다. 결과적으로 고객이 보는 것은 상태
 * <b>단어 하나</b>이고, "언제부터 이 상태였는지"도 "왜 아직 안 움직이는지"도 알 수 없다.
 * 문의가 들어오면 운영자도 답할 근거가 없다.
 *
 * <p><b>택배사 API 가 전제가 아니다.</b> 이 이력은 우리 시스템의 상태 전이만으로 성립한다.
 * 택배사 연동은 그 위에 얹는 것이지, 없으면 타임라인이 비는 구조가 아니다 — 연동이 꺼져 있거나
 * 조회가 실패해도 고객은 최소한 우리가 아는 사실을 시간순으로 본다.
 *
 * @param id          저장된 이력의 식별자. 아직 저장 전이거나 택배사 스캔이면 {@code null}
 * @param orderId     주문 식별자. 배송은 주문과 1:1 이라 배송 id 대신 주문 id 로 묶는다
 *                    (배송 id 는 저장 시점에야 정해지지만 주문 id 는 생성 순간부터 있다)
 * @param status      이 시점의 배송 상태
 * @param source      출처 — 우리 시스템인지 택배사인지
 * @param description 사람이 읽는 설명. 택배사 이벤트면 택배사가 준 문구를 그대로 싣는다
 * @param location    택배사 스캔 위치(집화·중계 지점). 내부 이벤트에는 없다
 * @param occurredAt  실제로 일어난 시각. 저장 시각이 아니다 — 택배사 스캔은 한참 뒤에 조회된다
 */
public record ShipmentTrackingEvent(
        Long id,
        Long orderId,
        ShippingStatus status,
        TrackingEventSource source,
        String description,
        String location,
        LocalDateTime occurredAt) {

    public ShipmentTrackingEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (description == null || description.isBlank()) {
            // 설명 없는 줄은 화면에서 빈 칸으로 보인다 — 아무 일도 없었던 것과 구분이 안 된다.
            throw new ShipmentInvariantViolationException("description 필수");
        }
    }

    /** 우리 시스템의 상태 전이. 일어난 그 순간이 곧 발생 시각이다. */
    public static ShipmentTrackingEvent internal(Long orderId, ShippingStatus status, String description) {
        return new ShipmentTrackingEvent(null, orderId, status, TrackingEventSource.INTERNAL,
                description, null, LocalDateTime.now());
    }

    /** 저장된 이력을 되살릴 때. */
    public static ShipmentTrackingEvent rehydrate(Long id, Long orderId, ShippingStatus status,
                                                  TrackingEventSource source, String description,
                                                  String location, LocalDateTime occurredAt) {
        return new ShipmentTrackingEvent(id, orderId, status, source, description, location, occurredAt);
    }

    /**
     * 택배사 스캔 기록. 저장하지 않으므로 id 가 없고, 발생 시각은 택배사가 알려준 값을 쓴다
     * (조회 시각으로 바꿔 달면 순서가 뒤엉킨다).
     */
    public static ShipmentTrackingEvent carrier(Long orderId, ShippingStatus status, String description,
                                                String location, LocalDateTime occurredAt) {
        return new ShipmentTrackingEvent(null, orderId, status, TrackingEventSource.CARRIER,
                description, location, occurredAt);
    }

    public ShipmentTrackingEvent withId(Long assignedId) {
        return new ShipmentTrackingEvent(assignedId, orderId, status, source, description, location, occurredAt);
    }
}
