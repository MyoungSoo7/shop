package github.lms.lemuel.common.config.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PIIMaskingConverterTest {

    @Test
    @DisplayName("이메일 — 도메인/로컬 파트 앞글자만 노출")
    void masksEmail() {
        String out = PIIMaskingConverter.mask("contact user@example.com for details");
        assertThat(out).isEqualTo("contact u***@e***.com for details");
    }

    @Test
    @DisplayName("전화번호 — 중간 4자리 마스킹")
    void masksPhone() {
        assertThat(PIIMaskingConverter.mask("call 010-1234-5678 now"))
                .isEqualTo("call 010-****-5678 now");
        // 하이픈 없는 형태도 동일하게 처리
        assertThat(PIIMaskingConverter.mask("call 01012345678 now"))
                .isEqualTo("call 010-****-5678 now");
    }

    @Test
    @DisplayName("카드번호 — BIN 6자리 + 말미 4자리만 노출")
    void masksCard() {
        String out = PIIMaskingConverter.mask("card 1234-5678-9012-3456 expired");
        assertThat(out).isEqualTo("card 123456******3456 expired");
    }

    @Test
    @DisplayName("민감정보 없음 — 원문 그대로")
    void passesThroughBenignText() {
        String original = "payment created successfully paymentId=42 amount=50000";
        assertThat(PIIMaskingConverter.mask(original)).isEqualTo(original);
    }

    @Test
    @DisplayName("여러 유형 혼합 마스킹")
    void masksMultipleTypes() {
        String raw = "user john.doe@corp.com phone 010-9999-0000 card 4111-1111-1111-1111 expired";
        String out = PIIMaskingConverter.mask(raw);
        assertThat(out).doesNotContain("john.doe");
        assertThat(out).doesNotContain("9999-0000"); // 중간 4자리 마스킹 후 사라져야 함
        assertThat(out).doesNotContain("4111-1111-1111-1111");
        assertThat(out).contains("411111"); // BIN
        assertThat(out).contains("1111");    // last 4
    }

    @Test
    @DisplayName("null 안전")
    void nullSafe() {
        assertThat(PIIMaskingConverter.mask(null)).isNull();
        assertThat(PIIMaskingConverter.mask("")).isEmpty();
    }
}
