package github.lms.lemuel.operation.board.adapter.out.image;

import github.lms.lemuel.operation.board.application.port.out.GenerateThumbnailPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * JDK ImageIO 기반 축소본 생성기.
 *
 * <p>외부 라이브러리를 붙이지 않는다 — 필요한 것은 "긴 변을 줄인다" 하나뿐이고, 그 대가로
 * 네이티브 의존(libwebp 등)을 컨테이너에 들이면 이미지가 무거워지고 빌드가 플랫폼을 탄다.
 *
 * <p><b>못 만드는 경우가 정상이다</b>:
 * <ul>
 *   <li>WEBP — JDK ImageIO 에 리더가 없다. {@code ImageIO.read} 가 {@code null} 을 준다.</li>
 *   <li>손상·잘린 이미지 — 예외가 난다.</li>
 * </ul>
 * 둘 다 비어 있는 값을 돌려주고 <b>예외를 밖으로 내보내지 않는다</b>. 썸네일은 부가 기능이라
 * 이것 때문에 첨부 업로드가 실패하면 안 된다.
 *
 * <p>출력은 항상 PNG 다. 원본 형식을 따라가면 JPEG 재인코딩 품질·투명도 손실을 신경 써야 하는데,
 * 목록 썸네일 크기에서 그 차이는 의미가 없고 PNG 는 어디서나 읽힌다.
 */
@Slf4j
@Component
public class ImageIoThumbnailGenerator implements GenerateThumbnailPort {

    private static final String OUTPUT_FORMAT = "png";

    @Override
    public Optional<Thumbnail> generate(byte[] source, String extension, int maxEdge) {
        if (source == null || source.length == 0 || maxEdge <= 0) {
            return Optional.empty();
        }
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
            if (original == null) {
                // 리더가 없는 형식(WEBP 등). 실패가 아니라 "이 형식은 축소본이 없다"는 사실이다.
                log.debug("썸네일 리더 없음 — 원본으로 서빙한다: extension={}", extension);
                return Optional.empty();
            }
            if (original.getWidth() <= maxEdge && original.getHeight() <= maxEdge) {
                // 이미 충분히 작다 — 늘려 봐야 용량만 늘고 화질은 나아지지 않는다.
                return Optional.empty();
            }
            return Optional.of(new Thumbnail(encode(scale(original, maxEdge)), OUTPUT_FORMAT));
        } catch (IOException | RuntimeException e) {
            log.warn("썸네일 생성 실패 — 원본으로 서빙한다: extension={}", extension, e);
            return Optional.empty();
        }
    }

    private static BufferedImage scale(BufferedImage original, int maxEdge) {
        int width = original.getWidth();
        int height = original.getHeight();
        double ratio = (double) maxEdge / Math.max(width, height);
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        // TYPE_INT_ARGB 로 그려 투명 PNG 의 배경이 검게 뭉개지지 않게 한다.
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, OUTPUT_FORMAT, out);
        return out.toByteArray();
    }
}
