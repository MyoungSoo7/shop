package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.out.SanitizeHtmlPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardContentSanitizerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-15T10:00:00Z");

    @Mock
    private SanitizeHtmlPort sanitizeHtmlPort;

    private static BoardDefinition board(BoardContentFormat format) {
        return BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.of(format, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.of(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                NOW);
    }

    @Test
    @DisplayName("HTML 게시판은 정화 포트를 거친다")
    void sanitizesHtmlBoard() {
        when(sanitizeHtmlPort.sanitize("<script>x</script>본문")).thenReturn("본문");
        BoardContentSanitizer sanitizer = new BoardContentSanitizer(sanitizeHtmlPort);

        assertThat(sanitizer.sanitize(board(BoardContentFormat.HTML), "<script>x</script>본문"))
                .isEqualTo("본문");
        verify(sanitizeHtmlPort).sanitize(anyString());
    }

    @Test
    @DisplayName("TEXT·MARKDOWN 은 정화하지 않는다 — 마크다운 코드블록의 예시 태그까지 지워진다")
    void skipsNonHtmlBoards() {
        BoardContentSanitizer sanitizer = new BoardContentSanitizer(sanitizeHtmlPort);
        String raw = "```html\n<script>예시</script>\n```";

        assertThat(sanitizer.sanitize(board(BoardContentFormat.MARKDOWN), raw)).isEqualTo(raw);
        assertThat(sanitizer.sanitize(board(BoardContentFormat.TEXT), raw)).isEqualTo(raw);
        verifyNoInteractions(sanitizeHtmlPort);
    }

    @Test
    @DisplayName("null 본문은 포트를 부르지 않고 그대로 돌려준다")
    void nullContent() {
        BoardContentSanitizer sanitizer = new BoardContentSanitizer(sanitizeHtmlPort);

        assertThat(sanitizer.sanitize(board(BoardContentFormat.HTML), null)).isNull();
        verify(sanitizeHtmlPort, never()).sanitize(anyString());
    }
}
