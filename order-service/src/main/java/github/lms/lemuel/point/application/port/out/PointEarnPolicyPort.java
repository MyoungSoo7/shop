package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointEarnPolicy;

import java.time.LocalDate;
import java.util.List;

/**
 * 적립률 정책 조회 포트.
 *
 * <p>후보를 주문의 실제 키로 걸러서 돌려준다. 우선순위 해석은 {@code PointEarnPolicyResolver}
 * 의 몫이라 여기서 하지 않는다 — 해석 규칙이 SQL 과 도메인 두 곳에 흩어지면 드리프트한다.
 */
public interface PointEarnPolicyPort {

    /**
     * @param gradeKey    회원 등급 키(없으면 null) — Phase 1 에서는 항상 null
     * @param categoryKey 카테고리 키(없으면 null) — Phase 1 에서는 항상 null
     */
    List<PointEarnPolicy> loadCandidates(LocalDate on, String gradeKey, String categoryKey);
}
