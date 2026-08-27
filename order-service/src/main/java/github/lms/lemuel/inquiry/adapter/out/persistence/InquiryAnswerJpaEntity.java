package github.lms.lemuel.inquiry.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 답변 행.
 *
 * <p>질문과 <b>다른 테이블</b>이고, {@code inquiry_id} 외래키로 매달린다. 레거시는 답변을 질문과
 * 같은 테이블의 형제 행으로 넣고 {@code ABS(ID_NUM) = 질문ID AND ID_DEPTH != 0} 이라는 관례로
 * 이었다. 관례는 DB 가 지켜 주지 않으므로 질문 없는 답변이 남을 수 있었다. 여기서는 부모가
 * 사라지면 {@code ON DELETE CASCADE} 로 함께 사라진다.
 */
@Entity
@Table(name = "inquiry_answers",
        indexes = @Index(name = "ix_inquiry_answers_inquiry", columnList = "inquiry_id, answered_at"))
public class InquiryAnswerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inquiry_id", nullable = false)
    private Long inquiryId;

    @Column(name = "answered_by", nullable = false)
    private Long answeredBy;

    @Column(name = "content", nullable = false, length = 4000)
    private String content;

    @Column(name = "answered_at", nullable = false, updatable = false)
    private LocalDateTime answeredAt;

    protected InquiryAnswerJpaEntity() { }

    public InquiryAnswerJpaEntity(Long id, Long inquiryId, Long answeredBy,
                                  String content, LocalDateTime answeredAt) {
        this.id = id;
        this.inquiryId = inquiryId;
        this.answeredBy = answeredBy;
        this.content = content;
        this.answeredAt = answeredAt;
    }

    @PrePersist
    protected void onCreate() {
        if (answeredAt == null) answeredAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getInquiryId() { return inquiryId; }
    public Long getAnsweredBy() { return answeredBy; }
    public String getContent() { return content; }
    public LocalDateTime getAnsweredAt() { return answeredAt; }
}
