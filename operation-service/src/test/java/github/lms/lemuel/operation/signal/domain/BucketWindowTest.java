package github.lms.lemuel.operation.signal.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BucketWindowTest {

    @Test
    void 같은_5분_창의_시각들은_동일한_버킷_시작으로_정렬된다() {
        Instant a = Instant.parse("2026-07-07T06:02:11Z");
        Instant b = Instant.parse("2026-07-07T06:04:59Z");
        Instant expected = Instant.parse("2026-07-07T06:00:00Z");

        assertThat(BucketWindow.floor(a, 300)).isEqualTo(expected);
        assertThat(BucketWindow.floor(b, 300)).isEqualTo(expected);
    }

    @Test
    void 버킷_경계_시각은_다음_버킷으로_넘어간다() {
        assertThat(BucketWindow.floor(Instant.parse("2026-07-07T06:05:00Z"), 300))
                .isEqualTo(Instant.parse("2026-07-07T06:05:00Z"));
        assertThat(BucketWindow.floor(Instant.parse("2026-07-07T06:04:59Z"), 300))
                .isEqualTo(Instant.parse("2026-07-07T06:00:00Z"));
    }

    @Test
    void 나노초는_제거된다() {
        Instant withNanos = Instant.parse("2026-07-07T06:00:03.987654321Z");
        assertThat(BucketWindow.floor(withNanos, 300))
                .isEqualTo(Instant.parse("2026-07-07T06:00:00Z"));
    }

    @Test
    void bucketSeconds_는_양수여야_한다() {
        assertThatThrownBy(() -> BucketWindow.floor(Instant.now(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BucketWindow.floor(Instant.now(), -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
