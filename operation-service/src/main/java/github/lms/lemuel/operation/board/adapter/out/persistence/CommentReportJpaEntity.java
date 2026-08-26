package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "board_comment_reports", schema = "board")
public class CommentReportJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reporter_name", nullable = false, length = 40)
    private String reporterName;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private CommentReportReason reason;

    @Column(name = "detail", length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CommentReportStatus status;

    @Column(name = "handled_by", length = 64)
    private String handledBy;

    @Column(name = "handled_at")
    private OffsetDateTime handledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected CommentReportJpaEntity() {
    }

    public static CommentReportJpaEntity from(CommentReport report) {
        CommentReportJpaEntity entity = new CommentReportJpaEntity();
        entity.id = report.getId();
        entity.apply(report);
        return entity;
    }

    public void apply(CommentReport report) {
        this.commentId = report.getCommentId();
        this.reporterId = report.getReporter().userId();
        this.reporterName = report.getReporter().displayName();
        this.reason = report.getReason();
        this.detail = report.getDetail();
        this.status = report.getStatus();
        this.handledBy = report.getHandledBy();
        this.handledAt = report.getHandledAt();
        this.createdAt = report.getCreatedAt();
    }

    public CommentReport toDomain() {
        return CommentReport.rehydrate(id, commentId, new BoardAuthor(reporterId, reporterName),
                reason, detail, status, handledBy, handledAt, createdAt);
    }

    public Long getId() {
        return id;
    }
}
