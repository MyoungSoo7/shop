package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 정책(형식·크기)이 저장 기술이 아니라 도메인에서 강제되는지 검증한다.
 * 파일시스템도 스프링 컨텍스트도 필요 없다 — 순수 POJO 테스트.
 */
class ImageUploadTest {

    private static final byte[] BYTES = new byte[]{1, 2, 3};

    private ImageUpload upload(String contentType, long size) {
        return ImageUpload.of("a.jpg", contentType, size, BYTES);
    }

    @Test
    @DisplayName("허용 형식(jpeg/jpg/png/webp)은 생성된다")
    void allowedContentTypes() {
        assertThat(upload("image/jpeg", 1024L).getContentType()).isEqualTo("image/jpeg");
        assertThat(upload("image/jpg", 1024L).getContentType()).isEqualTo("image/jpg");
        assertThat(upload("image/png", 1024L).getContentType()).isEqualTo("image/png");
        assertThat(upload("image/webp", 1024L).getContentType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("허용되지 않은 형식(gif)은 생성 자체가 거부된다")
    void rejectsDisallowedContentType() {
        assertThatThrownBy(() -> upload("image/gif", 1024L))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("Invalid image type");
    }

    @Test
    @DisplayName("contentType null 은 거부된다")
    void rejectsNullContentType() {
        assertThatThrownBy(() -> upload(null, 1024L))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("Invalid image type");
    }

    @Test
    @DisplayName("크기 경계 — 정확히 5MB 는 허용")
    void allowsExactlyMaxSize() {
        assertThat(upload("image/jpeg", 5L * 1024 * 1024).getSizeBytes()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    @DisplayName("크기 경계 — 5MB + 1바이트는 거부")
    void rejectsOverMaxSize() {
        assertThatThrownBy(() -> upload("image/jpeg", 5L * 1024 * 1024 + 1))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    @DisplayName("크기 0 과 음수는 거부")
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> upload("image/jpeg", 0L))
                .isInstanceOf(ProductInvariantViolationException.class);
        assertThatThrownBy(() -> upload("image/jpeg", -1L))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test
    @DisplayName("확장자 추출 — 없으면 빈 문자열")
    void extension() {
        assertThat(ImageUpload.of("photo.PNG", "image/png", 10L, BYTES).extension()).isEqualTo(".PNG");
        assertThat(ImageUpload.of("noext", "image/png", 10L, BYTES).extension()).isEmpty();
        assertThat(ImageUpload.of(null, "image/png", 10L, BYTES).extension()).isEmpty();
    }

    @Test
    @DisplayName("content 는 방어적 복사 — 반환 배열을 바꿔도 내부는 불변")
    void contentIsDefensivelyCopied() {
        byte[] source = new byte[]{9, 9};
        ImageUpload up = ImageUpload.of("a.png", "image/png", 2L, source);

        source[0] = 0;
        byte[] returned = up.getContent();
        returned[1] = 0;

        assertThat(up.getContent()).containsExactly(9, 9);
    }
}
