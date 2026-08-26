package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 댓글 통합 조회·신고 매핑.
 *
 * <p>여기서 보는 것은 도메인 규칙이 아니라 <b>질의가 실제로 걸리는가</b>다. 특히 원본(dentis)이
 * 놓쳤던 두 가지 — ① 검색어 없이 상태만으로 거르기, ② 신고 붙은 댓글만 보기 — 는 화면상으로는
 * 필터가 걸린 것처럼 보이면서 결과만 전체였던 종류의 결함이라, 단위 테스트로는 안 잡힌다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:commentmoderation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "INIT=CREATE SCHEMA IF NOT EXISTS board",
                "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@Transactional
class CommentModerationPersistenceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-27T09:00:00Z");

    @Autowired private BoardCommentPersistenceAdapter comments;
    @Autowired private CommentReportPersistenceAdapter reports;
    @Autowired private BoardPostPersistenceAdapter posts;

    private BoardComment comment(Long boardId, Long postId, Long authorId, String content,
                                 BoardCommentStatus status, int minuteOffset) {
        return comments.save(BoardComment.rehydrate(null, postId, boardId, null,
                new BoardAuthor(authorId, "us***"), content, status,
                NOW.plusMinutes(minuteOffset), NOW.plusMinutes(minuteOffset)));
    }

    private CommentReport report(Long commentId, Long reporterId, CommentReportStatus status) {
        return reports.save(CommentReport.rehydrate(null, commentId, new BoardAuthor(reporterId, "re***"),
                CommentReportReason.ABUSE, null, status,
                status == CommentReportStatus.RECEIVED ? null : "ad***",
                status == CommentReportStatus.RECEIVED ? null : NOW, NOW));
    }

    @Test
    @DisplayName("신고는 왕복해도 그대로다")
    void reportRoundTrip() {
        BoardComment target = comment(1L, 5L, 10L, "문제의 댓글", BoardCommentStatus.PUBLISHED, 0);
        CommentReport saved = reports.save(CommentReport.rehydrate(null, target.getId(),
                new BoardAuthor(11L, "re***"), CommentReportReason.PRIVACY, "개인정보가 있습니다",
                CommentReportStatus.RECEIVED, null, null, NOW));

        CommentReport found = reports.findById(saved.getId()).orElseThrow();

        assertThat(found.getCommentId()).isEqualTo(target.getId());
        assertThat(found.getReporter().userId()).isEqualTo(11L);
        assertThat(found.getReason()).isEqualTo(CommentReportReason.PRIVACY);
        assertThat(found.getDetail()).isEqualTo("개인정보가 있습니다");
        assertThat(found.getStatus()).isEqualTo(CommentReportStatus.RECEIVED);
        assertThat(found.getHandledBy()).isNull();
    }

    @Test
    @DisplayName("검색어 없이 상태만으로도 걸린다 — 원본은 이 조건이 검색어 안에 중첩돼 조용히 무시됐다")
    void statusFilterWorksWithoutKeyword() {
        comment(1L, 5L, 10L, "살아 있는 댓글", BoardCommentStatus.PUBLISHED, 0);
        comment(1L, 5L, 10L, "가려진 댓글", BoardCommentStatus.HIDDEN, 1);

        var hidden = comments.search(
                new CommentSearchCriteria(null, BoardCommentStatus.HIDDEN, null, null, false), 0, 20);

        assertThat(hidden.totalElements()).isEqualTo(1);
        assertThat(hidden.content().get(0).getContent()).isEqualTo("가려진 댓글");
    }

    @Test
    @DisplayName("게시판·작성자·검색어 조건은 서로 독립이다")
    void filtersAreIndependent() {
        comment(1L, 5L, 10L, "공지 게시판 댓글", BoardCommentStatus.PUBLISHED, 0);
        comment(2L, 6L, 10L, "다른 게시판 댓글", BoardCommentStatus.PUBLISHED, 1);
        comment(1L, 5L, 20L, "남의 댓글", BoardCommentStatus.PUBLISHED, 2);

        assertThat(comments.search(new CommentSearchCriteria(1L, null, null, null, false), 0, 20)
                .totalElements()).isEqualTo(2);
        assertThat(comments.search(new CommentSearchCriteria(null, null, null, 20L, false), 0, 20)
                .totalElements()).isEqualTo(1);
        assertThat(comments.search(new CommentSearchCriteria(null, null, "다른", null, false), 0, 20)
                .totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("신고 붙은 댓글만 보기 — 신고가 여러 건이어도 줄은 하나다")
    void reportedOnlyDoesNotDuplicateRows() {
        BoardComment reported = comment(1L, 5L, 10L, "신고된 댓글", BoardCommentStatus.PUBLISHED, 0);
        comment(1L, 5L, 10L, "멀쩡한 댓글", BoardCommentStatus.PUBLISHED, 1);
        report(reported.getId(), 11L, CommentReportStatus.RECEIVED);
        report(reported.getId(), 12L, CommentReportStatus.RECEIVED);
        report(reported.getId(), 13L, CommentReportStatus.KEPT);

        var page = comments.search(new CommentSearchCriteria(null, null, null, null, true), 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).getContent()).isEqualTo("신고된 댓글");
    }

    @Test
    @DisplayName("댓글별 신고 건수는 처리 여부와 무관하다 — '몇 명이 문제 삼았는가'가 판정 근거다")
    void countIgnoresHandledState() {
        BoardComment first = comment(1L, 5L, 10L, "댓글 하나", BoardCommentStatus.PUBLISHED, 0);
        BoardComment second = comment(1L, 5L, 10L, "댓글 둘", BoardCommentStatus.PUBLISHED, 1);
        report(first.getId(), 11L, CommentReportStatus.RECEIVED);
        report(first.getId(), 12L, CommentReportStatus.HIDDEN);
        report(second.getId(), 11L, CommentReportStatus.RECEIVED);

        assertThat(reports.countByCommentIds(List.of(first.getId(), second.getId())))
                .containsEntry(first.getId(), 2)
                .containsEntry(second.getId(), 1);
    }

    @Test
    @DisplayName("큐는 오래된 순 — 최신순이면 밀린 건이 영영 뒤로 밀린다")
    void queueIsOldestFirst() {
        BoardComment target = comment(1L, 5L, 10L, "댓글", BoardCommentStatus.PUBLISHED, 0);
        CommentReport older = reports.save(CommentReport.rehydrate(null, target.getId(),
                new BoardAuthor(11L, "re***"), CommentReportReason.SPAM, null,
                CommentReportStatus.RECEIVED, null, null, NOW));
        reports.save(CommentReport.rehydrate(null, target.getId(),
                new BoardAuthor(12L, "re***"), CommentReportReason.SPAM, null,
                CommentReportStatus.RECEIVED, null, null, NOW.plusHours(1)));

        var queue = reports.search(CommentReportStatus.RECEIVED, 0, 20);

        assertThat(queue.totalElements()).isEqualTo(2);
        assertThat(queue.content().get(0).getId()).isEqualTo(older.getId());
    }

    @Test
    @DisplayName("같은 사람의 같은 댓글 신고 여부를 판정한다 — 중복 접수 차단의 근거")
    void existsByCommentAndReporter() {
        BoardComment target = comment(1L, 5L, 10L, "댓글", BoardCommentStatus.PUBLISHED, 0);
        report(target.getId(), 11L, CommentReportStatus.RECEIVED);

        assertThat(reports.existsByCommentIdAndReporterId(target.getId(), 11L)).isTrue();
        assertThat(reports.existsByCommentIdAndReporterId(target.getId(), 12L)).isFalse();
    }

    @Test
    @DisplayName("글 제목 묶음 조회 — 없는 글은 키가 빠진다(빈 문자열로 채우지 않는다)")
    void postTitlesAreProjected() {
        BoardPost saved = posts.save(BoardPost.rehydrate(null, 1L, null, "문제의 글", "본문",
                BoardContentFormat.TEXT, new BoardAuthor(10L, "us***"), false, false,
                BoardPostStatus.PUBLISHED, 0L, NOW, NOW));

        assertThat(posts.findTitlesByIds(List.of(saved.getId(), 99999L)))
                .containsExactly(entry(saved.getId(), "문제의 글"));
    }
}
