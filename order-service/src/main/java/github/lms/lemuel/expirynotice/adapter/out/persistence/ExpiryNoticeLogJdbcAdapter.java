package github.lms.lemuel.expirynotice.adapter.out.persistence;

import github.lms.lemuel.expirynotice.application.port.out.RecordExpiryNoticePort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 통보 원장 선점 — {@code ON CONFLICT DO NOTHING} 으로 중복을 <b>예외 없이</b> 흘린다.
 *
 * <p>JPA 로 "있는지 보고 없으면 저장" 을 하지 않은 이유가 둘이다.
 *
 * <p>첫째, check-then-act 레이스다. 두 인스턴스가 동시에 조회하면 둘 다 "없음" 을 보고 둘 다 넣는다.
 * ShedLock 이 있어 보통은 한 대만 돌지만, 락 만료(lockAtMostFor) 이후 겹치는 순간이 실제로 있다.
 *
 * <p>둘째, PostgreSQL 에서 <b>제약 위반은 트랜잭션 전체를 abort 시킨다.</b> 중복을 예외로 받아
 * catch 하고 계속 돌면 그 뒤 문장이 전부 {@code current transaction is aborted} 로 죽는다.
 * 중복은 매일 도는 배치의 <i>정상</i> 상태라, 정상 상태가 트랜잭션을 깨뜨리게 두면 안 된다.
 *
 * <p>{@code ON CONFLICT} 는 PostgreSQL 문법이다. 이 서비스의 운영 DB 는 PostgreSQL 이고
 * 스키마 자체가 이미 {@code TIMESTAMPTZ} · 파티션 함수를 쓰므로 새로 생긴 종속은 아니다.
 */
@Component
public class ExpiryNoticeLogJdbcAdapter implements RecordExpiryNoticePort {

    private static final String CLAIM_SQL = """
            INSERT INTO expiry_notice_log (subject_type, subject_id, stage, user_id, amount, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (subject_type, subject_id, stage) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public ExpiryNoticeLogJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(ExpiringItem item, ExpiryNoticeStage stage) {
        int inserted = jdbcTemplate.update(CLAIM_SQL,
                item.subject().name(),
                item.subjectId(),
                stage.name(),
                item.userId(),
                item.amount(),
                Timestamp.from(item.expiresAt().toInstant()));
        // 0 = 이미 있었다(= 이미 보냈다). 1 = 이번에 내가 선점했다.
        return inserted == 1;
    }
}
