package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.domain.TrackingNumberRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 송장 CSV 파싱 — 파일 형식만 책임진다(유효성 판정은 도메인).
 */
class TrackingNumberCsvParserTest {

    private List<TrackingNumberRegistration> parse(String csv) {
        return new TrackingNumberCsvParser()
                .parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test @DisplayName("헤더 순서가 달라도 이름으로 읽는다 — 운영자가 열을 옮겨도 깨지지 않게")
    void readsByHeaderName() {
        List<TrackingNumberRegistration> rows = parse("""
                tracking_number,order_id,carrier
                1234567890,7,CJ
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).orderId()).isEqualTo(7L);
        assertThat(rows.get(0).carrier()).isEqualTo("CJ");
        assertThat(rows.get(0).trackingNumber()).isEqualTo("1234567890");
    }

    @Test @DisplayName("필수 헤더가 없으면 파일 전체를 거절한다 — 행별로 알릴 수 없는 오류")
    void missingHeaderRejectsFile() {
        assertThatThrownBy(() -> parse("order_id,carrier\n7,CJ\n"))
                .isInstanceOf(InvalidTrackingCsvException.class)
                .hasMessageContaining("tracking_number");
    }

    @Test @DisplayName("빈 줄은 건너뛴다")
    void skipsBlankLines() {
        assertThat(parse("order_id,carrier,tracking_number\n\n7,CJ,111\n\n")).hasSize(1);
    }

    @Test @DisplayName("숫자가 아닌 주문번호는 파일을 깨지 않고 그 행만 거절한다")
    void nonNumericOrderIdBecomesInvalidRow() {
        List<TrackingNumberRegistration> rows = parse("""
                order_id,carrier,tracking_number
                ABC,CJ,111
                8,CJ,222
                """);

        assertThat(rows.get(0).valid()).isFalse();
        assertThat(rows.get(0).reason()).contains("주문번호");
        assertThat(rows.get(1).valid()).isTrue();
    }

    @Test @DisplayName("열이 모자란 행도 파일을 깨지 않는다")
    void shortRowBecomesInvalidRow() {
        List<TrackingNumberRegistration> rows = parse("""
                order_id,carrier,tracking_number
                7,CJ
                """);

        assertThat(rows.get(0).valid()).isFalse();
    }

    @Test @DisplayName("UTF-8 BOM 이 붙어도 첫 헤더를 인식한다 — 엑셀이 흔히 붙인다")
    void tolerantToUtf8Bom() {
        List<TrackingNumberRegistration> rows = parse("﻿order_id,carrier,tracking_number\n7,CJ,111\n");

        assertThat(rows.get(0).valid()).isTrue();
    }

    @Test @DisplayName("파일 안 중복 주문은 뒤엣것이 거절된 상태로 나온다")
    void duplicatesAlreadyRejected() {
        List<TrackingNumberRegistration> rows = parse("""
                order_id,carrier,tracking_number
                7,CJ,111
                7,CJ,222
                """);

        assertThat(rows.get(0).valid()).isTrue();
        assertThat(rows.get(1).valid()).isFalse();
    }
}
