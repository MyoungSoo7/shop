package github.lms.lemuel.review.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "reviews",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_review_user_product",
        columnNames = {"user_id", "product_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReviewJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private short rating;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 노출 상태. 블라인드는 삭제가 아니라 노출 차단이라 원문 컬럼은 그대로 둔다.
     *
     * <p>enum 이 아니라 {@code String} 으로 두는 이유는 이 모듈의 관례({@code UserJpaEntity} 의
     * {@code role}·{@code membership_status})와 같다 — 매핑은 어댑터가 하고, 엔티티는 컬럼 모양만
     * 책임진다. {@code VARCHAR(20)} 기대 타입이 스키마와 어긋나면 {@code ddl-auto=validate} 가
     * 기동을 막는다.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "hidden_reason", length = 500)
    private String hiddenReason;

    @Column(name = "hidden_by")
    private Long hiddenBy;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
