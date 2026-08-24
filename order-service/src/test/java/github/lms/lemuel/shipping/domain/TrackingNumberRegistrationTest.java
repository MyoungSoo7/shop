package github.lms.lemuel.shipping.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 송장 일괄 등록 1행의 유효성 판정 — 순수 도메인.
 *
 * <p>업로드는 수백 행이 한꺼번에 들어오므로, 한 행이 잘못됐다고 전체를 거절하면 운영자는 무엇이
 * 틀렸는지 모른 채 파일만 다시 만들게 된다. 행마다 통과/사유를 남겨 미리보기에서 그대로 보여준다.
 */
class TrackingNumberRegistrationTest {

    @Test @DisplayName("정상 행은 통과한다")
    void validRow() {
        TrackingNumberRegistration row = TrackingNumberRegistration.of(7L, "CJ", "1234567890");

        assertThat(row.valid()).isTrue();
        assertThat(row.reason()).isNull();
    }

    @Test @DisplayName("주문번호가 없으면 거절 — 어느 배송인지 특정할 수 없다")
    void missingOrderId() {
        assertThat(TrackingNumberRegistration.of(null, "CJ", "123").valid()).isFalse();
    }

    @Test @DisplayName("택배사·운송장이 비면 거절")
    void missingCarrierOrTracking() {
        assertThat(TrackingNumberRegistration.of(7L, "", "123").valid()).isFalse();
        assertThat(TrackingNumberRegistration.of(7L, "CJ", "  ").valid()).isFalse();
        assertThat(TrackingNumberRegistration.of(7L, null, null).valid()).isFalse();
    }

    @Test @DisplayName("앞뒤 공백은 다듬어 저장한다 — 엑셀 복사가 흔히 공백을 끌고 온다")
    void trimsWhitespace() {
        TrackingNumberRegistration row = TrackingNumberRegistration.of(7L, " CJ ", " 1234567890 ");

        assertThat(row.carrier()).isEqualTo("CJ");
        assertThat(row.trackingNumber()).isEqualTo("1234567890");
    }

    @Test @DisplayName("거절 사유는 사람이 읽고 고칠 수 있어야 한다")
    void reasonIsActionable() {
        assertThat(TrackingNumberRegistration.of(7L, "CJ", "").reason()).contains("운송장");
        assertThat(TrackingNumberRegistration.of(null, "CJ", "123").reason()).contains("주문");
    }

    // ── 파일 단위 규칙 ──

    @Test @DisplayName("같은 주문이 두 번 오면 뒤엣것을 거절한다 — 어느 운송장이 맞는지 알 수 없다")
    void duplicateOrderIsRejected() {
        List<TrackingNumberRegistration> rows = TrackingNumberRegistration.rejectDuplicates(List.of(
                TrackingNumberRegistration.of(7L, "CJ", "111"),
                TrackingNumberRegistration.of(8L, "CJ", "222"),
                TrackingNumberRegistration.of(7L, "CJ", "333")));

        assertThat(rows.get(0).valid()).isTrue();
        assertThat(rows.get(1).valid()).isTrue();
        assertThat(rows.get(2).valid()).isFalse();
        assertThat(rows.get(2).reason()).contains("중복");
    }

    @Test @DisplayName("이미 거절된 행은 중복 판정의 기준이 되지 않는다")
    void invalidRowDoesNotClaimTheOrder() {
        List<TrackingNumberRegistration> rows = TrackingNumberRegistration.rejectDuplicates(List.of(
                TrackingNumberRegistration.of(7L, "CJ", ""),      // 무효
                TrackingNumberRegistration.of(7L, "CJ", "333")));  // 유효 — 중복 아님

        assertThat(rows.get(1).valid()).isTrue();
    }
}
