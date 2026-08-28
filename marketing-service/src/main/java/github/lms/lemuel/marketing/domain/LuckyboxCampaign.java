package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 럭키박스 캠페인 애그리거트 루트 (레거시 {@code TBL_LUCKYBOX}).
 *
 * <p>참여 가능 여부와 보상 지급 시점을 결정한다. 경품 목록과 추첨은 각각
 * {@link LuckyboxPrize} 와 {@link PrizeDraw} 가 맡는다 — 캠페인이 경품 리스트를 품으면
 * 참여 한 번에 경품 전체를 적재하게 되고, 그 리스트는 동시 참여자마다 다르게 소진된다.
 *
 * <p><b>가입일·주문금액·배송상태 조건은 여기 없다.</b> 한때 필드로 들고 컬럼에 저장까지 했지만
 * 아무도 읽지 않았다. 왜 지웠는지와 되살리려면 무엇이 먼저 있어야 하는지는
 * {@code docs/plan/marketing-legacy-gap.md} §2 ④ 에 있다.
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
    private LocalDate rewardExpiresOn;
    private String note;
    private CampaignBanner banner;
    private final String createdBy;
    private String updatedBy;
    private final long version;

    private LuckyboxCampaign(UUID id, String tenantRef, String name, LocalDate startsOn, LocalDate endsOn,
                             CampaignStatus status, BenefitType benefitType, LocalDate benefitOn,
                             EntryCondition entryCondition, LocalDate rewardExpiresOn, String note,
                             CampaignBanner banner, String createdBy, String updatedBy, long version) {
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
        this.rewardExpiresOn = rewardExpiresOn;
        this.note = note;
        this.banner = banner == null ? CampaignBanner.empty() : banner;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static LuckyboxCampaign draft(UUID id, String tenantRef, String name, LocalDate startsOn, LocalDate endsOn,
                                         BenefitType benefitType, LocalDate benefitOn, EntryCondition entryCondition,
                                         LocalDate rewardExpiresOn, String note, CampaignBanner banner, String actor) {
        return new LuckyboxCampaign(id, tenantRef, name, startsOn, endsOn, CampaignStatus.DRAFT, benefitType,
                benefitOn, entryCondition, rewardExpiresOn, note, banner, actor, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static LuckyboxCampaign rehydrate(UUID id, String tenantRef, String name, LocalDate startsOn,
                                             LocalDate endsOn, CampaignStatus status, BenefitType benefitType,
                                             LocalDate benefitOn, EntryCondition entryCondition,
                                             LocalDate rewardExpiresOn, String note, CampaignBanner banner,
                                             String createdBy, String updatedBy, long version) {
        return new LuckyboxCampaign(id, tenantRef, name, startsOn, endsOn, status, benefitType, benefitOn,
                entryCondition, rewardExpiresOn, note, banner, createdBy, updatedBy, version);
    }

    /**
     * 오늘 이 캠페인에 참여할 수 있는지 확인한다. 못 하면 던진다.
     *
     * <p>여기서 보는 것이 참여 자격의 <b>전부</b>다 — 캠페인이 열려 있는가, 오늘이 기간 안인가.
     * 한 사람이 몇 번 참여할 수 있는지는 이 메서드가 아니라 {@code (campaign_id, member_ref,
     * entry_slot)} 유니크 인덱스가 막는다({@link #entrySlot}). 조건을 늘리려면 그 조건에 필요한
     * 데이터가 이 서비스 안에 있어야 한다.
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
    public LocalDate rewardExpiresOn() { return rewardExpiresOn; }
    public String note() { return note; }
    public CampaignBanner banner() { return banner; }
    public String createdBy() { return createdBy; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
