package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase;
import github.lms.lemuel.point.application.port.out.ManagePointEarnPolicyPort;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 적립률 정책 편집 — 행을 고치지 않고 <b>종료 + 신규 등록</b>으로만 바꾼다(ADR 0032).
 *
 * <p>서비스가 지는 판단은 <b>시간 방향</b> 하나다. 이미 지나간 구간은 건드릴 수 없다:
 * 적립된 로트는 그 시점 요율의 스냅샷이라 재계산되지 않으므로, 과거 요율을 바꾸면
 * 정책 표와 원장이 서로 다른 말을 하게 된다. 요율 변경은 언제나 앞을 향한다.
 *
 * <p>기간 겹침은 서비스가 보지 않는다 — DB 의 {@code ex_pep_no_overlap} 배제 제약이 정본이다.
 * 애플리케이션에서 한 번 더 확인해 봐야 그 사이에 다른 요청이 끼어들 수 있어 진짜 방어가 되지
 * 못하고, 규칙만 두 곳으로 갈린다. 겹치면 제약 위반이 그대로 올라온다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ManagePointEarnPolicyService implements ManagePointEarnPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManagePointEarnPolicyService.class);

    private final ManagePointEarnPolicyPort port;

    @Override
    public PointEarnPolicy register(RegisterPolicyCommand command, LocalDate today) {
        // 도메인이 먼저 형식을 본다(요율 0~1, 유효기간 양수, 종료일 > 시작일, 사유 필수).
        PointEarnPolicy policy = PointEarnPolicy.of(command.scope(), command.scopeKey(),
                command.earnRate(), command.validityDays(), command.effectiveFrom(),
                command.effectiveTo(), command.reason(), command.createdBy(),
                command.roundingUnit(), command.rounding());

        if (command.effectiveFrom().isBefore(today)) {
            throw new InvalidPointStateException(
                    "소급 발효는 등록할 수 없습니다: " + command.effectiveFrom() + " (오늘 " + today + ")."
                            + " 이미 적립된 로트는 그 시점 요율의 스냅샷이라 재계산되지 않으므로,"
                            + " 과거 구간 요율만 바꾸면 정책 표와 원장이 어긋납니다.",
                    "OPEN", "policy-register");
        }

        PointEarnPolicy saved = port.save(policy);
        log.info("적립률 정책 등록: scope={}:{}, rate={}, from={}, to={}, by={}",
                command.scope(), command.scopeKey(), command.earnRate(),
                command.effectiveFrom(), command.effectiveTo(), command.createdBy());
        return saved;
    }

    @Override
    public Optional<PointEarnPolicy> close(ClosePolicyCommand command, LocalDate today) {
        LocalDate cutoff = command.effectiveTo();
        if (cutoff == null) {
            throw new InvalidPointStateException(
                    "종료일이 필요합니다 — 정책을 무기한으로 되돌리는 조작은 없습니다",
                    "OPEN", "policy-close");
        }
        if (cutoff.isBefore(today)) {
            throw new InvalidPointStateException(
                    "과거 날짜로 종료할 수 없습니다: " + cutoff + " (오늘 " + today + ")."
                            + " 그 구간의 적립은 이미 일어났습니다.",
                    "OPEN", "policy-close");
        }

        Optional<PointEarnPolicy> target = port.findById(command.policyId());
        if (target.isEmpty()) {
            return Optional.empty();
        }
        LocalDate start = target.get().getEffectiveFrom();
        if (!cutoff.isAfter(start)) {
            throw new InvalidPointStateException(
                    "종료일(" + cutoff + ")은 시작일(" + start + ")보다 뒤여야 합니다 — 빈 구간이 됩니다",
                    "OPEN", "policy-close");
        }

        log.info("적립률 정책 종료: id={}, to={}, by={}",
                command.policyId(), cutoff, command.actor());
        return port.close(command.policyId(), cutoff);
    }
}
