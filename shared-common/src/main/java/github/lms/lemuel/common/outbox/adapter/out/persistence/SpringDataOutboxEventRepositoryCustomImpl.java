package github.lms.lemuel.common.outbox.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link SpringDataOutboxEventRepositoryCustom} 의 네이티브 SQL 구현.
 *
 * <p>스키마 한정자({@code <schema>.outbox_events})를 {@link OutboxSchema} 에서 받아 SQL 문자열에
 * 직접 끼워 넣는다. 스키마명은 설정값({@code hibernate.default_schema})에서 오므로 신뢰 가능하나,
 * 식별자 화이트리스트로 한 번 더 검증해 네이티브 SQL 조립 시 인젝션 여지를 차단한다.
 *
 * <p>{@code select}→{@code stamp} 는 호출측({@code claimPending})의 트랜잭션 안에서 실행되어야
 * {@code FOR UPDATE SKIP LOCKED} 잠금이 유지된다.
 */
public class SpringDataOutboxEventRepositoryCustomImpl implements SpringDataOutboxEventRepositoryCustom {

    private static final Pattern VALID_SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @PersistenceContext
    private EntityManager em;

    private final String schema;

    public SpringDataOutboxEventRepositoryCustomImpl(OutboxSchema outboxSchema) {
        String name = outboxSchema.getName();
        if (name == null || !VALID_SCHEMA.matcher(name).matches()) {
            throw new IllegalStateException("Invalid outbox schema name: " + name);
        }
        this.schema = name;
    }

    @Override
    public List<Long> selectClaimableIds(int limit, long leaseSeconds) {
        String sql = "SELECT e.id FROM " + schema + ".outbox_events e "
                + "WHERE e.status = 'PENDING' "
                + "  AND (e.claimed_at IS NULL OR e.claimed_at < now() - (:leaseSeconds * INTERVAL '1 second')) "
                + "ORDER BY e.created_at "
                + "LIMIT :limit "
                + "FOR UPDATE SKIP LOCKED";
        List<?> rows = em.createNativeQuery(sql)
                .setParameter("leaseSeconds", leaseSeconds)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(r -> ((Number) r).longValue()).toList();
    }

    @Override
    public void stampClaim(List<Long> ids, String worker, LocalDateTime now) {
        if (ids.isEmpty()) {
            return;
        }
        em.createNativeQuery("UPDATE " + schema + ".outbox_events "
                        + "SET claimed_at = :now, claimed_by = :worker WHERE id IN (:ids)")
                .setParameter("now", now)
                .setParameter("worker", worker)
                .setParameter("ids", ids)
                .executeUpdate();
    }

    @Override
    public void clearClaim(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        em.createNativeQuery("UPDATE " + schema + ".outbox_events "
                        + "SET claimed_at = NULL, claimed_by = NULL WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .executeUpdate();
    }
}
