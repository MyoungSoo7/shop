package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code gift_card_entries} 매핑 (append-only, 카드 단위). */
@Entity
@Table(name = "gift_card_entries")
public class GiftCardEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gift_card_id", nullable = false)
    private Long giftCardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private GiftCardEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "memo", length = 255)
    private String memo;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected GiftCardEntryJpaEntity() {
    }

    static GiftCardEntryJpaEntity from(GiftCardEntry entry) {
        GiftCardEntryJpaEntity jpa = new GiftCardEntryJpaEntity();
        jpa.giftCardId = entry.getGiftCardId();
        jpa.entryType = entry.getType();
        jpa.amount = entry.getAmount();
        jpa.referenceType = entry.getReferenceType();
        jpa.referenceId = entry.getReferenceId();
        jpa.sequence = entry.getSequence();
        jpa.memo = entry.getMemo();
        jpa.createdBy = entry.getCreatedBy();
        jpa.createdAt = entry.getCreatedAt();
        return jpa;
    }

    GiftCardEntry toDomain() {
        return GiftCardEntry.rehydrate(id, giftCardId, entryType, amount, referenceType,
                referenceId, sequence, memo, createdBy, createdAt);
    }

    Long getId() {
        return id;
    }
}
