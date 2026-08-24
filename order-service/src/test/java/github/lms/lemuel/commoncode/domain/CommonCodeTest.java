package github.lms.lemuel.commoncode.domain;
import github.lms.lemuel.commoncode.domain.exception.CommonCodeInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonCodeTest {

    @Test @DisplayName("create - groupCode/code 는 대문자화, label 은 트림")
    void create_normalizes() {
        CommonCode code = CommonCode.create(" order_status ", " paid ", "  결제완료  ", 2, "extra");

        assertThat(code.getGroupCode()).isEqualTo("ORDER_STATUS");
        assertThat(code.getCode()).isEqualTo("PAID");
        assertThat(code.getLabel()).isEqualTo("결제완료");
        assertThat(code.getSortOrder()).isEqualTo(2);
        assertThat(code.getExtra1()).isEqualTo("extra");
        assertThat(code.isActive()).isTrue();
        assertThat(code.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create - 필수값 누락 시 예외")
    void create_requiresFields() {
        assertThatThrownBy(() -> CommonCode.create(null, "C", "L", 0, null))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
        assertThatThrownBy(() -> CommonCode.create("G", " ", "L", 0, null))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
        assertThatThrownBy(() -> CommonCode.create("G", "C", "", 0, null))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test @DisplayName("update - label/sortOrder/active/extra 갱신")
    void update() {
        CommonCode code = CommonCode.create("G", "C", "old", 0, null);
        code.update("  새라벨  ", 7, false, "x2");

        assertThat(code.getLabel()).isEqualTo("새라벨");
        assertThat(code.getSortOrder()).isEqualTo(7);
        assertThat(code.isActive()).isFalse();
        assertThat(code.getExtra1()).isEqualTo("x2");
    }

    @Test @DisplayName("update - label 이 비어 있으면 예외")
    void update_blankLabel() {
        CommonCode code = CommonCode.create("G", "C", "old", 0, null);
        assertThatThrownBy(() -> code.update("  ", 0, true, null))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test @DisplayName("rehydrate/assignId - 식별자 복원")
    void setters() {
        CommonCode code = CommonCode.rehydrate(1L, "G", "X", "L", 0, true, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        assertThat(code.getId()).isEqualTo(1L);
        assertThat(code.getCode()).isEqualTo("X");
    }
}
