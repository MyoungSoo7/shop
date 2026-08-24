package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointAccount;

import java.util.Optional;

/**
 * 포인트 계정 적재·저장 포트.
 *
 * <p>{@link #loadForUpdate} 는 비관적 락으로 계정 행을 잡는다. 포인트 사용은 "잔액 확인 후 차감"이라
 * 락 없이 하면 동시 요청이 같은 잔액을 두 번 쓸 수 있다(재고 read-modify-write 와 같은 함정).
 */
public interface PointAccountPort {

    Optional<PointAccount> load(Long userId);

    /** 잔고를 변경하는 모든 경로는 이걸로 잡는다 — 락 없는 조회로 차감하지 마라. */
    Optional<PointAccount> loadForUpdate(Long userId);

    /**
     * 계정 식별자로 락을 잡는다 — 소멸 배치는 로트에서 출발하므로 userId 를 모른 채 계정을 잠가야 한다.
     */
    Optional<PointAccount> loadByIdForUpdate(Long accountId);

    PointAccount save(PointAccount account);

    /** 계정이 없으면 열고, 있으면 그대로 돌려준다(락 없이). 적립 경로의 진입점. */
    PointAccount openIfAbsent(Long userId);
}
