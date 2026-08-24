package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnRounding;
import github.lms.lemuel.point.domain.PointEarnScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** {@code point_earn_policy} 매핑 — 행 UPDATE 금지, 변경은 close + 신규 행(ADR 0032 규약). */
@Entity
@Table(name = "point_earn_policy")
public class PointEarnPolicyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private PointEarnScope scope;

    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    @Column(name = "earn_rate", nullable = false, precision = 6, scale = 5)
    private BigDecimal earnRate;

    @Column(name = "validity_days", nullable = false)
    private int validityDays;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "rounding_unit", nullable = false)
    private int roundingUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false, length = 10)
    private PointEarnRounding roundingMode;

    protected PointEarnPolicyJpaEntity() {
    }

    /**
     * 신규 등록용 — 도메인이 이미 검증을 마친 값만 받는다.
     *
     * <p>세터 대신 팩토리를 두는 이유: 이 표는 <b>행 UPDATE 금지</b>가 규약이라(ADR 0032),
     * 필드를 개별로 바꿀 수 있는 손잡이를 만들면 규약이 코드로 지켜지지 않는다.
     * 바꿀 수 있는 것은 종료({@link #closeAt})뿐이다.
     */
    static PointEarnPolicyJpaEntity from(PointEarnPolicy policy) {
        PointEarnPolicyJpaEntity entity = new PointEarnPolicyJpaEntity();
        entity.scope = policy.getScope();
        entity.scopeKey = policy.getScopeKey();
        entity.earnRate = policy.getEarnRate();
        entity.validityDays = policy.getValidityDays();
        entity.effectiveFrom = policy.getEffectiveFrom();
        entity.effectiveTo = policy.getEffectiveTo();
        entity.reason = policy.getReason();
        entity.createdBy = policy.getCreatedBy();
        entity.createdAt = OffsetDateTime.now();
        entity.roundingUnit = policy.getRoundingUnit();
        entity.roundingMode = policy.getRounding();
        return entity;
    }

    /**
     * 종료일 지정. {@code closedAt} 은 <b>언제 끊었는지의 기록</b>이지 적용 여부가 아니다 —
     * 종료일이 미래면 그날까지는 계속 적용된다(적용 여부는 날짜 범위가 정한다).
     */
    void closeAt(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
        this.closedAt = OffsetDateTime.now();
    }

    PointEarnPolicy toDomain() {
        return PointEarnPolicy.rehydrate(id, scope, scopeKey, earnRate, validityDays,
                effectiveFrom, effectiveTo, reason, createdBy, roundingUnit, roundingMode);
    }
}
