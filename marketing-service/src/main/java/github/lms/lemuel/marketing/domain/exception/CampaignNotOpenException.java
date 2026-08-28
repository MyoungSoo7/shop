package github.lms.lemuel.marketing.domain.exception;

/**
 * 캠페인이 지금 참여를 받지 않는다 — 초안이거나, 종료됐거나, 기간 밖이다.
 *
 * <p>셋을 한 예외로 묶은 것은 의도다. "아직 시작 전" 과 "이미 종료" 를 구분해 알려 주면
 * 캠페인 일정이 밖에서 보인다. 화면 문구는 캠페인의 message_* 가 담당한다.
 */
public class CampaignNotOpenException extends RuntimeException {
    public CampaignNotOpenException(String message) {
        super(message);
    }
}
