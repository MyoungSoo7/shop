package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;

import java.time.LocalDateTime;

/**
 * 잠긴 계정의 잠금 해제.
 *
 * <p><b>왜 필요한가</b>: 잠금은 {@code LoginSecurity} 가 기한부(기본 30 분)로 건다. 지금까지
 * 그것을 푸는 길은 <b>기다리거나 비밀번호를 바꾸는 것</b> 둘뿐이었다. 둘 다 계정 주인이 해야
 * 하는 일이라, 잠긴 사람이 로그인해서 비밀번호를 바꿀 수 없는 상황에서는 운영자가 할 수 있는
 * 조치가 없었다. 무차별 대입이 아니라 오타로 잠긴 사람에게는 30 분이 그대로 장애다.
 *
 * <p>영구 잠금이 아니라 기한부라 "언젠가는 풀린다"가 맞지만, 그 사이 운영자가 개입할 수단이
 * 아예 없는 것과 있는 것은 다르다.
 */
public interface UnlockAccountUseCase {

    /**
     * 계정 잠금을 해제한다.
     *
     * @param userId  대상 계정
     * @param reason  해제 사유(필수)
     * @param actorId 조작자 — JWT 주체에서 얻은 값이어야 한다
     */
    UnlockResult unlock(Long userId, String reason, Long actorId);

    /**
     * 해제 결과.
     *
     * <p><b>해제 직전 상태를 함께 돌려준다</b>. 감사에 "풀었다"만 남으면 5 회 실패로 잠긴 계정을
     * 푼 것인지 애초에 잠기지도 않은 계정에 대고 누른 것인지 구분할 수 없다. 전자는 조사할
     * 사건이고 후자는 아무 일도 아니다.
     *
     * @param wasLocked          조작 시점에 실제로 잠겨 있었는지
     * @param previousLockedUntil 해제 전 잠금 만료 시각
     * @param previousFailedAttempts 해제 전 연속 실패 횟수
     */
    record UnlockResult(
            User user,
            boolean wasLocked,
            LocalDateTime previousLockedUntil,
            int previousFailedAttempts) {
    }
}
