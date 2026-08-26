package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentReportTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-27T09:00:00Z");
    private static final OffsetDateTime LATER = NOW.plusHours(3);

    private static final BoardActor REPORTER = BoardActor.of(11L, "USER");
    private static final BoardAuthor REPORTER_NAME = new BoardAuthor(11L, "re***");

    private static BoardComment comment(BoardCommentStatus status) {
        return BoardComment.rehydrate(7L, 1L, 1L, null, new BoardAuthor(10L, "co***"),
                "문제의 댓글", status, NOW, NOW);
    }

    @Test
    @DisplayName("접수는 댓글을 건드리지 않는다 — 신고 한 건이 곧 검열이 되지 않게")
    void receiveDoesNotTouchComment() {
        BoardComment target = comment(BoardCommentStatus.PUBLISHED);

        CommentReport report = CommentReport.receive(target, REPORTER, REPORTER_NAME,
                CommentReportReason.ABUSE, "욕설입니다", NOW);

        assertThat(report.getStatus()).isEqualTo(CommentReportStatus.RECEIVED);
        assertThat(report.getCommentId()).isEqualTo(7L);
        assertThat(report.getHandledBy()).isNull();
        assertThat(report.getHandledAt()).isNull();
        assertThat(target.getStatus()).isEqualTo(BoardCommentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("미인증·타인 사칭은 거부한다 — 신고자는 JWT 에서만 온다")
    void receiveRequiresSelf() {
        BoardComment target = comment(BoardCommentStatus.PUBLISHED);

        assertThatThrownBy(() -> CommentReport.receive(target, BoardActor.anonymous(), REPORTER_NAME,
                CommentReportReason.SPAM, null, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);

        assertThatThrownBy(() -> CommentReport.receive(target, REPORTER, new BoardAuthor(12L, "ot***"),
                CommentReportReason.SPAM, null, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("자기 댓글은 신고할 수 없다 — 지우면 될 일을 큐로 보내는 경로")
    void cannotReportOwnComment() {
        BoardComment mine = BoardComment.rehydrate(7L, 1L, 1L, null, REPORTER_NAME,
                "내 댓글", BoardCommentStatus.PUBLISHED, NOW, NOW);

        assertThatThrownBy(() -> CommentReport.receive(mine, REPORTER, REPORTER_NAME,
                CommentReportReason.SPAM, null, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("삭제된 댓글은 신고할 수 없다")
    void cannotReportDeleted() {
        assertThatThrownBy(() -> CommentReport.receive(comment(BoardCommentStatus.DELETED), REPORTER,
                REPORTER_NAME, CommentReportReason.SPAM, null, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("가려진 댓글은 신고할 수 있다 — 이미 내려간 뒤에도 다른 사유의 접수는 기록돼야 한다")
    void canReportHidden() {
        assertThatCode(() -> CommentReport.receive(comment(BoardCommentStatus.HIDDEN), REPORTER,
                REPORTER_NAME, CommentReportReason.PRIVACY, null, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("'그 밖' 사유는 설명이 있어야 한다 — 없으면 판정할 근거가 없다")
    void etcRequiresDetail() {
        BoardComment target = comment(BoardCommentStatus.PUBLISHED);

        assertThatThrownBy(() -> CommentReport.receive(target, REPORTER, REPORTER_NAME,
                CommentReportReason.ETC, "   ", NOW))
                .isInstanceOf(BoardInvariantViolationException.class);

        assertThat(CommentReport.receive(target, REPORTER, REPORTER_NAME,
                CommentReportReason.ETC, " 도배로 보입니다 ", NOW).getDetail())
                .isEqualTo("도배로 보입니다");
    }

    @Test
    @DisplayName("설명 500자는 허용하고 501자는 거부한다 — 경계")
    void detailBoundary() {
        BoardComment target = comment(BoardCommentStatus.PUBLISHED);

        assertThatCode(() -> CommentReport.receive(target, REPORTER, REPORTER_NAME,
                CommentReportReason.SPAM, "가".repeat(500), NOW)).doesNotThrowAnyException();

        assertThatThrownBy(() -> CommentReport.receive(target, REPORTER, REPORTER_NAME,
                CommentReportReason.SPAM, "가".repeat(501), NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("사유는 필수다")
    void reasonRequired() {
        BoardComment target = comment(BoardCommentStatus.PUBLISHED);

        assertThatThrownBy(() -> CommentReport.receive(target, REPORTER, REPORTER_NAME, null, null, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("판정은 어느 쪽으로 갈렸는지를 남긴다 — 원본은 처리 여부 한 칸뿐이라 이걸 잃었다")
    void resolveRecordsDirection() {
        CommentReport report = CommentReport.receive(comment(BoardCommentStatus.PUBLISHED), REPORTER,
                REPORTER_NAME, CommentReportReason.ABUSE, null, NOW);

        report.resolve(CommentReportStatus.KEPT, " ad*** ", LATER);

        assertThat(report.getStatus()).isEqualTo(CommentReportStatus.KEPT);
        assertThat(report.getHandledBy()).isEqualTo("ad***");
        assertThat(report.getHandledAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("이미 처리한 신고는 다시 판정하지 않는다 — 나중 판정이 앞 판정을 조용히 덮지 않게")
    void resolveOnce() {
        CommentReport report = CommentReport.receive(comment(BoardCommentStatus.PUBLISHED), REPORTER,
                REPORTER_NAME, CommentReportReason.ABUSE, null, NOW);
        report.resolve(CommentReportStatus.HIDDEN, "ad***", LATER);

        assertThatThrownBy(() -> report.resolve(CommentReportStatus.KEPT, "ot***", LATER))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("RECEIVED 는 판정 결과가 아니고, 처리자 없는 판정도 거부한다")
    void resolveRejectsInvalidDecision() {
        CommentReport report = CommentReport.receive(comment(BoardCommentStatus.PUBLISHED), REPORTER,
                REPORTER_NAME, CommentReportReason.ABUSE, null, NOW);

        assertThatThrownBy(() -> report.resolve(CommentReportStatus.RECEIVED, "ad***", LATER))
                .isInstanceOf(BoardInvariantViolationException.class);
        assertThatThrownBy(() -> report.resolve(null, "ad***", LATER))
                .isInstanceOf(BoardInvariantViolationException.class);
        assertThatThrownBy(() -> report.resolve(CommentReportStatus.HIDDEN, "  ", LATER))
                .isInstanceOf(BoardInvariantViolationException.class);
    }
}
