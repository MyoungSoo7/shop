package github.lms.lemuel.common.audit.adapter.out.persistence;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.application.port.out.SearchAuditLogsPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 감사 로그 조회 어댑터.
 *
 * <p><b>왜 JPQL 인가</b>(다른 콘솔 조회가 JdbcTemplate 인 것과 달리): 이 어댑터는 서비스마다
 * 스키마가 다른 테이블을 읽는다 — order 는 {@code opslab.audit_logs}, settlement 는
 * {@code public.audit_logs} 다. JdbcTemplate 은 커넥션 {@code search_path} 를 따르므로 스키마를
 * 문자열로 한정해야 하는데, 그러면 shared-common 한 벌이 두 서비스를 못 섬긴다. JPQL 은
 * Hibernate 의 {@code default_schema} 설정이 해석해 주므로 <b>같은 코드가 양쪽에서 각자의
 * 테이블</b>을 읽는다.
 *
 * <p><b>동적 조건을 문자열로 조립하는 이유</b>: {@code (:param IS NULL OR col = :param)} 관용구는
 * PostgreSQL 에서 파라미터 타입 추론이 {@code bytea} 로 떨어져 실행 시점에 터진다(이 저장소에서
 * 이미 밟은 함정). 그래서 <b>값이 있는 조건만</b> WHERE 에 붙이고, 그 조건에만 파라미터를 바인딩한다.
 * 조립되는 것은 우리가 쓴 상수 조각뿐이고 사용자 입력은 전부 바인딩 파라미터라 주입 경로가 없다.
 *
 * <p>정렬은 {@code created_at DESC, id DESC} 다. {@code created_at} 만으로는 같은 밀리초에 들어온
 * 두 행의 순서가 페이지마다 흔들려, 페이지를 넘길 때 같은 행이 두 번 보이거나 사라진다.
 */
@Repository
public class AuditLogSearchJpaAdapter implements SearchAuditLogsPort {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogRow> search(AuditLogCriteria criteria, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(criteria, params);

        TypedQuery<AuditLogJpaEntity> query = entityManager.createQuery(
                "SELECT a FROM AuditLogJpaEntity a " + where
                        + " ORDER BY a.createdAt DESC, a.id DESC",
                AuditLogJpaEntity.class);
        params.forEach(query::setParameter);

        return query.setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList()
                .stream()
                .map(AuditLogSearchJpaAdapter::toRow)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(AuditLogCriteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(criteria, params);

        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(a) FROM AuditLogJpaEntity a " + where, Long.class);
        params.forEach(query::setParameter);

        return query.getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditActionCount> countByAction(AuditLogCriteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(criteria, params);

        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT a.action, COUNT(a) FROM AuditLogJpaEntity a " + where
                        + " GROUP BY a.action ORDER BY COUNT(a) DESC, a.action ASC",
                Object[].class);
        params.forEach(query::setParameter);

        return query.getResultList().stream()
                .map(cols -> new AuditActionCount((String) cols[0], ((Number) cols[1]).longValue()))
                .toList();
    }

    /**
     * 값이 있는 조건만 WHERE 절로 조립하고, 같은 이름으로 바인딩할 파라미터를 채운다.
     *
     * <p>기간은 항상 있다 — 서비스가 기본값을 강제하므로 여기서 null 을 다룰 필요가 없다.
     */
    private static String buildWhere(AuditLogCriteria criteria, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();

        clauses.add("a.createdAt >= :from");
        params.put("from", criteria.from());
        clauses.add("a.createdAt < :toExclusive");
        params.put("toExclusive", criteria.toExclusive());

        if (criteria.actorEmail() != null) {
            clauses.add("LOWER(a.actorEmail) LIKE :actorEmail");
            params.put("actorEmail", "%" + criteria.actorEmail().toLowerCase() + "%");
        }
        if (criteria.actorId() != null) {
            clauses.add("a.actorId = :actorId");
            params.put("actorId", criteria.actorId());
        }
        if (criteria.action() != null) {
            clauses.add("a.action = :action");
            params.put("action", criteria.action());
        }
        if (criteria.resourceType() != null) {
            clauses.add("a.resourceType = :resourceType");
            params.put("resourceType", criteria.resourceType());
        }
        if (criteria.resourceId() != null) {
            clauses.add("a.resourceId = :resourceId");
            params.put("resourceId", criteria.resourceId());
        }

        return "WHERE " + String.join(" AND ", clauses);
    }

    private static AuditLogRow toRow(AuditLogJpaEntity entity) {
        return new AuditLogRow(
                entity.getId(),
                entity.getActorId(),
                entity.getActorEmail(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getDetailJson(),
                entity.getIpAddress(),
                entity.getUserAgent(),
                entity.getCreatedAt());
    }
}
