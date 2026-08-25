package github.lms.lemuel.operation.dashboard.adapter.out.persistence;

import github.lms.lemuel.operation.dashboard.application.port.out.LoadOperationHealthPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 운영 서비스 자기 테이블에서 바로 세는 수치.
 *
 * <p>인시던트·발송 저널을 굳이 이벤트로 우회해 집계 테이블에 옮기지 않는 이유는 둘이다 —
 * (1) 같은 DB 라 실시간으로 셀 수 있는데 늦은 값을 만들 이유가 없고, (2) 같은 사실이 두 곳에
 * 있으면 언젠가 어긋나는데 그때 어느 쪽이 맞는지 아무도 모른다.
 *
 * <p>다른 슬라이스의 리포지토리를 재사용하지 않고 SQL 을 다시 쓴 것은 의도적이다. 대시보드가
 * incident·notification 슬라이스를 코드로 붙잡으면 슬라이스 간 의존이 생기고, 그런 화살표가
 * 몇 개 쌓이면 순환이 된다(ArchUnit 이 그 시점에 막지만, 그때는 이미 되돌리기 비싸다).
 * 여기서 아는 것은 테이블 이름과 상태 문자열뿐이며, 둘 다 마이그레이션의 CHECK 제약이 정본이다.
 */
@Component
public class JdbcOperationHealthAdapter implements LoadOperationHealthPort {

    // 상태 집합은 IncidentStatus.isActive() 와 uq_incident_active 부분 인덱스가 같은 값을 쓴다.
    private static final String COUNT_OPEN_INCIDENTS = """
            SELECT COUNT(*) FROM opslab.incidents
             WHERE status IN ('OPEN', 'ACKNOWLEDGED')
            """;

    // NO_CHANNEL 은 세지 않는다 — 활성 채널이 0개인 것은 배포 설정 문제지 발송 실패가 아니다.
    // 섞어 세면 채널을 안 붙인 환경에서 실패 카드가 늘 빨간불이라 진짜 실패를 아무도 안 본다
    // (JdbcNotificationJournal.statusOf 가 같은 구분을 한다).
    private static final String COUNT_FAILED_DISPATCHES = """
            SELECT COUNT(*) FROM opslab.notification_dispatches
             WHERE status IN ('FAILED', 'PARTIAL') AND created_at >= ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcOperationHealthAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpenIncidents() {
        return count(COUNT_OPEN_INCIDENTS);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFailedDispatchesSince(Instant since) {
        // notification_dispatches.created_at 은 TIMESTAMP(타임존 없음)라 값 자체로는 어느 존인지
        // 알 수 없다. 이 저널의 읽기 경로(JdbcNotificationJournal)가 이미 JVM 기본 존으로
        // Instant 를 오가므로 여기서도 같은 규약을 쓴다 — 한쪽만 다른 규약을 쓰면 목록에 보이는
        // 실패 건과 카드의 숫자가 어긋난다.
        Long total = jdbc.queryForObject(COUNT_FAILED_DISPATCHES, Long.class, Timestamp.from(since));
        return total == null ? 0L : total;
    }

    private long count(String sql) {
        Long total = jdbc.queryForObject(sql, Long.class);
        return total == null ? 0L : total;
    }
}
