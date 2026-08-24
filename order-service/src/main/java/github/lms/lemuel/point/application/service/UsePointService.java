package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.UsePointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 포인트 사용(차감) — 결제의 POINT 텐더가 부르는 경로.
 *
 * <p>순서가 중요하다:
 * <ol>
 *   <li><b>멱등 단축 반환</b> — 같은 참조로 이미 기록된 사용이면 아무것도 하지 않는다.
 *   <li><b>비관적 락</b>으로 계정을 잡는다. 잔액 확인과 차감 사이에 다른 요청이 끼어들면
 *       같은 포인트가 두 번 쓰인다(재고 read-modify-write 와 같은 함정).
 *   <li>계정 잔고를 먼저 줄여 상태 규칙(정지 계정)과 잔액 부족을 판정한 뒤 로트를 건드린다.
 *   <li>로트 소비·원장 기록·이벤트는 {@link PointSpendRecorder} 가 맡는다 — 입금 대기 결제의
 *       확정({@link HoldPointService})과 같은 절차를 써야 소비 순서·엔트리 규약이 갈라지지 않는다.
 * </ol>
 *
 * <p>잔액 부족({@link InsufficientPointException})은 비즈니스 정상 결과다 — 결제 경로에서는
 * "포인트로는 결제할 수 없다"는 답이며, 재시도 대상이 아니다.
 */
@Service
@Transactional
public class UsePointService implements UsePointUseCase {

    private static final Logger log = LoggerFactory.getLogger(UsePointService.class);

    private final PointAccountPort accountPort;
    private final PointSpendRecorder recorder;

    public UsePointService(PointAccountPort accountPort, PointSpendRecorder recorder) {
        this.accountPort = accountPort;
        this.recorder = recorder;
    }

    @Override
    public UsePointResult use(UsePointCommand command) {
        PointAccount account = accountPort.loadForUpdate(command.userId())
                .orElseThrow(() -> new InsufficientPointException(
                        "포인트 계정이 없습니다: userId=" + command.userId(),
                        command.amount(), BigDecimal.ZERO));

        // 같은 tender 를 두 번 차감하지 않는다. L3 UNIQUE 가 최후 방어선이지만,
        // 정상 경로에서 먼저 걸러야 재시도가 예외로 시끄러워지지 않는다.
        if (recorder.alreadyRecorded(account.getId(), command.referenceType(), command.referenceId())) {
            log.info("포인트 사용 멱등 단축 반환: userId={}, ref={}:{}",
                    command.userId(), command.referenceType(), command.referenceId());
            return new UsePointResult(null, command.amount(), account.getAvailable());
        }

        account.use(command.amount());
        PointEntry appended = recorder.record(account, command.amount(),
                command.referenceType(), command.referenceId(), command.actor());

        log.info("포인트 사용: userId={}, amount={}, 잔액={}",
                command.userId(), command.amount(), account.getAvailable());
        return new UsePointResult(appended.getId(), command.amount(), account.getAvailable());
    }

    /** 잔액 조회 — 결제 화면이 "포인트로 얼마까지 낼 수 있나"를 물을 때. */
    @Transactional(readOnly = true)
    public BigDecimal availableBalance(Long userId) {
        Optional<PointAccount> account = accountPort.load(userId);
        return account.map(PointAccount::getAvailable).orElse(BigDecimal.ZERO);
    }
}
