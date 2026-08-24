package github.lms.lemuel.payment.application.port.in;

import java.time.LocalDateTime;

/**
 * 미입금 결제 자동 만료 — 가상계좌·무통장 입금 기한이 지난 결제를 만료시키고 그 주문을 취소한다.
 *
 * <p>입금이 오지 않은 결제는 READY 로, 주문은 CREATED 로 남아 주문 생성 시 차감한 재고를 계속 붙잡는다.
 * 이 유스케이스가 그 잔류분을 주기적으로 정리한다.
 */
public interface ExpirePendingPaymentsUseCase {

    /**
     * 기한이 지난 미입금 결제를 만료 처리한다.
     *
     * @param now     판정 기준 시각(테스트·재실행 시 명시 주입)
     * @param dryRun  true 면 아무 것도 바꾸지 않고 "만료될 건수"만 산출한다
     */
    ExpiryReport expireDue(LocalDateTime now, boolean dryRun);

    /**
     * 배치 1회 결과.
     *
     * @param scanned 조회된 후보 수
     * @param expired 만료된(=dryRun 이면 만료될) 건수
     * @param skipped 정책상 만료 대상이 아니어서 건너뛴 건수
     * @param failed  처리 중 실패한 건수 — 0 이 아니면 운영 확인 대상이다
     */
    record ExpiryReport(int scanned, int expired, int skipped, int failed, boolean dryRun) {
    }
}
