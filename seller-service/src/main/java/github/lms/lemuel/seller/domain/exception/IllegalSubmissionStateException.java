package github.lms.lemuel.seller.domain.exception;

import github.lms.lemuel.seller.domain.SubmissionStatus;

/**
 * 지금 상태에서 할 수 없는 전이를 시도했다 (심사 중인 신청서 수정, 이미 승인된 건 재승인 등).
 *
 * <p>메시지에 <b>현재 상태</b>를 담는 것이 요점이다. 백오피스에서 이 예외가 나는 가장 흔한
 * 경로는 두 사람이 같은 신청서를 동시에 열어 둔 경우다 — 한 명이 제출한 뒤 다른 명이 수정을
 * 누른다. "잘못된 요청" 만 보여 주면 그 사람은 자기 입력이 틀린 줄 알고 계속 고친다. 상태를
 * 말해 주면 화면을 새로고침한다.
 */
public class IllegalSubmissionStateException extends RuntimeException {

    public IllegalSubmissionStateException(SubmissionStatus current, String attempted) {
        super("현재 상태(" + current + ")에서는 " + attempted + " 할 수 없습니다. 화면을 새로고침해 주세요.");
    }
}
