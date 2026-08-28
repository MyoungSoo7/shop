package github.lms.lemuel.marketing.domain;

/**
 * 캠페인 노출·참여 상태. 레거시 {@code EVENT_ST} 의 '2'(진행)/'3'(종료)에 초안(DRAFT)을 더했다.
 *
 * <p>레거시에는 "만들었지만 아직 안 여는" 상태가 없어서, 운영자가 캠페인을 등록하는 순간
 * 기간만 맞으면 화면에 떴다. 이미지·문구를 채우는 중에 노출되는 사고가 그래서 났다.
 */
public enum CampaignStatus {

    /** 작성 중 — 공개 API 에 노출되지 않고 참여도 받지 않는다. */
    DRAFT,

    /** 진행 — 기간 안이면 참여 가능. */
    RUNNING,

    /** 종료 — 조회는 되지만 참여는 거절한다. */
    CLOSED;

    public boolean isPubliclyVisible() {
        return this != DRAFT;
    }
}
