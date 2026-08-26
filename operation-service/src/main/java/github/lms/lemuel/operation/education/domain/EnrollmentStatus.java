package github.lms.lemuel.operation.education.domain;

/**
 * 수강 신청 상태 — dentis 의 lecture_state('대기'/'신청완료'/'취소') 를 옮긴 것.
 *
 * <p>결제 상태는 여기 없다. 돈은 order-service 가 소유하고, 이 열거형은 "자리를 잡았는가"만 말한다.
 */
public enum EnrollmentStatus { WAITING, CONFIRMED, CANCELLED }
