package github.lms.lemuel.common.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GhostscriptService — gs 바이너리가 없거나 입력이 유효하지 않은 환경에서 각 변환 API 가
 * GhostscriptException 으로 감싸 던지는지 검증(명령 조립 + runGs 진입 + 예외 경로 커버).
 */
class GhostscriptServiceTest {

    private final GhostscriptService service = new GhostscriptService();

    @Test
    @DisplayName("pdfToImages: 존재하지 않는 PDF → GhostscriptException")
    void pdfToImagesFails(@TempDir Path dir) {
        assertThatThrownBy(() -> service.pdfToImages(dir.resolve("nope.pdf"), dir.resolve("out"), 150))
                .isInstanceOf(GhostscriptException.class);
    }

    @Test
    @DisplayName("pdfToJpeg: 존재하지 않는 PDF → GhostscriptException")
    void pdfToJpegFails(@TempDir Path dir) {
        assertThatThrownBy(() -> service.pdfToJpeg(dir.resolve("nope.pdf"), dir.resolve("out"), 150, 80))
                .isInstanceOf(GhostscriptException.class);
    }

    @Test
    @DisplayName("compressPdf / convertToPdfA: 잘못된 입력 → GhostscriptException")
    void compressAndConvertFail(@TempDir Path dir) {
        assertThatThrownBy(() -> service.compressPdf(dir.resolve("a.pdf"), dir.resolve("b.pdf"), "ebook"))
                .isInstanceOf(GhostscriptException.class);
        assertThatThrownBy(() -> service.convertToPdfA(dir.resolve("a.pdf"), dir.resolve("b.pdf")))
                .isInstanceOf(GhostscriptException.class);
    }

    @Test
    @DisplayName("imagesToPdf: 빈 목록 → GhostscriptException(변환할 이미지 없음)")
    void imagesToPdfEmpty(@TempDir Path dir) {
        assertThatThrownBy(() -> service.imagesToPdf(List.of(), dir.resolve("out.pdf")))
                .isInstanceOf(GhostscriptException.class)
                .hasMessageContaining("이미지가 없습니다");
    }

    @Test
    @DisplayName("imagesToPdf: 존재하지 않는 이미지 → GhostscriptException")
    void imagesToPdfMissingFiles(@TempDir Path dir) {
        assertThatThrownBy(() -> service.imagesToPdf(List.of(dir.resolve("x.png")), dir.resolve("out.pdf")))
                .isInstanceOf(GhostscriptException.class);
    }

    @Test
    @DisplayName("GhostscriptException: 메시지/원인 생성자")
    void exceptionConstructors() {
        GhostscriptException e1 = new GhostscriptException("msg");
        assertThat(e1).hasMessage("msg");
        GhostscriptException e2 = new GhostscriptException("msg2", new RuntimeException("c"));
        assertThat(e2).hasMessage("msg2");
        assertThat(e2.getCause()).hasMessage("c");
    }
}
