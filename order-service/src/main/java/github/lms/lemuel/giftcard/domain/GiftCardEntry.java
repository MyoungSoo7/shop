package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 기프트카드 원장 엔트리 — append-only, <b>카드 단위</b>.
 *
 * <p>포인트 엔트리는 여러 로트에 걸칠 수 있어 배분 상세 테이블이 필요했다. 기프트카드는 엔트리
 * 자체가 카드 하나에 속하므로 그 테이블이 없다 — 한 결제가 여러 장을 걸치면 카드마다 엔트리가
 * 하나씩 생기고, 같은 {@code referenceId} 로 묶인다.
 *
 * <p>금액은 언제나 양수이고 방향은 {@link GiftCardEntryType} 이 정한다.
 */
public class GiftCardEntry {

    private Long id;
    private final Long giftCardId;
    private final GiftCardEntryType type;
    private final BigDecimal amount;
    private final String referenceType;
    private final String referenceId;
    private final int sequence;
    private final String memo;
    private final String createdBy;
    private final OffsetDateTime createdAt;

    private GiftCardEntry(Long id, Long giftCardId, GiftCardEntryType type, BigDecimal amount,
                          String referenceType, String referenceId, int sequence, String memo,
                          String createdBy, OffsetDateTime createdAt) {
        this.id = id;
        this.giftCardId = giftCardId;
        this.type = type;
        this.amount = amount;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.sequence = sequence;
        this.memo = memo;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** 모든 팩토리가 지나는 유일한 생성 경로 — 금액 규약과 식별 정보 검증을 여기서 한다. */
    private static GiftCardEntry create(GiftCardEntryType type, Long giftCardId, BigDecimal amount,
                                        String referenceType, String referenceId, int sequence,
                                        String createdBy, String memo, OffsetDateTime createdAt) {
        BigDecimal value = GiftCardAmounts.require(amount, type.name().toLowerCase(java.util.Locale.ROOT));
        if (giftCardId == null || referenceType == null || referenceId == null) {
            throw new GiftCardInvariantViolationException(
                    "불변식 위반: 카드·참조 없이 원장 엔트리를 만들 수 없다 (type=" + type + ")");
        }
        return new GiftCardEntry(null, giftCardId, type, value, referenceType, referenceId,
                sequence, memo, createdBy, createdAt);
    }

    /** 등록 — 부채가 생기는 지점. 금액은 권면가다. */
    public static GiftCardEntry register(Long giftCardId, BigDecimal faceAmount, String referenceType,
                                         String referenceId, int sequence, String createdBy, String memo) {
        return create(GiftCardEntryType.REGISTER, giftCardId, faceAmount, referenceType, referenceId,
                sequence, createdBy, memo, OffsetDateTime.now());
    }

    public static GiftCardEntry use(Long giftCardId, BigDecimal amount, String referenceType,
                                    String referenceId, int sequence, String createdBy) {
        return create(GiftCardEntryType.USE, giftCardId, amount, referenceType, referenceId,
                sequence, createdBy, null, OffsetDateTime.now());
    }

    public static GiftCardEntry restore(Long giftCardId, BigDecimal amount, String referenceType,
                                        String referenceId, int sequence, String createdBy) {
        return create(GiftCardEntryType.RESTORE, giftCardId, amount, referenceType, referenceId,
                sequence, createdBy, null, OffsetDateTime.now());
    }

    public static GiftCardEntry expire(Long giftCardId, BigDecimal amount, String referenceType,
                                       String referenceId, int sequence, String createdBy) {
        return create(GiftCardEntryType.EXPIRE, giftCardId, amount, referenceType, referenceId,
                sequence, createdBy, null, OffsetDateTime.now());
    }

    /** 영속 상태로부터 복원 — 저장된 행도 같은 검증을 통과해야 한다. */
    public static GiftCardEntry rehydrate(Long id, Long giftCardId, GiftCardEntryType type,
                                          BigDecimal amount, String referenceType, String referenceId,
                                          int sequence, String memo, String createdBy,
                                          OffsetDateTime createdAt) {
        GiftCardEntry entry = create(type, giftCardId, amount, referenceType, referenceId,
                sequence, createdBy, memo, createdAt);
        entry.id = id;
        return entry;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new GiftCardInvariantViolationException(
                    "append-only 원장 엔트리의 ID 를 다시 할당할 수 없습니다: " + this.id);
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getGiftCardId() { return giftCardId; }
    public GiftCardEntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public int getSequence() { return sequence; }
    public String getMemo() { return memo; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
