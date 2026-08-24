package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.out.ManagePointEarnPolicyPort;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.exception.PointPolicyOverlapException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 적립률 정책 쓰기 어댑터.
 *
 * <p>기간 겹침은 여기서 보지 않는다 — DB 의 {@code ex_pep_no_overlap}(GiST 배제 제약)이 정본이다.
 * 애플리케이션에서 미리 확인해도 그 사이에 다른 요청이 끼어들 수 있어 진짜 방어가 되지 못하고,
 * 규칙만 두 곳으로 갈린다. 위반은 {@code DataIntegrityViolationException} 으로 올라오고
 * 공통 핸들러가 409 로 매핑한다 — 운영자에게는 "먼저 현재 정책을 종료하라"는 신호다.
 */
@Repository
@RequiredArgsConstructor
public class PointEarnPolicyPersistenceAdapter implements ManagePointEarnPolicyPort {

    private final PointEarnPolicyRepository repository;

    @Override
    public PointEarnPolicy save(PointEarnPolicy policy) {
        try {
            // 제약 위반을 이 트랜잭션 안에서 받아 내려면 flush 가 필요하다. save 만 하면 커밋
            // 시점에 터져 컨트롤러 밖에서 500 이 되고, 운영자에게는 원인이 보이지 않는다.
            return repository.saveAndFlush(PointEarnPolicyJpaEntity.from(policy)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new PointPolicyOverlapException(
                    "같은 범위(" + policy.getScope() + ":" + policy.getScopeKey() + ")에 기간이 겹치는"
                            + " 적립률 정책이 이미 있습니다. 현재 정책의 종료일을 먼저 지정하세요"
                            + " (ADR 0032 — 정책은 고치지 않고 끊고 다시 만든다).");
        }
    }

    @Override
    public Optional<PointEarnPolicy> findById(Long policyId) {
        return repository.findById(policyId).map(PointEarnPolicyJpaEntity::toDomain);
    }

    @Override
    public Optional<PointEarnPolicy> close(Long policyId, LocalDate effectiveTo) {
        return repository.findById(policyId).map(entity -> {
            entity.closeAt(effectiveTo);
            // 여기서 반드시 flush 한다. Hibernate 의 액션 큐는 같은 flush 안에서
            // <b>INSERT 를 UPDATE 보다 먼저</b> 실행하므로, 종료를 미뤄 두면
            // "현재 정책 종료 → 신규 등록"을 한 트랜잭션에서 할 때 신규 INSERT 가
            // 아직 열려 있는 구간과 겹쳐 배제 제약에 걸린다(실측으로 확인).
            return repository.saveAndFlush(entity).toDomain();
        });
    }
}
