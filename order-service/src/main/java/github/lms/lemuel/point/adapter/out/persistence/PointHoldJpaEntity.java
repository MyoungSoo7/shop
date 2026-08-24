package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.PointHoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code point_holds} 매핑. 도메인({@link PointHold})과의 변환만 담당한다.
 *
 * <p>{@code @Version} 을 두지 않는다 — 선점의 상태 변경은 언제나 계정 행의 <b>비관적 락</b> 안에서
 * 일어난다. 낙관적 락을 겹치면 같은 경합을 두 방식으로 막게 되고, 재시도 정책만 두 벌이 된다.
 */
@Entity
@Table(name = "point_holds")
public class PointHoldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PointHoldStatus status;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected PointHoldJpaEntity() {
    }

    static PointHoldJpaEntity from(PointHold hold) {
        PointHoldJpaEntity entity = new PointHoldJpaEntity();
        entity.id = hold.getId();
        entity.accountId = hold.getAccountId();
        entity.amount = hold.getAmount();
        entity.createdAt = hold.getCreatedAt();
        entity.apply(hold);
        return entity;
    }

    /** 변경 가능한 필드만 덮어쓴다 — 금액·계정·근거는 선점이 만들어진 뒤 바뀌지 않는다. */
    void apply(PointHold hold) {
        this.status = hold.getStatus();
        this.referenceType = hold.getReferenceType();
        this.referenceId = hold.getReferenceId();
        this.resolvedAt = hold.getResolvedAt();
    }

    PointHold toDomain() {
        return PointHold.rehydrate(id, accountId, amount, status, referenceType, referenceId,
                createdAt, resolvedAt);
    }

    Long getId() {
        return id;
    }
}
