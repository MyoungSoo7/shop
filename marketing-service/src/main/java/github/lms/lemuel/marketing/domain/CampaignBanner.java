package github.lms.lemuel.marketing.domain;

/**
 * 캠페인 배너 이미지 — PC/모바일 한 쌍.
 *
 * <p>두 캠페인 애그리거트가 똑같이 들고 있는 값이라 묶었다. 생성자 인자 스무 개짜리
 * 애그리거트에서 이미지 URL 두 개가 어느 자리인지 세는 일이 없어진다.
 */
public record CampaignBanner(String pcImageUrl, String mobileImageUrl) {

    private static final CampaignBanner EMPTY = new CampaignBanner(null, null);

    public static CampaignBanner empty() {
        return EMPTY;
    }

    public static CampaignBanner of(String pcImageUrl, String mobileImageUrl) {
        if (pcImageUrl == null && mobileImageUrl == null) {
            return EMPTY;
        }
        return new CampaignBanner(pcImageUrl, mobileImageUrl);
    }
}
