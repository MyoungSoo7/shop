package github.lms.lemuel.operation.dashboard.application.port.out;

import java.time.Instant;

/**
 * 운영 서비스가 <b>자기 DB 에서 바로 읽을 수 있는</b> 수치.
 *
 * <p>인시던트와 알림 발송 저널은 이 서비스의 테이블이다. 이벤트로 우회해 집계 테이블에
 * 옮겨 담을 이유가 없다 — 늦은 값이 될 뿐이고, 같은 사실을 두 곳에 두면 어긋난다.
 * 그래서 이 둘만 실시간으로 세고 나머지는 집계 테이블에서 읽는다.
 *
 * <p>포트를 따로 두는 것은 슬라이스 경계 때문이다. 대시보드가 incident·notification 슬라이스의
 * 리포지토리를 직접 잡으면 슬라이스 사이에 의존이 생기고, 그 방향이 늘어나면 순환으로 자란다.
 */
public interface LoadOperationHealthPort {

    /** 아직 닫히지 않은(OPEN·ACKNOWLEDGED) 인시던트 수. */
    long countOpenIncidents();

    /** {@code since} 이후 실패로 끝난 알림 발송 건수(FAILED·PARTIAL). */
    long countFailedDispatchesSince(Instant since);
}
