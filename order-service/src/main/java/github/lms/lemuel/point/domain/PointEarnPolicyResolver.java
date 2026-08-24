package github.lms.lemuel.point.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 적립률 정책 해석 — 후보 중 <b>가장 구체적인</b> 하나를 고른다.
 *
 * <p>후보는 저장소가 이미 주문의 실제 키(등급·카테고리)로 걸러서 넘긴다. 여기서는 적용 기간만
 * 확인하고 {@link PointEarnScope#priority()} 가 큰 쪽을 택한다. 같은 scope 안에서 기간이 겹치는
 * 행은 DB {@code EXCLUDE} 제약이 입력 시점에 막으므로, 우선순위 동률로 해석이 흔들리지 않는다.
 *
 * <p>결과가 비면 <b>적립하지 않는다</b>. 기본 적립률로 몰래 폴백하지 않는 이유는, 정책 표가
 * 비었을 때 도입 전과 동일하게 동작해야 하기 때문이다(무행동 착지).
 */
public final class PointEarnPolicyResolver {

    private static final Comparator<PointEarnPolicy> MOST_SPECIFIC_FIRST =
            Comparator.comparingInt((PointEarnPolicy policy) -> policy.getScope().priority()).reversed();

    private PointEarnPolicyResolver() {
    }

    public static Optional<PointEarnPolicy> resolve(List<PointEarnPolicy> candidates, LocalDate on) {
        return candidates.stream()
                .filter(policy -> policy.appliesOn(on))
                .min(MOST_SPECIFIC_FIRST);
    }
}
