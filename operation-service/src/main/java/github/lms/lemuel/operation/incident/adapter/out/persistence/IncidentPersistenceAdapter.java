package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSearchCondition;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSummary;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.PageResult;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.SaveIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.SearchIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class IncidentPersistenceAdapter implements LoadIncidentPort, SaveIncidentPort, SearchIncidentPort {

    private static final Set<IncidentStatus> ACTIVE_STATUSES =
            EnumSet.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED);

    private final SpringDataIncidentRepository repository;

    public IncidentPersistenceAdapter(SpringDataIncidentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Incident> findActive(IncidentSource source, String correlationKey) {
        return repository.findFirstBySourceAndCorrelationKeyAndStatusIn(source, correlationKey, ACTIVE_STATUSES)
                .map(IncidentJpaEntity::toDomain);
    }

    @Override
    public Optional<Incident> findById(Long id) {
        return repository.findById(id).map(IncidentJpaEntity::toDomain);
    }

    @Override
    public Incident save(Incident incident) {
        // 도메인 스냅샷 → detached 엔티티 재구성 후 save(merge).
        // 도메인이 들고 온 @Version 이 그대로 실리므로 운영자 조작 경쟁 시
        // ObjectOptimisticLockingFailureException, 동시 INSERT 경쟁 시
        // uq_incident_active 위반(DataIntegrityViolationException)이 호출자로 전파된다.
        IncidentJpaEntity saved = repository.saveAndFlush(IncidentJpaEntity.fromDomain(incident));
        return saved.toDomain();
    }

    @Override
    public PageResult<Incident> search(IncidentSearchCondition c) {
        // ★ JPQL ":param IS NULL OR" 패턴은 PG 에서 bytea 오류 — 동적 조건은 Specification 으로 조립한다.
        Specification<IncidentJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.status() != null) {
                predicates.add(cb.equal(root.get("status"), c.status()));
            }
            if (c.category() != null) {
                predicates.add(cb.equal(root.get("category"), c.category()));
            }
            if (c.severity() != null) {
                predicates.add(cb.equal(root.get("severity"), c.severity()));
            }
            if (c.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("firstSeenAt"), c.from()));
            }
            if (c.to() != null) {
                predicates.add(cb.lessThan(root.get("firstSeenAt"), c.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<IncidentJpaEntity> page = repository.findAll(spec,
                PageRequest.of(c.page(), c.size(), Sort.by(Sort.Direction.DESC, "lastSeenAt")));

        return new PageResult<>(
                page.getContent().stream().map(IncidentJpaEntity::toDomain).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    public IncidentSummary summarize(Instant from) {
        long openTotal = repository.countByStatusIn(ACTIVE_STATUSES);

        Map<IncidentStatus, Long> byStatus = toEnumCountMap(repository.countByStatusSince(from), IncidentStatus.class);
        Map<SignalCategory, Long> byCategory = toEnumCountMap(repository.countByCategorySince(from), SignalCategory.class);
        Map<IncidentSeverity, Long> bySeverity = toEnumCountMap(repository.countBySeveritySince(from), IncidentSeverity.class);

        Double mttr = repository.averageResolutionSeconds(from);
        Long mttrSeconds = mttr == null ? null : Math.round(mttr);

        return new IncidentSummary(openTotal, byStatus, byCategory, bySeverity, mttrSeconds);
    }

    private <E extends Enum<E>> Map<E, Long> toEnumCountMap(List<Object[]> rows, Class<E> type) {
        Map<E, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put(type.cast(row[0]), (Long) row[1]);
        }
        return result;
    }
}
