package github.lms.lemuel.inquiry.adapter.out.persistence;

import github.lms.lemuel.inquiry.domain.InquiryType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 문의 질문 행.
 *
 * <p>식별자는 {@code IDENTITY} 다. 레거시는 {@code (SELECT NVL(MAX(ID)+1,1) FROM ...)} 로 다음
 * 번호를 읽어서 넣었고, 질문·답변을 잇는 {@code ID_NUM} 이 그 값의 음수였다 — 두 요청이 같은
 * 순간에 읽으면 같은 번호를 쓰고, 그러면 남의 문의에 답변이 붙는다. 여기서는 번호를 DB 가 정하고,
 * 답변은 번호 규약이 아니라 <b>외래키</b>로 매달린다.
 */
@Entity
@Table(name = "inquiries",
        indexes = {
                @Index(name = "ix_inquiries_user", columnList = "user_id, asked_at"),
                @Index(name = "ix_inquiries_product", columnList = "product_id, asked_at")
        })
public class InquiryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private InquiryType type;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "content", nullable = false, length = 4000)
    private String content;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @Column(name = "asked_at", nullable = false, updatable = false)
    private LocalDateTime askedAt;

    protected InquiryJpaEntity() { }

    public InquiryJpaEntity(Long id, Long userId, InquiryType type, Long productId, Long orderId,
                            String subject, String content, boolean secret, LocalDateTime askedAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.productId = productId;
        this.orderId = orderId;
        this.subject = subject;
        this.content = content;
        this.secret = secret;
        this.askedAt = askedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (askedAt == null) askedAt = LocalDateTime.now();
    }

    /** 제목·본문·공개 여부만 바뀐다. 종류·대상·작성자는 한 번 정해지면 그대로다. */
    public void edit(String newSubject, String newContent, boolean newSecret) {
        this.subject = newSubject;
        this.content = newContent;
        this.secret = newSecret;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public InquiryType getType() { return type; }
    public Long getProductId() { return productId; }
    public Long getOrderId() { return orderId; }
    public String getSubject() { return subject; }
    public String getContent() { return content; }
    public boolean isSecret() { return secret; }
    public LocalDateTime getAskedAt() { return askedAt; }
}
