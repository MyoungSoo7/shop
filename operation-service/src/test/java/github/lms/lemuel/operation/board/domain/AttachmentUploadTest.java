package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 검증 테스트.
 *
 * <p>첨부 사고는 거의 전부 <b>"선언과 실제가 다르다"</b>에서 온다 — 확장자만 바꾼 파일, 이미지인
 * 척하는 스크립트 문서, 헤더를 쪼개는 파일명. 이 클래스가 지키는 것은 그 간극이다.
 */
class AttachmentUploadTest {

    private static final DetectedFileType JPEG = DetectedFileType.of("jpg", "image/jpeg", true, "jpeg");
    private static final DetectedFileType PDF = DetectedFileType.of("pdf", "application/pdf", false);
    private static final DetectedFileType ZIP =
            DetectedFileType.of("zip", "application/zip", false, "docx", "xlsx", "pptx");

    @Test
    @DisplayName("같은 형식의 다른 이름은 통과한다 — photo.jpeg 가 막히면 규칙이 사람을 이긴 것")
    void aliasPasses() {
        BoardAttachmentPolicy jpgOnly = BoardAttachmentPolicy.enabled(3, 100, List.of("jpg"));

        assertThatCode(() -> new AttachmentUpload("photo.jpeg", 1024, JPEG).validateAgainst(jpgOnly))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ZIP 기반 문서(docx)는 별칭으로 통과하되 정책이 허용해야 한다")
    void zipBasedDocument() {
        BoardAttachmentPolicy docs = BoardAttachmentPolicy.enabled(3, 100, List.of("docx"));

        assertThatCode(() -> new AttachmentUpload("보고서.docx", 2048, ZIP).validateAgainst(docs))
                .doesNotThrowAnyException();

        BoardAttachmentPolicy imagesOnly = BoardAttachmentPolicy.enabled(3, 100, List.of("jpg"));
        assertThatThrownBy(() -> new AttachmentUpload("보고서.docx", 2048, ZIP).validateAgainst(imagesOnly))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("허용하지 않는");
    }

    private static BoardAttachmentPolicy policy() {
        return BoardAttachmentPolicy.enabled(3, 100, List.of("jpg", "png", "pdf"));
    }

    @Test
    @DisplayName("정상 업로드 — 판정 결과가 종류를 정한다")
    void valid() {
        AttachmentUpload upload = new AttachmentUpload("photo.jpg", 1024, JPEG);

        assertThatCode(() -> upload.validateAgainst(policy())).doesNotThrowAnyException();
        assertThat(upload.kind()).isEqualTo(BoardAttachmentKind.IMAGE);
        assertThat(new AttachmentUpload("doc.pdf", 1024, PDF).kind()).isEqualTo(BoardAttachmentKind.FILE);
    }

    @Test
    @DisplayName("확장자만 바꿔 올린 파일은 거절한다 — 서버가 본 것과 다르면 무조건")
    void extensionMismatch() {
        AttachmentUpload disguised = new AttachmentUpload("shell.jpg", 1024, PDF);

        assertThatThrownBy(() -> disguised.validateAgainst(policy()))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("확장자와 다릅니다");
    }

    @Test
    @DisplayName("형식을 알아볼 수 없으면 거절한다 — 서버가 모르는 바이트를 브라우저는 추측한다")
    void unknownType() {
        AttachmentUpload unknown = new AttachmentUpload("data.jpg", 1024, DetectedFileType.unknown());

        assertThatThrownBy(() -> unknown.validateAgainst(policy()))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("형식을 알 수 없는");
    }

    @ParameterizedTest
    @DisplayName("SVG·HTML 계열은 게시판이 허용해도 받지 않는다")
    @ValueSource(strings = {"logo.svg", "page.html", "page.htm", "doc.xml"})
    void alwaysBlocked(String fileName) {
        BoardAttachmentPolicy permissive =
                BoardAttachmentPolicy.enabled(3, 100, List.of("svg", "html", "htm", "xml"));
        AttachmentUpload upload = new AttachmentUpload(fileName, 512,
                DetectedFileType.of(fileName.substring(fileName.lastIndexOf('.') + 1), "text/xml", false));

        assertThatThrownBy(() -> upload.validateAgainst(permissive))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("스크립트를 담을 수 있는");
    }

    @Test
    @DisplayName("게시판이 허용하지 않는 확장자는 거절한다")
    void notPermitted() {
        BoardAttachmentPolicy imagesOnly = BoardAttachmentPolicy.enabled(3, 100, List.of("jpg", "png"));

        assertThatThrownBy(() -> new AttachmentUpload("doc.pdf", 1024, PDF).validateAgainst(imagesOnly))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("허용하지 않는");
    }

    @Test
    @DisplayName("첨부를 받지 않는 게시판이면 거절한다")
    void attachmentsDisabled() {
        assertThatThrownBy(() ->
                new AttachmentUpload("photo.jpg", 100, JPEG).validateAgainst(BoardAttachmentPolicy.disabled()))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("첨부를 받지 않습니다");
    }

    @Test
    @DisplayName("크기 한도는 KB 단위 정책을 바이트로 환산해 비교한다 — 경계")
    void sizeBoundary() {
        BoardAttachmentPolicy oneKb = BoardAttachmentPolicy.enabled(3, 1, List.of("jpg"));

        assertThatCode(() -> new AttachmentUpload("a.jpg", 1024, JPEG).validateAgainst(oneKb))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AttachmentUpload("a.jpg", 1025, JPEG).validateAgainst(oneKb))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("너무 큽니다");
    }

    @Test
    @DisplayName("빈 파일은 받지 않는다")
    void emptyFile() {
        assertThatThrownBy(() -> new AttachmentUpload("a.jpg", 0, JPEG))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @ParameterizedTest
    @DisplayName("파일명에서 경로를 걷어낸다 — 마지막 세그먼트만 남는다")
    @ValueSource(strings = {"../../etc/photo.jpg", "..\\..\\windows\\photo.jpg", "/tmp/photo.jpg"})
    void stripsPath(String fileName) {
        assertThat(new AttachmentUpload(fileName, 100, JPEG).originalName()).isEqualTo("photo.jpg");
    }

    @Test
    @DisplayName("헤더를 쪼갤 수 있는 문자는 제거한다 — 이 이름은 Content-Disposition 으로 나간다")
    void stripsHeaderInjection() {
        AttachmentUpload upload = new AttachmentUpload("photo\r\nX-Evil: 1\".jpg", 100, JPEG);

        assertThat(upload.originalName()).doesNotContain("\r").doesNotContain("\n").doesNotContain("\"");
    }

    @ParameterizedTest
    @DisplayName("이름이 경로뿐이면 거절한다")
    @ValueSource(strings = {"..", ".", "/", "   "})
    void rejectsPathOnlyName(String fileName) {
        assertThatThrownBy(() -> new AttachmentUpload(fileName, 100, JPEG))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("확장자가 없으면 판정 결과와 다르다고 본다")
    void noExtension() {
        assertThat(new AttachmentUpload("photo", 100, JPEG).declaredExtension()).isEmpty();
        assertThatThrownBy(() -> new AttachmentUpload("photo", 100, JPEG).validateAgainst(policy()))
                .isInstanceOf(BoardInvariantViolationException.class);
    }
}
