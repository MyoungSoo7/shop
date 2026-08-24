package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 포인트 적립률 정책 — 기간을 가진 데이터(ADR 0032 구조 재사용).
 *
 * <p>적립률을 enum 상수나 등급 행의 필드로 두지 않는다. ofDentis 레거시가
 * {@code GradeMasterEntity.pointRate} 를 {@code Float} 로 들고 있었는데, 그러면 요율 변경이
 * 배포가 되고 타입은 금액 가드레일과 충돌한다. 여기서는 {@code BigDecimal} + 기간 행이다.
 *
 * <p>정책 행이 하나도 없으면 적립도 없다 — 이 기능의 무행동 착지가 여기서 나온다.
 * {@code closed_at}(조기 종료 시각)은 감사 메타데이터이며, 적용 여부는 언제나
 * {@code [effectiveFrom, effectiveTo)} 반열림 구간이 결정한다.
 */
public class PointEarnPolicy {

    /** 적립액 스케일 — 포인트는 1원 단위 정수다. */
    private static final int EARN_SCALE = 0;
    /**
     * 원 미만 <b>절사</b>. 반올림하면 회사가 정책상 약속하지 않은 1원을 주게 되고,
     * 그 1원이 수백만 건 쌓이면 판촉비가 정책과 어긋난다.
     */
    private static final RoundingMode EARN_ROUNDING = RoundingMode.DOWN;
    /** 단위를 지정하지 않은 정책의 적립 단위 — 1 원(기존 동작 보존). */
    private static final int DEFAULT_ROUNDING_UNIT = 1;

    private final Long id;
    private final PointEarnScope scope;
    private final String scopeKey;
    private final BigDecimal earnRate;
    private final int validityDays;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveTo;
    private final String reason;
    private final String createdBy;
    /** 적립 단위 — 1(원 단위) / 10 / 100 / 1000. 이 단위 경계에서 {@link #rounding} 이 방향을 정한다. */
    private final int roundingUnit;
    private final PointEarnRounding rounding;

    private PointEarnPolicy(Long id, PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                            int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                            String reason, String createdBy,
                            int roundingUnit, PointEarnRounding rounding) {
        this.roundingUnit = roundingUnit;
        this.rounding = rounding;
        this.id = id;
        this.scope = scope;
        this.scopeKey = scopeKey;
        this.earnRate = earnRate;
        this.validityDays = validityDays;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    /** 단위·방식을 생략한 정책 — 1 원 단위 버림(이 기능의 기존 동작). */
    public static PointEarnPolicy of(PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                                     int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                                     String reason, String createdBy) {
        return of(scope, scopeKey, earnRate, validityDays, effectiveFrom, effectiveTo,
                reason, createdBy, DEFAULT_ROUNDING_UNIT, PointEarnRounding.DOWN);
    }

    public static PointEarnPolicy of(PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                                     int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                                     String reason, String createdBy,
                                     int roundingUnit, PointEarnRounding rounding) {
        validate(scope, scopeKey, earnRate, validityDays, effectiveFrom, effectiveTo, reason);
        validateRounding(roundingUnit, rounding);
        return new PointEarnPolicy(null, scope, scopeKey, earnRate, validityDays,
                effectiveFrom, effectiveTo, reason, createdBy, roundingUnit, rounding);
    }

    public static PointEarnPolicy rehydrate(Long id, PointEarnScope scope, String scopeKey,
                                            BigDecimal earnRate, int validityDays,
                                            LocalDate effectiveFrom, LocalDate effectiveTo,
                                            String reason, String createdBy) {
        return rehydrate(id, scope, scopeKey, earnRate, validityDays, effectiveFrom, effectiveTo,
                reason, createdBy, DEFAULT_ROUNDING_UNIT, PointEarnRounding.DOWN);
    }

    public static PointEarnPolicy rehydrate(Long id, PointEarnScope scope, String scopeKey,
                                            BigDecimal earnRate, int validityDays,
                                            LocalDate effectiveFrom, LocalDate effectiveTo,
                                            String reason, String createdBy,
                                            int roundingUnit, PointEarnRounding rounding) {
        return new PointEarnPolicy(id, scope, scopeKey, earnRate, validityDays,
                effectiveFrom, effectiveTo, reason, createdBy,
                roundingUnit <= 0 ? DEFAULT_ROUNDING_UNIT : roundingUnit,
                rounding == null ? PointEarnRounding.DOWN : rounding);
    }

    private static void validateRounding(int roundingUnit, PointEarnRounding rounding) {
        if (roundingUnit <= 0) {
            throw new InvalidPointStateException(
                    "적립 단위는 양수여야 합니다: " + roundingUnit, "NONE", "policy");
        }
        if (rounding == null) {
            throw new InvalidPointStateException("적립 라운딩 방식은 필수입니다", "NONE", "policy");
        }
    }

    private static void validate(PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                                 int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                                 String reason) {
        if (scope == null || scopeKey == null || scopeKey.isBlank()) {
            throw new InvalidPointStateException("정책 범위(scope/scopeKey)가 비었습니다", "NONE", "policy");
        }
        if (earnRate == null || earnRate.signum() < 0 || earnRate.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidPointStateException(
                    "적립률은 0 이상 1 이하여야 합니다: " + earnRate, "NONE", "policy");
        }
        if (validityDays <= 0) {
            throw new InvalidPointStateException(
                    "유효기간 일수는 양수여야 합니다: " + validityDays, "NONE", "policy");
        }
        if (effectiveFrom == null) {
            throw new InvalidPointStateException("시작일이 없습니다", "NONE", "policy");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new InvalidPointStateException(
                    "종료일(" + effectiveTo + ")은 시작일(" + effectiveFrom + ")보다 뒤여야 합니다", "NONE", "policy");
        }
        // 적립률 변경의 근거를 남기지 않으면, 나중에 "왜 이 율이었나"에 답할 수 없다.
        if (reason == null || reason.isBlank()) {
            throw new InvalidPointStateException("적립률 근거(reason)는 필수입니다", "NONE", "policy");
        }
    }

    /**
     * 주문금액에 적립률을 적용한 적립액 — <b>적립 단위 경계</b>에서 정책의 라운딩 방식으로 맞춘다.
     *
     * <p>예) 12,345 원 × 1.5% = 185.175
     * <ul>
     *   <li>단위 1 · 버림 → 185 (기존 동작)</li>
     *   <li>단위 10 · 버림 → 180, 단위 10 · 반올림/올림 → 190</li>
     *   <li>단위 100 · 반올림 → 200</li>
     * </ul>
     *
     * <p>레거시 커머스는 "1 원 단위로 반올림한 뒤 단위로 버림"이라 단위가 1 보다 크면 반올림·올림
     * 설정이 사실상 무의미했다(무엇을 고르든 결과가 버림과 같아지는 구간이 넓다). 여기서는 방식을
     * 단위 경계에 적용해 설정이 실제로 금액을 바꾸게 한다.
     *
     * <p>주문 금액이 0 이하면 올림 정책이어도 0 이다 — 적립 사유 자체가 없다.
     */
    public BigDecimal earnFor(BigDecimal orderAmount) {
        if (orderAmount == null || orderAmount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(EARN_SCALE, EARN_ROUNDING);
        }
        BigDecimal unit = BigDecimal.valueOf(roundingUnit);
        return orderAmount.multiply(earnRate)
                .divide(unit, EARN_SCALE, rounding.toRoundingMode())
                .multiply(unit);
    }

    /** {@code [effectiveFrom, effectiveTo)} 반열림 — 경계 접촉은 중첩이 아니다(DB EXCLUDE 와 같은 규약). */
    public boolean appliesOn(LocalDate date) {
        if (date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || date.isBefore(effectiveTo);
    }

    /** 이 정책으로 적립한 로트의 만료 시각. */
    public OffsetDateTime expiryFrom(OffsetDateTime grantedAt) {
        return grantedAt.plusDays(validityDays);
    }

    public Long getId() { return id; }
    public PointEarnScope getScope() { return scope; }
    public String getScopeKey() { return scopeKey; }
    public BigDecimal getEarnRate() { return earnRate; }
    public int getValidityDays() { return validityDays; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public int getRoundingUnit() { return roundingUnit; }
    public PointEarnRounding getRounding() { return rounding; }
}
