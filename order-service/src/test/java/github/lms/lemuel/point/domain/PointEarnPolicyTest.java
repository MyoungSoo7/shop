package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적립률 정책 단위 테스트.
 *
 * <p>적립률을 코드 상수가 아니라 "기간을 가진 데이터"로 다룬다(ADR 0032 구조 재사용).
 * 정책이 하나도 없으면 적립도 없다 — 이 기능의 무행동 착지가 여기서 나온다.
 */
class PointEarnPolicyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 1);

    private PointEarnPolicy global(String rate, LocalDate from, LocalDate to) {
        return PointEarnPolicy.of(PointEarnScope.GLOBAL, "*", new BigDecimal(rate), 365,
                from, to, "테스트 정책", "admin");
    }

    @Nested
    @DisplayName("earnFor — 적립액 산정")
    class EarnTests {

        @Test
        @DisplayName("주문금액 × 적립률로 계산한다")
        void earnFor_multipliesRate() {
            PointEarnPolicy policy = global("0.01000", FROM, null);

            assertThat(policy.earnFor(new BigDecimal("50000")))
                    .isEqualByComparingTo(new BigDecimal("500"));
        }

        @Test
        @DisplayName("원 미만은 절사한다 — 반올림하면 회사가 없는 돈을 준다")
        void earnFor_truncatesFraction() {
            PointEarnPolicy policy = global("0.01000", FROM, null);

            // 12345 × 1% = 123.45 → 123
            assertThat(policy.earnFor(new BigDecimal("12345")))
                    .isEqualByComparingTo(new BigDecimal("123"));
            // 19999 × 1% = 199.99 → 199 (반올림이면 200)
            assertThat(policy.earnFor(new BigDecimal("19999")))
                    .isEqualByComparingTo(new BigDecimal("199"));
        }

        @Test
        @DisplayName("적립액이 1원 미만이면 0 이다 — 적립 자체가 일어나지 않는다 (경계값)")
        void earnFor_belowOneUnitIsZero() {
            PointEarnPolicy policy = global("0.01000", FROM, null);

            assertThat(policy.earnFor(new BigDecimal("99"))).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("적립률 0 정책은 언제나 0 을 적립한다")
        void earnFor_zeroRate() {
            PointEarnPolicy policy = global("0.00000", FROM, null);

            assertThat(policy.earnFor(new BigDecimal("100000"))).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("appliesOn — 유효기간")
    class EffectiveRangeTests {

        @Test
        @DisplayName("시작일 당일부터 적용된다 (경계값)")
        void appliesFromStartInclusive() {
            PointEarnPolicy policy = global("0.01000", FROM, TO);

            assertThat(policy.appliesOn(FROM.minusDays(1))).isFalse();
            assertThat(policy.appliesOn(FROM)).isTrue();
        }

        @Test
        @DisplayName("종료일 당일은 적용되지 않는다 — [from, to) 반열림 (경계값)")
        void endIsExclusive() {
            PointEarnPolicy policy = global("0.01000", FROM, TO);

            assertThat(policy.appliesOn(TO.minusDays(1))).isTrue();
            assertThat(policy.appliesOn(TO)).isFalse();
        }

        @Test
        @DisplayName("종료일이 없으면 무기한 적용된다")
        void openEndedPolicy() {
            PointEarnPolicy policy = global("0.01000", FROM, null);

            assertThat(policy.appliesOn(FROM.plusYears(10))).isTrue();
        }
    }

    @Nested
    @DisplayName("생성 규약")
    class CreationTests {

        @Test
        @DisplayName("적립률은 0 이상 1 이하여야 한다")
        void rejectsRateOutOfRange() {
            assertThatThrownBy(() -> global("-0.01000", FROM, null))
                    .isInstanceOf(InvalidPointStateException.class);
            assertThatThrownBy(() -> global("1.00001", FROM, null))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("유효기간 일수는 양수여야 한다")
        void rejectsNonPositiveValidityDays() {
            assertThatThrownBy(() -> PointEarnPolicy.of(PointEarnScope.GLOBAL, "*",
                    new BigDecimal("0.01000"), 0, FROM, null, "사유", "admin"))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("종료일은 시작일보다 뒤여야 한다")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> global("0.01000", TO, FROM))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("근거(reason)가 없으면 거절한다 — 감사 없이 적립률이 바뀌지 않게")
        void requiresReason() {
            assertThatThrownBy(() -> PointEarnPolicy.of(PointEarnScope.GLOBAL, "*",
                    new BigDecimal("0.01000"), 365, FROM, null, "  ", "admin"))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("expiryFrom — 적립분 만료일")
    class ExpiryTests {

        @Test
        @DisplayName("적립 시각 + 유효기간 일수가 만료 시각이다")
        void expiryIsGrantPlusValidityDays() {
            PointEarnPolicy policy = PointEarnPolicy.of(PointEarnScope.GLOBAL, "*",
                    new BigDecimal("0.01000"), 30, FROM, null, "사유", "admin");
            OffsetDateTime grantedAt = OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.UTC);

            assertThat(policy.expiryFrom(grantedAt)).isEqualTo(grantedAt.plusDays(30));
        }
    }

    @Nested
    @DisplayName("PointEarnPolicyResolver — 해석")
    class ResolverTests {

        @Test
        @DisplayName("정책이 없으면 비어 있는 결과 — 적립이 일어나지 않는다 (무행동 착지)")
        void noPolicyMeansNoEarn() {
            assertThat(PointEarnPolicyResolver.resolve(List.of(), FROM)).isEmpty();
        }

        @Test
        @DisplayName("기간이 지난 정책은 후보에서 빠진다")
        void ignoresOutOfRangePolicies() {
            PointEarnPolicy expired = global("0.01000", FROM, TO);

            assertThat(PointEarnPolicyResolver.resolve(List.of(expired), TO.plusDays(1))).isEmpty();
        }

        @Test
        @DisplayName("더 구체적인 scope 가 이긴다 — CATEGORY > GRADE > GLOBAL")
        void mostSpecificScopeWins() {
            PointEarnPolicy globalPolicy = global("0.01000", FROM, null);
            PointEarnPolicy gradePolicy = PointEarnPolicy.of(PointEarnScope.GRADE, "VIP",
                    new BigDecimal("0.02000"), 365, FROM, null, "VIP 우대", "admin");
            PointEarnPolicy categoryPolicy = PointEarnPolicy.of(PointEarnScope.CATEGORY, "1001",
                    new BigDecimal("0.05000"), 365, FROM, null, "카테고리 행사", "admin");

            Optional<PointEarnPolicy> resolved = PointEarnPolicyResolver.resolve(
                    List.of(globalPolicy, categoryPolicy, gradePolicy), FROM);

            assertThat(resolved).isPresent();
            assertThat(resolved.get().getScope()).isEqualTo(PointEarnScope.CATEGORY);
            assertThat(resolved.get().getEarnRate()).isEqualByComparingTo(new BigDecimal("0.05000"));
        }
    }
}
