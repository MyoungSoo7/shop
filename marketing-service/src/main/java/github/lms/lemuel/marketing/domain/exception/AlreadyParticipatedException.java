package github.lms.lemuel.marketing.domain.exception;

/**
 * 이미 참여했다 — 출석은 하루 한 번, 럭키박스는 캠페인 조건(하루 1회/기간 1회)당 한 번.
 *
 * <p>이 예외는 애플리케이션의 사전 조회에서도 나오지만, 진짜 방어선은 DB 유니크 인덱스다.
 * 동시에 들어온 두 요청은 사전 조회를 둘 다 통과할 수 있고, 그때는 INSERT 가 하나를 튕긴다.
 * 어댑터가 그 제약 위반을 잡아 다시 이 예외로 바꾼다 — 사용자에게는 같은 상황이다.
 */
public class AlreadyParticipatedException extends RuntimeException {
    public AlreadyParticipatedException(String message) {
        super(message);
    }

    /** 제약 위반을 옮겨 담을 때 쓴다 — 원인을 버리면 로그에서 어느 인덱스였는지 사라진다. */
    public AlreadyParticipatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
