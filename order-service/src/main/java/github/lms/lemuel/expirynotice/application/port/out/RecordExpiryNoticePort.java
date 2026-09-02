package github.lms.lemuel.expirynotice.application.port.out;

import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;

/** 통보 원장 적재. */
public interface RecordExpiryNoticePort {

    /**
     * 이 (대상, 단계)를 "보냈다" 로 선점한다.
     *
     * @return 이번에 처음 선점했으면 {@code true}. 이미 있었으면 {@code false} —
     *         <b>예외가 아니라 false 다.</b> 중복은 배치가 정상적으로 매일 도는 동안 계속 발생하는
     *         평상시 상태이고, 그걸 예외로 다루면 로그가 예외로 뒤덮여 진짜 실패가 안 보인다.
     */
    boolean claim(ExpiringItem item, ExpiryNoticeStage stage);
}
