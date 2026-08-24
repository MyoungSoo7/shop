package github.lms.lemuel.operation.board.adapter.out.image;

import github.lms.lemuel.operation.board.application.port.out.GenerateThumbnailPort.Thumbnail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축소본 생성기 — 실제 ImageIO 로 돌린다(목이 아니다).
 *
 * <p>이 클래스가 지키는 것은 <b>"못 만드는 경우가 예외가 아니라 값"</b>이라는 계약이다.
 * 여기서 예외가 새면 썸네일이라는 부가 기능이 첨부 업로드 전체를 죽인다.
 */
class ImageIoThumbnailGeneratorTest {

    private final ImageIoThumbnailGenerator generator = new ImageIoThumbnailGenerator();

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("큰 이미지는 긴 변을 맞춰 줄이고 비율을 지킨다")
    void scalesDownKeepingRatio() throws IOException {
        Optional<Thumbnail> result = generator.generate(png(800, 400), "png", 200);

        assertThat(result).isPresent();
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(result.get().content()));
        assertThat(scaled.getWidth()).isEqualTo(200);
        assertThat(scaled.getHeight()).isEqualTo(100);
        assertThat(result.get().extension()).isEqualTo("png");
    }

    @Test
    @DisplayName("세로가 더 긴 이미지도 긴 변 기준으로 줄인다")
    void scalesByLongestEdge() throws IOException {
        Optional<Thumbnail> result = generator.generate(png(300, 900), "png", 300);

        assertThat(result).isPresent();
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(result.get().content()));
        assertThat(scaled.getHeight()).isEqualTo(300);
        assertThat(scaled.getWidth()).isEqualTo(100);
    }

    @Test
    @DisplayName("이미 작은 이미지는 만들지 않는다 — 늘려 봐야 용량만 는다")
    void skipsSmallImage() throws IOException {
        assertThat(generator.generate(png(100, 80), "png", 400)).isEmpty();
        // 경계: 긴 변이 정확히 상한이면 그대로 둔다
        assertThat(generator.generate(png(400, 200), "png", 400)).isEmpty();
    }

    @Test
    @DisplayName("리더가 없는 형식·손상 바이트는 예외 대신 빈 값 — 업로드를 죽이지 않는다")
    void unreadableInputYieldsEmpty() {
        assertThat(generator.generate("not an image".getBytes(StandardCharsets.UTF_8), "webp", 400)).isEmpty();
        assertThat(generator.generate(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "jpg", 400)).isEmpty();
    }

    @Test
    @DisplayName("빈 입력·잘못된 상한은 빈 값")
    void guardsInput() {
        assertThat(generator.generate(null, "png", 400)).isEmpty();
        assertThat(generator.generate(new byte[0], "png", 400)).isEmpty();
    }

    @Test
    @DisplayName("상한이 0 이하면 만들지 않는다")
    void nonPositiveEdge() throws IOException {
        assertThat(generator.generate(png(800, 400), "png", 0)).isEmpty();
    }
}
