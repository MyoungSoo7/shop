package github.lms.lemuel.shipping.adapter.out.carrier;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierTrackingConfigTest {

    @Test
    @DisplayName("표시명:코드 목록을 맵으로 읽는다 — 공백은 다듬는다")
    void parsesEntries() {
        Map<String, String> codes = CarrierTrackingConfig.parseCarrierCodes("CJ대한통운:04, 한진택배 : 05");

        assertThat(codes).containsExactlyInAnyOrderEntriesOf(
                Map.of("CJ대한통운", "04", "한진택배", "05"));
    }

    @Test
    @DisplayName("비었거나 null 이면 빈 맵 — 모든 택배사가 조회 대상에서 빠질 뿐 기동은 막지 않는다")
    void emptyInput() {
        assertThat(CarrierTrackingConfig.parseCarrierCodes(null)).isEmpty();
        assertThat(CarrierTrackingConfig.parseCarrierCodes("   ")).isEmpty();
    }

    @Test
    @DisplayName("형식이 어긋난 항목만 버리고 나머지는 살린다")
    void skipsMalformedEntries() {
        Map<String, String> codes = CarrierTrackingConfig.parseCarrierCodes(
                "CJ대한통운:04,코드없음,:05,한진택배:,, 롯데택배:08 ");

        assertThat(codes).containsExactlyInAnyOrderEntriesOf(
                Map.of("CJ대한통운", "04", "롯데택배", "08"));
    }

    @Test
    @DisplayName("켰는데 설정이 비어 있으면 기동 대신 꺼진 어댑터를 만든다")
    void enabledButUnconfiguredYieldsDisabledAdapter() {
        HttpCarrierTrackingAdapter adapter =
                new CarrierTrackingConfig().httpCarrierTrackingAdapter("", "", "");

        assertThat(adapter.enabled()).isFalse();
        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");
        assertThat(result.available()).isFalse();
    }
}
