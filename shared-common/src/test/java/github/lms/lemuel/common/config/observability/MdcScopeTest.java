package github.lms.lemuel.common.config.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcScopeTest {

    @Test
    @DisplayName("블록 내에서 MDC 설정, 종료 시 정리")
    void setsAndClearsMdc() {
        assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isNull();

        try (var ignore = MdcScope.of(MdcKeys.PAYMENT_ID, "123")) {
            assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isEqualTo("123");
        }

        assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isNull();
    }

    @Test
    @DisplayName("기존 값이 있으면 블록 종료 시 복원")
    void restoresPreviousValue() {
        MDC.put(MdcKeys.PAYMENT_ID, "outer");
        try {
            try (var ignore = MdcScope.of(MdcKeys.PAYMENT_ID, "inner")) {
                assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isEqualTo("inner");
            }
            assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isEqualTo("outer");
        } finally {
            MDC.remove(MdcKeys.PAYMENT_ID);
        }
    }
}
