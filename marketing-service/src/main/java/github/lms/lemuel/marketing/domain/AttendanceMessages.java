package github.lms.lemuel.marketing.domain;

/**
 * 출석 화면에 상태별로 띄우는 문구. 레거시 {@code EVENT_MESSAGE1~4} 다.
 *
 * <p>번호가 아니라 이름으로 부른다 — 레거시 JSP 는 {@code EVENT_MESSAGE3} 가 "달성 축하" 인지
 * "종료 안내" 인지 화면을 열어 봐야 알 수 있었다.
 */
public record AttendanceMessages(String beforeStart, String running, String achieved, String closed) {

    private static final AttendanceMessages EMPTY = new AttendanceMessages(null, null, null, null);

    public static AttendanceMessages empty() {
        return EMPTY;
    }

    /** 지금 화면에 띄울 문구 하나. */
    public String forState(CampaignStatus status, boolean started, boolean goalAchieved) {
        if (status == CampaignStatus.CLOSED) {
            return closed;
        }
        if (!started) {
            return beforeStart;
        }
        return goalAchieved ? achieved : running;
    }
}
