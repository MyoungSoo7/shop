package github.lms.lemuel.user.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 회원 승인 처리 이력 JPA Entity.
 * DB 스키마: opslab.membership_approvals (V20260610090000)
 */
@Entity
@Table(name = "membership_approvals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MembershipApprovalJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 500)
    private String reason;

    @Column(name = "processed_by", nullable = false)
    private Long processedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
