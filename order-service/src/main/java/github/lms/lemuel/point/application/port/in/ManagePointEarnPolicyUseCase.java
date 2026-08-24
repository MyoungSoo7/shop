package github.lms.lemuel.point.application.port.in;

import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnRounding;
import github.lms.lemuel.point.domain.PointEarnScope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 적립률 정책 편집 (ADR 0032 규약).
 *
 * <p><b>행을 고치지 않는다.</b> 변경은 언제나 <b>종료 + 신규 등록</b> 2단계다 — 이력이 곧 이 표라,
 * 과거 값을 덮으면 "그때 왜 그 요율로 적립됐나"를 설명할 수 없게 된다.
 *
 * <p>DB 의 {@code ex_pep_no_overlap} 배제 제약이 같은 {@code (scope, scopeKey)} 안의 기간 겹침을
 * 막는다. 그래서 요율을 바꾸려면 <b>현재 정책의 종료일을 먼저 지정해</b> 자리를 비운 뒤에야
 * 새 정책을 넣을 수 있다. 이 순서는 화면이 아니라 제약이 강제한다.
 */
public interface ManagePointEarnPolicyUseCase {

    /** 신규 정책 등록. 소급(오늘보다 이른 발효)은 거절한다. */
    PointEarnPolicy register(RegisterPolicyCommand command, LocalDate today);

    /** 기존 정책 종료일 지정. 대상이 없으면 비어 있는 결과를 준다(404 는 어댑터가 판단). */
    Optional<PointEarnPolicy> close(ClosePolicyCommand command, LocalDate today);

    /**
     * @param effectiveTo null 이면 무기한 — 다음 정책을 넣으려면 그때 종료일을 지정해야 한다
     * @param reason      이 요율의 근거. 감사 추적용 필수 입력
     */
    record RegisterPolicyCommand(PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                                 int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                                 String reason, String createdBy,
                                 int roundingUnit, PointEarnRounding rounding) {

        /** 단위·방식을 지정하지 않은 요청은 1 원 단위 버림으로 착지한다(이 기능의 기존 동작). */
        public RegisterPolicyCommand {
            if (roundingUnit <= 0) {
                roundingUnit = 1;
            }
            if (rounding == null) {
                rounding = PointEarnRounding.DOWN;
            }
        }

        public RegisterPolicyCommand(PointEarnScope scope, String scopeKey, BigDecimal earnRate,
                                     int validityDays, LocalDate effectiveFrom, LocalDate effectiveTo,
                                     String reason, String createdBy) {
            this(scope, scopeKey, earnRate, validityDays, effectiveFrom, effectiveTo,
                    reason, createdBy, 1, PointEarnRounding.DOWN);
        }
    }

    /** @param effectiveTo 이 날짜부터 적용하지 않는다(반열림 [from, to)). 오늘 이상이어야 한다 */
    record ClosePolicyCommand(Long policyId, LocalDate effectiveTo, String actor) {
    }
}
