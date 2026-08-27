package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 럭키박스 캠페인 애그리거트 루트 (레거시 {@code TBL_LUCKYBOX}).
 *
 * <p>참여 가능 여부와 보상 지급 시점을 결정한다. 경품 목록과 추첨은 각각
 * {@link LuckyboxPrize} 와 {@link PrizeDraw} 가 맡는다 — 캠페인이 경품 리스트를 품으면
 * 참여 한 번에 경품 전체를 적재하게 되고, 그 리스트는 동시 참여자마다 다르게 소진된다.
 */
public final class LuckyboxCampaign {

    private final UUID id;
    private final String tenantRef;
    private String name;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private CampaignStatus status;
    private final BenefitType benefitType;
    private LocalDate benefitOn;
    private final EntryCondition entryCondition;
    private LocalDate memberJoinedFrom;
    private LocalDate rewardExpiresOn;
    private AmountBasis amountBasis;
    private BigDecimal minOrderAmount;
    private ShippingStatusRequirement shippingStatusRequired;
    private String note;
    private CampaignBanner banner;
    private final String createdBy;
    private String updatedBy;
    private final long version;

    private LuckyboxCampaign(UUID id, String tenantRef, String name, LocalDate startsOn, LocalDate endsOn,
                             CampaignStatus status, BenefitType benefitType, LocalDate benefitOn,
                             EntryCondition entryCondition, LocalDate memberJoinedFrom, LocalDate rewardExpiresOn,
                             AmountBasis amountBasis, BigDecimal minOrderAmount,
                             ShippingStatusRequirement shippingStatusRequired, String note, CampaignBanner banner,
                             String createdBy, String updatedBy, long version) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (startsOn == null || endsOn == null) throw new IllegalArgumentException("기간은 필수다");
        if (endsOn.isBefore(startsOn)) throw new IllegalArgumentException("종료일이 시작일보다 빠르다");
        if (benefitType == null || entryCondition == null) {
            throw new IllegalArgumentException("benefitType/entryCondition is required");
        }
        // 일괄 지급인데 지급일이 없으면 당첨자는 화면에서 "당첨" 을 보고 포인트는 영원히 안 받는다.
        if (benefitType == BenefitType.BATCH && benefitOn == null) {
            throw new IllegalArgumentException("일괄 지급 캠페인은 지급일이 필요하다");
        }
        this.id = id;
        this.tenantRef = tenantRef;
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.status = status;
        this.benefitType = benefitType;
        this.benefitOn = benefitOn;
        this.entryCondition = entryCondition;
        this.memberJoinedFrom = memberJoinedFrom;
        this.rewardExpiresOn = rewardExpiresOn;
        this.amountBasis = amountBasis;
        this.minOrderAmount = minOrderAmount;
        this.shippingStatusRequired = shippingStatusRequired;
        this.note = note;
        this.banner = banner == null ? CampaignBanner.empty() : banner;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static LuckyboxCampaign draft(UUID id, String tenantRef, String name, LocalDate startsOn, LocalDate endsOn,
                                         BenefitType benefitType, LocalDate benefitOn, EntryCondition entryCondition,
                                         LocalDate memberJoinedFrom, LocalDate rewardExpiresOn,
                                         AmountBasis amountBasis, BigDecimal minOrderAmount,
                                         ShippingStatusRequirement shippingStatusRequired, String note,
                                         CampaignBanner banner, String actor) {
        return new LuckyboxCampaign(id, tenantRef, name, startsOn, endsOn, CampaignStatus.DRAFT, benefitType,
                benefitOn, entryCondition, memberJoinedFrom, rewardExpiresOn, amountBasis, minOrderAmount,
                shippingStatusRequired, note, banner, actor, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static LuckyboxCampaign rehydrate(UUID id, String tenantRef, String name, LocalDate startsOn,
                                             LocalDate endsOn, CampaignStatus status, BenefitType benefitType,
                                             LocalDate benefitOn, EntryCondition entryCondition,
                                             LocalDate memberJoinedFrom, LocalDate rewardExpiresOn,
                                             AmountBasis amountBasis, BigDecimal minOrderAmount,
                                             ShippingStatusRequirement shippingStatusRequired, String note,
                                             CampaignBanner banner, String createdBy, String updatedBy, long version) {
        return new LuckyboxCampaign(id, tenantRef, name, startsOn, endsOn, status, benefitType, benefitOn,
                entryCondition, memberJoinedFrom, rewardExpiresOn, amountBasis, minOrderAmount,
                shippingStatusRequired, note, banner, createdBy, updatedBy, version);
    }

    /**
     * 오늘 이 캠페인에 참여할 수 있는지 확인한다. 못 하면 던진다.
     *
     * <p>{@code memberJoinedFrom}(가입일 조건)과 금액 조건은 여기서 보지 않는다 — 둘 다 회원·주문
     * 정보가 필요하고, 그건 이 서비스가 소유하지 않는다. 자세한 건 {@link AmountBasis} 주석에 있다.
     */
    public void assertDrawAllowed(LocalDate today) {
        if (status != CampaignStatus.RUNNING) {
            throw new CampaignNotOpenException("진행 중인 이벤트가 아닙니다: " + name);
        }
        if (today.isBefore(startsOn) || today.isAfter(endsOn)) {
            throw new CampaignNotOpenException("이벤트 기간이 아닙니다: " + name);
        }
    }

    /** 오늘 참여분의 슬롯 키 — 참여 제한 유니크 인덱스의 세 번째 값. */
    public String entrySlot(LocalDate on) {
        return entryCondition.slotKey(on);
    }

    /** 보상 지급 예정일. 즉시 지급이면 {@code null}(= 대기 없이 바로 요청). */
    public LocalDate scheduledRewardDate() {
        return benefitType.isImmediate() ? null : benefitOn;
    }

    public void open(String actor) {
        if (status == CampaignStatus.CLOSED) {
            throw new CampaignNotOpenException("종료된 이벤트는 다시 열 수 없습니다: " + name);
        }
        this.status = CampaignStatus.RUNNING;
        this.updatedBy = actor;
    }

    public void close(String actor) {
        this.status = CampaignStatus.CLOSED;
        this.updatedBy = actor;
    }

    public void update(String name, LocalDate startsOn, LocalDate endsOn, LocalDate benefitOn,
                       LocalDate rewardExpiresOn, String note, CampaignBanner banner, String actor) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("기간이 올바르지 않다");
        }
        if (benefitType == BenefitType.BATCH && benefitOn == null) {
            throw new IllegalArgumentException("일괄 지급 캠페인은 지급일이 필요하다");
        }
        // entryCondition/benefitType 은 못 바꾼다 — 이미 쌓인 참여 기록의 슬롯 키 의미가
        // 소급해 달라져서, 하루 1회로 바꾸는 순간 기간 1회로 참여한 사람이 다시 참여할 수 있게 된다.
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.benefitOn = benefitOn;
        this.rewardExpiresOn = rewardExpiresOn;
        this.note = note;
        this.banner = banner == null ? CampaignBanner.empty() : banner;
        this.updatedBy = actor;
    }

    public UUID id() { return id; }
    public String tenantRef() { return tenantRef; }
    public String name() { return name; }
    public LocalDate startsOn() { return startsOn; }
    public LocalDate endsOn() { return endsOn; }
    public CampaignStatus status() { return status; }
    public BenefitType benefitType() { return benefitType; }
    public LocalDate benefitOn() { return benefitOn; }
    public EntryCondition entryCondition() { return entryCondition; }
    public LocalDate memberJoinedFrom() { return memberJoinedFrom; }
    public LocalDate rewardExpiresOn() { return rewardExpiresOn; }
    public AmountBasis amountBasis() { return amountBasis; }
    public BigDecimal minOrderAmount() { return minOrderAmount; }
    public ShippingStatusRequirement shippingStatusRequired() { return shippingStatusRequired; }
    public String note() { return note; }
    public CampaignBanner banner() { return banner; }
    public String createdBy() { return createdBy; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
