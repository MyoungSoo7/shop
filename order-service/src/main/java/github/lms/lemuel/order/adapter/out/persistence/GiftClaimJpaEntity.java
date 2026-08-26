package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.GiftClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code order_gift_claims} 매핑.
 *
 * <p>enum 은 {@code String} 으로 담는다. 순서 기반 저장은 상수를 하나 끼워 넣는 순간 이미 저장된
 * 모든 행의 뜻이 조용히 바뀐다.
 *
 * <p><b>{@code tokenHash} 는 해시다</b> — 평문 토큰은 이 테이블에 절대 들어오지 않는다. 링크 하나가
 * 로그인 없이 주문 화면을 여는 열쇠이므로, 평문을 담으면 DB 한 벌이 새는 순간 살아 있는 링크
 * 전부가 즉시 쓸 수 있는 상태가 된다.
 */
@Entity
@Table(name = "order_gift_claims")
public class GiftClaimJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;

    @Column(name = "recipient_name", nullable = false, length = 60)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 40)
    private String recipientPhone;

    @Column(length = 200)
    private String message;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "verification_code_hash", length = 128)
    private String verificationCodeHash;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    @Column(name = "verify_attempts", nullable = false)
    private int verifyAttempts;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected GiftClaimJpaEntity() {
    }

    static GiftClaimJpaEntity fromDomain(GiftClaim claim) {
        GiftClaimJpaEntity entity = new GiftClaimJpaEntity();
        entity.id = claim.getId();
        entity.applyFrom(claim);
        return entity;
    }

    /** 이미 영속화된 행에 도메인의 현재 값을 덮어쓴다(식별자는 건드리지 않는다). */
    void applyFrom(GiftClaim claim) {
        this.orderId = claim.getOrderId();
        this.senderUserId = claim.getSenderUserId();
        this.recipientName = claim.getRecipientName();
        this.recipientPhone = claim.getRecipientPhone();
        this.message = claim.getMessage();
        this.tokenHash = claim.getTokenHash();
        this.status = claim.getStatus().name();
        this.verificationCodeHash = claim.getVerificationCodeHash();
        this.codeExpiresAt = claim.getCodeExpiresAt();
        this.verifyAttempts = claim.getVerifyAttempts();
        this.expiresAt = claim.getExpiresAt();
        this.createdAt = claim.getCreatedAt();
        this.verifiedAt = claim.getVerifiedAt();
        this.claimedAt = claim.getClaimedAt();
        this.updatedAt = claim.getUpdatedAt();
    }

    GiftClaim toDomain() {
        return GiftClaim.restore(id, orderId, senderUserId,
                recipientName, recipientPhone, message,
                tokenHash, GiftClaimStatus.fromString(status),
                verificationCodeHash, codeExpiresAt, verifyAttempts,
                expiresAt, createdAt, verifiedAt, claimedAt, updatedAt);
    }

    public Long getId() {
        return id;
    }
}
