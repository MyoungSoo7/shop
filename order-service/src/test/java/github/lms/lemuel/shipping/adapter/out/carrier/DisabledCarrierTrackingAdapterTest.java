package github.lms.lemuel.shipping.adapter.out.carrier;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연동이 없는 배포의 기본 어댑터.
 *
 * <p>이 어댑터가 있어야 <b>설정 한 줄 없이도</b> 배송 추적 화면이 뜬다 — 택배사 연동은 얹는
 * 것이지 전제가 아니라는 결정이 코드로 성립하는 자리다.
 */
class DisabledCarrierTrackingAdapterTest {

    private final DisabledCarrierTrackingAdapter adapter = new DisabledCarrierTrackingAdapter();

    @Test
    @DisplayName("항상 꺼져 있고, 불러도 사유만 돌려준다")
    void alwaysDisabled() {
        assertThat(adapter.enabled()).isFalse();

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.available()).isFalse();
        assertThat(result.scans()).isEmpty();
        assertThat(result.unavailableReason()).isEqualTo(DisabledCarrierTrackingAdapter.REASON);
    }
}
