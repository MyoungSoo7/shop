package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code gift_cards} 매핑. 도메인({@link GiftCard})과의 변환만 담당한다. */
@Entity
@Table(name = "gift_cards")
public class GiftCardJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "code_last4", nullable = false, length = 4)
    private String codeLast4;

    @Column(name = "face_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal faceAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GiftCardStatus status;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "issued_by", nullable = false, length = 64)
    private String issuedBy;

    @Column(name = "memo", length = 255)
    private String memo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected GiftCardJpaEntity() {
    }

    static GiftCardJpaEntity from(GiftCard card) {
        GiftCardJpaEntity entity = new GiftCardJpaEntity();
        entity.id = card.getId();
        entity.codeHash = card.getCodeHash();
        entity.codeLast4 = card.getCodeLast4();
        entity.faceAmount = card.getFaceAmount();
        entity.issuedAt = card.getIssuedAt();
        entity.expiresAt = card.getExpiresAt();
        entity.issuedBy = card.getIssuedBy();
        entity.memo = card.getMemo();
        entity.createdAt = card.getIssuedAt();
        entity.apply(card);
        return entity;
    }

    void apply(GiftCard card) {
        this.remainingAmount = card.getRemainingAmount();
        this.status = card.getStatus();
        this.ownerUserId = card.getOwnerUserId();
        this.activatedAt = card.getActivatedAt();
        this.registeredAt = card.getRegisteredAt();
        this.updatedAt = OffsetDateTime.now();
    }

    GiftCard toDomain() {
        return GiftCard.rehydrate(id, codeHash, codeLast4, faceAmount, remainingAmount, status,
                ownerUserId, issuedAt, activatedAt, registeredAt, expiresAt, issuedBy, memo, version);
    }

    Long getId() {
        return id;
    }

    long getVersion() {
        return version;
    }
}
