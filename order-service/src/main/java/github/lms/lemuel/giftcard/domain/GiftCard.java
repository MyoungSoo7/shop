package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 기프트카드 증서 — <b>잔액이 붙는 유일한 곳</b>.
 *
 * <p>포인트는 계정(요약)과 로트(상세)를 함께 두어 "요약과 상세가 어긋날 수 있다"는 불변식이
 * 따로 필요했다. 기프트카드는 요약을 저장하지 않는다 — 사용자 잔액은 등록된 카드들의 잔액 합으로
 * 계산한다. 그래서 불변식이 이 클래스 하나로 닫힌다: {@code 0 ≤ remaining ≤ face}.
 *
 * <p>부채가 생기는 지점은 <b>등록</b>이다. 발행만 된 코드는 아직 아무에게도 가지 않았으므로
 * 회사의 빚이 아니다({@code docs/plan/gift-card-ledger.md} §6).
 */
public class GiftCard {

    private Long id;
    private final String codeHash;
    private final String codeLast4;
    private final BigDecimal faceAmount;
    private BigDecimal remainingAmount;
    private GiftCardStatus status;
    private Long ownerUserId;
    private final OffsetDateTime issuedAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime registeredAt;
    private final OffsetDateTime expiresAt;
    private final String issuedBy;
    private final String memo;
    private long version;

    private GiftCard(Long id, String codeHash, String codeLast4, BigDecimal faceAmount,
                     BigDecimal remainingAmount, GiftCardStatus status, Long ownerUserId,
                     OffsetDateTime issuedAt, OffsetDateTime activatedAt, OffsetDateTime registeredAt,
                     OffsetDateTime expiresAt, String issuedBy, String memo, long version) {
        this.id = id;
        this.codeHash = codeHash;
        this.codeLast4 = codeLast4;
        this.faceAmount = faceAmount;
        this.remainingAmount = remainingAmount;
        this.status = status;
        this.ownerUserId = ownerUserId;
        this.issuedAt = issuedAt;
        this.activatedAt = activatedAt;
        this.registeredAt = registeredAt;
        this.expiresAt = expiresAt;
        this.issuedBy = issuedBy;
        this.memo = memo;
        this.version = version;
    }

    /**
     * 발행 — 코드는 만들어졌지만 아직 배포 전이다. 잔액은 권면가로 시작하되 주인은 없다.
     *
     * <p>{@code codeHash} 만 받는다. 평문 코드는 도메인에 들어오지 않는다 — 들어오는 순간
     * 로그·예외 메시지·직렬화 어디로든 샐 수 있다.
     */
    public static GiftCard issue(String codeHash, String codeLast4, BigDecimal faceAmount,
                                 OffsetDateTime issuedAt, OffsetDateTime expiresAt,
                                 String issuedBy, String memo) {
        BigDecimal face = GiftCardAmounts.require(faceAmount, "issue");
        if (codeHash == null || codeHash.isBlank() || codeLast4 == null || codeLast4.isBlank()) {
            throw new InvalidGiftCardStateException("코드 해시 없이 발행할 수 없습니다", "NONE", "issue");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new InvalidGiftCardStateException(
                    "유효기간(" + expiresAt + ")은 발행일(" + issuedAt + ")보다 뒤여야 합니다", "NONE", "issue");
        }
        return new GiftCard(null, codeHash, codeLast4, face, face, GiftCardStatus.ISSUED,
                null, issuedAt, null, null, expiresAt, issuedBy, memo, 0L);
    }

    /**
     * 영속 상태로부터 복원. 복원 시점에도 불변식을 검증한다 — 저장소가 깨진 행을 돌려주면
     * 그 사실을 조용히 흡수하지 않고 즉시 드러내야 한다.
     */
    public static GiftCard rehydrate(Long id, String codeHash, String codeLast4, BigDecimal faceAmount,
                                     BigDecimal remainingAmount, GiftCardStatus status, Long ownerUserId,
                                     OffsetDateTime issuedAt, OffsetDateTime activatedAt,
                                     OffsetDateTime registeredAt, OffsetDateTime expiresAt,
                                     String issuedBy, String memo, long version) {
        GiftCard card = new GiftCard(id, codeHash, codeLast4,
                GiftCardAmounts.normalize(faceAmount, "rehydrate"),
                GiftCardAmounts.normalize(remainingAmount, "rehydrate"),
                status, ownerUserId, issuedAt, activatedAt, registeredAt, expiresAt,
                issuedBy, memo, version);
        card.enforceInvariant();
        return card;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 생명주기
    // ──────────────────────────────────────────────────────────────────────────

    /** 배포 확정. 등록은 활성화된 카드만 받는다 — 발행 즉시 등록 가능하면 유출된 코드가 곧 잔액이 된다. */
    public void activate() {
        if (status == GiftCardStatus.ACTIVE) {
            return;
        }
        if (status != GiftCardStatus.ISSUED) {
            throw new InvalidGiftCardStateException(
                    "발행 상태에서만 활성화할 수 있습니다: " + status, status.name(), "activate");
        }
        this.status = GiftCardStatus.ACTIVE;
        this.activatedAt = OffsetDateTime.now();
    }

    /**
     * 사용자에게 귀속. <b>한 번뿐이다</b> — 이미 등록된 카드를 다시 등록할 수 있으면
     * 코드를 아는 사람이 남의 잔액을 가져갈 수 있다.
     */
    public void registerTo(Long userId, OffsetDateTime at) {
        if (userId == null) {
            throw new InvalidGiftCardStateException("등록 대상 사용자가 없습니다", status.name(), "register");
        }
        if (!status.isRegisterable()) {
            throw new InvalidGiftCardStateException(
                    "등록할 수 없는 상태입니다: " + status, status.name(), "register");
        }
        this.ownerUserId = userId;
        this.registeredAt = at;
        this.status = GiftCardStatus.REGISTERED;
        enforceInvariant();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 잔액 변경
    // ──────────────────────────────────────────────────────────────────────────

    /** 결제 사용. 부분 사용이 허용되며 잔액이 이월된다(권면가 단위를 강제하지 않는다). */
    public void use(BigDecimal amount) {
        BigDecimal value = GiftCardAmounts.require(amount, "use");
        if (!status.isSpendable()) {
            throw new InvalidGiftCardStateException(
                    "사용할 수 없는 상태입니다: " + status, status.name(), "use");
        }
        if (remainingAmount.compareTo(value) < 0) {
            throw new InsufficientGiftCardBalanceException(
                    "기프트카드 잔액 부족: 요청 " + value + ", 잔액 " + remainingAmount, value, remainingAmount);
        }
        this.remainingAmount = this.remainingAmount.subtract(value);
        if (remainingAmount.signum() == 0) {
            this.status = GiftCardStatus.USED_UP;
        }
        enforceInvariant();
    }

    /**
     * 환불 복원. 소진된 카드도 되살린다 — 고객이 쓰지 않았더라면 그 카드는 여전히 원래 유효기간을
     * 갖고 있었을 것이므로, 원 카드로 되돌리는 편이 정확하다(포인트 로트와 같은 판단).
     * 이미 <b>소멸·정지</b>된 카드는 되살리지 않는다.
     */
    public void restore(BigDecimal amount) {
        BigDecimal value = GiftCardAmounts.require(amount, "restore");
        if (status != GiftCardStatus.REGISTERED && status != GiftCardStatus.USED_UP) {
            throw new InvalidGiftCardStateException(
                    "복원할 수 없는 상태입니다: " + status, status.name(), "restore");
        }
        BigDecimal restored = remainingAmount.add(value);
        if (restored.compareTo(faceAmount) > 0) {
            throw new GiftCardInvariantViolationException(
                    "불변식 위반: 복원 후 잔액(" + restored + ")이 권면가(" + faceAmount + ")를 초과");
        }
        this.remainingAmount = restored;
        this.status = GiftCardStatus.REGISTERED;
        enforceInvariant();
    }

    /**
     * 유효기간 소멸. 남은 잔액을 반환하며, 만료 시각 전에는 소멸시킬 수 없다 —
     * 배치 버그를 도메인이 막는다.
     */
    public BigDecimal expire(OffsetDateTime at) {
        if (status != GiftCardStatus.ACTIVE && status != GiftCardStatus.REGISTERED) {
            throw new InvalidGiftCardStateException(
                    "소멸시킬 수 없는 상태입니다: " + status, status.name(), "expire");
        }
        if (!isExpiredAt(at)) {
            throw new InvalidGiftCardStateException(
                    "아직 만료되지 않았습니다: expiresAt=" + expiresAt + ", now=" + at,
                    status.name(), "expire");
        }
        BigDecimal forfeited = remainingAmount;
        this.remainingAmount = GiftCardAmounts.zero();
        this.status = GiftCardStatus.EXPIRED;
        return forfeited;
    }

    /** 분실·부정 신고로 정지. 잔액은 남기되 사용을 막는다(조사 후 되살릴 수 있어야 하므로). */
    public void suspend() {
        if (status == GiftCardStatus.SUSPENDED) {
            return;
        }
        if (status.isTerminal()) {
            throw new InvalidGiftCardStateException(
                    "이미 닫힌 카드는 정지할 수 없습니다: " + status, status.name(), "suspend");
        }
        this.status = GiftCardStatus.SUSPENDED;
    }

    /** 만료 판정 — 만료 시각 <b>정각은 아직 유효</b>하다(반열림 경계). */
    public boolean isExpiredAt(OffsetDateTime at) {
        return at.isAfter(expiresAt);
    }

    /** 이 사용자가 결제에 쓸 수 있는 카드인가 — 소유권 대조를 도메인이 갖는다(IDOR 방어). */
    public boolean isSpendableBy(Long userId) {
        return status.isSpendable()
                && ownerUserId != null
                && ownerUserId.equals(userId)
                && remainingAmount.signum() > 0;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new InvalidGiftCardStateException("이미 ID 가 할당된 카드입니다: " + this.id,
                    status.name(), "assignId");
        }
        this.id = id;
    }

    public void syncVersion(long version) {
        this.version = version;
    }

    /**
     * 불변식 — 잔액 범위와 <b>귀속 정합</b>. 등록 이후 상태에는 소유자가 있어야 하고,
     * 등록 전에는 없어야 한다. 이게 깨지면 "주인 없는 잔액"이 생긴다.
     */
    private void enforceInvariant() {
        if (remainingAmount.signum() < 0) {
            throw new GiftCardInvariantViolationException("불변식 위반: 잔액 < 0 (" + remainingAmount + ")");
        }
        if (remainingAmount.compareTo(faceAmount) > 0) {
            throw new GiftCardInvariantViolationException(
                    "불변식 위반: 잔액(" + remainingAmount + ")이 권면가(" + faceAmount + ")를 초과");
        }
        boolean ownedStates = status == GiftCardStatus.REGISTERED || status == GiftCardStatus.USED_UP;
        if (ownedStates && ownerUserId == null) {
            throw new GiftCardInvariantViolationException(
                    "불변식 위반: " + status + " 상태인데 소유자가 없다 — 주인 없는 잔액");
        }
        if ((status == GiftCardStatus.ISSUED || status == GiftCardStatus.ACTIVE) && ownerUserId != null) {
            throw new GiftCardInvariantViolationException(
                    "불변식 위반: " + status + " 상태인데 소유자가 있다 — 등록되지 않은 카드의 귀속");
        }
    }

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public String getCodeLast4() { return codeLast4; }
    public BigDecimal getFaceAmount() { return faceAmount; }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public GiftCardStatus getStatus() { return status; }
    public Long getOwnerUserId() { return ownerUserId; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public String getIssuedBy() { return issuedBy; }
    public String getMemo() { return memo; }
    public long getVersion() { return version; }
}
