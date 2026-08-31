package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import github.lms.lemuel.seller.domain.SubmissionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code product_submissions} 매핑 — 이 모듈에서 <b>유일하게 진짜로 쓰는</b> 엔티티다.
 *
 * <p>다른 엔티티들은 {@code ddl-auto: validate} 를 위한 껍데기이고 적재는 전부 네이티브
 * upsert 다. 신청서만 다른 이유는 이것이 사본이 아니라 <b>우리 원장</b>이기 때문이다. 상태
 * 전이가 있고, 낙관적 충돌이 실제로 의미를 갖고, 채번을 DB 에 맡겨야 한다.
 *
 * <p>도메인({@link ProductSubmission})은 불변 record 이고 이 엔티티는 가변이다. 그 경계를
 * 이 클래스 안에서만 넘는다 — {@link #from}/{@link #apply} 로 들어오고 {@link #toDomain} 으로
 * 나간다. 게터를 두지 않은 것은 밖에서 필드를 하나씩 읽어 다시 조립하는 코드가 생기는 것을
 * 막기 위해서다. 그런 코드는 필드가 하나 늘 때마다 조용히 뒤처진다.
 */
@Entity
@Table(name = "product_submissions")
class ProductSubmissionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "submission_type", nullable = false, length = 20)
    private String submissionType;

    @Column(name = "base_product_id")
    private Long baseProductId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Column(length = 100)
    private String category;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "display_visible", nullable = false)
    private boolean displayVisible;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProductSubmissionJpaEntity() {
    }

    /**
     * 감사 컬럼은 DB 기본값({@code DEFAULT NOW()})에 기대지 않는다. JPA 는 null 필드도 INSERT
     * 문에 <b>명시적으로</b> 실어 보내므로 기본값이 발동하지 않고 NOT NULL 위반으로 터진다.
     */
    @PrePersist
    void onInsert() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    static ProductSubmissionJpaEntity from(ProductSubmission submission) {
        ProductSubmissionJpaEntity entity = new ProductSubmissionJpaEntity();
        entity.apply(submission);
        return entity;
    }

    /** 도메인 상태를 그대로 덮어쓴다. 부분 갱신이 없는 이유는 전이가 언제나 전체 값이어서다. */
    void apply(ProductSubmission submission) {
        ProductContent content = submission.content();
        submissionId = submission.submissionId();
        sellerId = submission.sellerId();
        organizationId = submission.organizationId();
        createdByUserId = submission.createdByUserId();
        submissionType = submission.type().name();
        baseProductId = submission.baseProductId();
        name = content.name();
        description = content.description();
        price = content.price();
        stock = content.stock();
        category = content.category();
        imageUrl = content.imageUrl();
        displayVisible = content.displayVisible();
        status = submission.status().name();
        rejectReason = submission.rejectReason();
        productId = submission.productId();
        submittedAt = submission.submittedAt();
        decidedAt = submission.decidedAt();
        decidedByUserId = submission.decidedByUserId();
    }

    ProductSubmission toDomain() {
        return new ProductSubmission(
                submissionId,
                sellerId,
                organizationId,
                createdByUserId,
                SubmissionType.valueOf(submissionType),
                baseProductId,
                new ProductContent(name, description, price, stock, category, imageUrl, displayVisible),
                SubmissionStatus.valueOf(status),
                rejectReason,
                productId,
                submittedAt,
                decidedAt,
                decidedByUserId);
    }
}
