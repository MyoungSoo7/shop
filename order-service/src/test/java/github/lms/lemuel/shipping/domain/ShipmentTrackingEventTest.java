package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 추적 이벤트의 불변식.
 *
 * <p>여기서 막는 것은 "빈 줄"이다. 설명 없는 이벤트는 화면에서 빈 칸으로 보이고, 사용자는
 * 그것을 무슨 일이 있었는지 알 수 없는 사건이 아니라 <i>아무 일도 아닌 것</i>으로 읽는다.
 */
class ShipmentTrackingEventTest {

    @Test
    @DisplayName("internal: 내부 전이는 발생 시각을 지금으로 찍고 위치가 없다")
    void internal() {
        LocalDateTime before = LocalDateTime.now();

        ShipmentTrackingEvent event = ShipmentTrackingEvent.internal(7L, ShippingStatus.SHIPPED, "인계했습니다.");

        assertThat(event.id()).isNull();
        assertThat(event.orderId()).isEqualTo(7L);
        assertThat(event.status()).isEqualTo(ShippingStatus.SHIPPED);
        assertThat(event.source()).isEqualTo(TrackingEventSource.INTERNAL);
        assertThat(event.location()).isNull();
        assertThat(event.occurredAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("carrier: 택배사 스캔은 위치와 발생 시각을 그대로 보존한다")
    void carrier() {
        LocalDateTime scannedAt = LocalDateTime.of(2026, 8, 20, 9, 30);

        ShipmentTrackingEvent event = ShipmentTrackingEvent.carrier(7L, ShippingStatus.IN_TRANSIT,
                "간선상차", "동서울허브", scannedAt);

        assertThat(event.source()).isEqualTo(TrackingEventSource.CARRIER);
        assertThat(event.location()).isEqualTo("동서울허브");
        assertThat(event.occurredAt()).isEqualTo(scannedAt);
    }

    @Test
    @DisplayName("설명이 비면 거부한다 — 빈 줄은 '아무 일도 없었다'로 읽힌다")
    void blankDescriptionRejected() {
        assertThatThrownBy(() -> ShipmentTrackingEvent.internal(7L, ShippingStatus.PENDING, "  "))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> ShipmentTrackingEvent.internal(7L, ShippingStatus.PENDING, null))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("orderId·status·source·occurredAt 은 필수")
    void requiredFields() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> ShipmentTrackingEvent.rehydrate(1L, null, ShippingStatus.PENDING,
                TrackingEventSource.INTERNAL, "x", null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShipmentTrackingEvent.rehydrate(1L, 7L, null,
                TrackingEventSource.INTERNAL, "x", null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShipmentTrackingEvent.rehydrate(1L, 7L, ShippingStatus.PENDING,
                null, "x", null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShipmentTrackingEvent.rehydrate(1L, 7L, ShippingStatus.PENDING,
                TrackingEventSource.INTERNAL, "x", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("withId: 나머지 값은 그대로 두고 식별자만 붙인다")
    void withId() {
        ShipmentTrackingEvent event = ShipmentTrackingEvent.internal(7L, ShippingStatus.READY, "준비 중");

        ShipmentTrackingEvent assigned = event.withId(42L);

        assertThat(assigned.id()).isEqualTo(42L);
        assertThat(assigned.orderId()).isEqualTo(event.orderId());
        assertThat(assigned.description()).isEqualTo(event.description());
        assertThat(assigned.occurredAt()).isEqualTo(event.occurredAt());
    }
}
