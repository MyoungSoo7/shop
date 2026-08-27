package github.lms.lemuel.marketing.domain.exception;

/** 오늘은 이 캠페인의 출석 인정일이 아니다 (평일 전용 캠페인의 주말 등). */
public class DayNotEligibleException extends RuntimeException {
    public DayNotEligibleException(String message) {
        super(message);
    }
}
