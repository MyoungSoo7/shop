package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLotConsumption;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code point_entries} 매핑 (append-only).
 *
 * <p>배분 상세를 {@code CascadeType.ALL} 로 함께 저장한다 — 엔트리와 배분이 따로 저장되면
 * "금액은 있는데 어느 로트를 건드렸는지 모르는" 행이 생길 수 있다.
 */
@Entity
@Table(name = "point_entries")
public class PointEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private PointEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "memo", length = 255)
    private String memo;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "entry_id", nullable = false)
    private List<PointLotConsumptionJpaEntity> allocations = new ArrayList<>();

    protected PointEntryJpaEntity() {
    }

    static PointEntryJpaEntity from(PointEntry entry) {
        PointEntryJpaEntity jpa = new PointEntryJpaEntity();
        jpa.accountId = entry.getAccountId();
        jpa.entryType = entry.getType();
        jpa.amount = entry.getAmount();
        jpa.referenceType = entry.getReferenceType();
        jpa.referenceId = entry.getReferenceId();
        jpa.sequence = entry.getSequence();
        jpa.memo = entry.getMemo();
        jpa.createdBy = entry.getCreatedBy();
        jpa.createdAt = entry.getCreatedAt();
        jpa.allocations = entry.getAllocations().stream()
                .map(PointLotConsumptionJpaEntity::from)
                .toList();
        return jpa;
    }

    github.lms.lemuel.point.domain.PointEntry toDomain() {
        List<PointLotConsumption> domainAllocations = allocations.stream()
                .map(PointLotConsumptionJpaEntity::toDomain)
                .toList();
        return github.lms.lemuel.point.domain.PointEntry.rehydrate(id, accountId, entryType, amount,
                referenceType, referenceId, sequence, memo, createdBy, createdAt, domainAllocations);
    }

    Long getId() {
        return id;
    }
}
