package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.HoldPointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointHoldPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 포인트 선점 — 입금 대기 결제가 잠그고, 확정하고, 푸는 세 경로.
 *
 * <p>모든 경로가 <b>비관적 락</b>으로 계정을 잡고 시작한다. 선점·확정·해제는 전부
 * "현재 잔고를 보고 옮기는" 연산이라, 락 없이 하면 두 요청이 같은 잔고를 두 번 옮긴다.
 *
 * <p><b>확정과 해제가 서로를 막는다</b>. 입금 확인과 미입금 만료 배치는 독립적으로 도착하므로
 * 경합한다. 어느 쪽이든 먼저 도착한 쪽이 선점을 종단 상태로 옮기고, 늦은 쪽은
 * {@link PointHold} 의 전이 가드에 막힌다:
 *
 * <ul>
 *   <li>만료가 먼저면 뒤늦은 확정이 거부된다 — 그러지 않으면 이미 가용으로 돌아간 포인트를
 *       한 번 더 쓴다.
 *   <li>입금이 먼저면 뒤늦은 해제가 거부된다 — 그러지 않으면 쓴 포인트가 되살아나 없는 잔고가 생긴다.
 * </ul>
 *
 * <p><b>없는 선점에 대한 처리가 방향에 따라 다르다.</b> 확정은 예외를 던지고(조용히 넘기면 고객
 * 포인트를 받지 않은 채 주문이 확정된다), 해제는 경고만 남긴다(여기서 막으면 미입금 만료 배치가
 * 함께 멈춰 재고 회수까지 밀린다). 손해의 방향이 반대라 처리도 반대다.
 */
@Service
@Transactional
public class HoldPointService implements HoldPointUseCase {

    private static final Logger log = LoggerFactory.getLogger(HoldPointService.class);

    private final PointAccountPort accountPort;
    private final PointHoldPort holdPort;
    private final PointSpendRecorder recorder;

    public HoldPointService(PointAccountPort accountPort, PointHoldPort holdPort,
                            PointSpendRecorder recorder) {
        this.accountPort = accountPort;
        this.holdPort = holdPort;
        this.recorder = recorder;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        PointAccount account = accountPort.loadForUpdate(command.userId())
                .orElseThrow(() -> new InsufficientPointException(
                        "포인트 계정이 없습니다: userId=" + command.userId(),
                        command.amount(), BigDecimal.ZERO));

        // 결제 재시도가 선점을 두 벌 만들면 같은 잔고를 두 번 잠근다. DB UNIQUE 가 최후 방어선이지만,
        // 정상 경로에서 먼저 걸러야 재시도가 예외로 시끄러워지지 않는다.
        Optional<PointHold> existing = holdPort.findByReference(
                command.referenceType(), command.referenceId());
        if (existing.isPresent()) {
            PointHold hold = existing.get();
            log.info("포인트 선점 멱등 단축 반환: userId={}, ref={}:{}, status={}",
                    command.userId(), command.referenceType(), command.referenceId(), hold.getStatus());
            return new HoldResult(hold.getId(), hold.getAmount(), account.getAvailable());
        }

        account.hold(command.amount());
        accountPort.save(account);
        PointHold saved = holdPort.save(PointHold.place(account.getId(), command.amount(),
                command.referenceType(), command.referenceId(), OffsetDateTime.now()));

        log.info("포인트 선점: userId={}, amount={}, 가용={}, 잠금={}",
                command.userId(), command.amount(), account.getAvailable(), account.getLocked());
        return new HoldResult(saved.getId(), saved.getAmount(), account.getAvailable());
    }

    @Override
    public void capture(String referenceType, String referenceId, String actor) {
        Long accountId = holdPort.findAccountIdByReference(referenceType, referenceId)
                .orElseThrow(() -> new PointInvariantViolationException(
                        "확정할 포인트 선점이 없습니다: ref=" + referenceType + ":" + referenceId
                                + " — 선점 없이 확정하면 받지 않은 포인트를 받은 셈이 된다"));

        // 잠금을 먼저 얻고 그 안에서 선점을 처음 적재한다 — 순서가 뒤바뀌면 판정이 낡는다(아래 참조).
        PointAccount account = lockAccount(accountId);
        PointHold hold = loadAuthoritative(referenceType, referenceId);

        if (hold.getStatus() == github.lms.lemuel.point.domain.PointHoldStatus.CAPTURED) {
            log.info("포인트 선점 확정 멱등 단축 반환: ref={}:{}", referenceType, referenceId);
            return;
        }
        if (!hold.isActive()) {
            // 만료·해제가 먼저 이겼다. 여기서 확정하면 이미 가용으로 돌아간 포인트를 한 번 더 쓴다.
            throw new InvalidPointStateException(
                    "이미 해소된 선점은 확정할 수 없습니다: " + hold.getStatus(),
                    hold.getStatus().name(), "capture");
        }

        account.captureHold(hold.getAmount());
        hold.capture(OffsetDateTime.now());
        holdPort.save(hold);
        // 잔고를 줄인 뒤 로트 소비·원장 기록은 즉시 결제와 같은 절차를 탄다.
        recorder.record(account, hold.getAmount(), referenceType, referenceId, actor);

        log.info("포인트 선점 확정: ref={}:{}, amount={}, 총액={}",
                referenceType, referenceId, hold.getAmount(), account.getTotal());
    }

    @Override
    public void release(String referenceType, String referenceId, boolean expired) {
        Optional<Long> accountId = holdPort.findAccountIdByReference(referenceType, referenceId);
        if (accountId.isEmpty()) {
            // 포인트를 쓰지 않은 결제이거나 애초에 선점하지 않은 건. 막으면 만료 배치가 함께 멈춘다.
            log.warn("해제할 포인트 선점이 없습니다 — 건너뜁니다: ref={}:{}", referenceType, referenceId);
            return;
        }

        PointAccount account = lockAccount(accountId.get());
        PointHold hold = loadAuthoritative(referenceType, referenceId);

        if (hold.getStatus() == github.lms.lemuel.point.domain.PointHoldStatus.CAPTURED) {
            // 입금이 먼저 이겼다. 여기서 풀면 이미 쓴 포인트가 되살아나 없는 잔고가 생긴다.
            throw new InvalidPointStateException(
                    "이미 확정된 선점은 해제할 수 없습니다", hold.getStatus().name(), "release");
        }
        if (!hold.isActive()) {
            log.info("포인트 선점 해제 멱등 단축 반환: ref={}:{}, status={}",
                    referenceType, referenceId, hold.getStatus());
            return;
        }

        account.releaseHold(hold.getAmount());
        if (expired) {
            hold.expire(OffsetDateTime.now());
        } else {
            hold.release(OffsetDateTime.now());
        }
        accountPort.save(account);
        holdPort.save(hold);

        log.info("포인트 선점 해제: ref={}:{}, amount={}, 사유={}, 가용={}",
                referenceType, referenceId, hold.getAmount(),
                hold.getStatus(), account.getAvailable());
    }

    /**
     * 선점이 가리키는 계정을 <b>선점 레코드 기준</b>으로 잠근다.
     *
     * <p>호출자(입금 확인·만료 배치)는 tender 만 쥐고 있고 어느 계정 것인지 모른다. 호출자가 넘긴
     * 계정을 믿으면 남의 계정 잠금을 푸는 통로가 된다.
     */
    private PointAccount lockAccount(Long accountId) {
        return accountPort.loadByIdForUpdate(accountId)
                .orElseThrow(() -> new PointInvariantViolationException(
                        "선점이 가리키는 계정이 없습니다: accountId=" + accountId));
    }

    /**
     * 계정 잠금을 얻은 <b>뒤에</b> 선점을 처음 적재한다 — 이 순서가 경합 방어의 핵심이다.
     *
     * <p>잠금 전에 한 번 읽어 두면 두 가지가 겹쳐 무너진다. 첫째, 읽은 시점과 잠금 시점 사이에
     * 다른 트랜잭션이 이 선점을 해소할 수 있다(check-then-act). 둘째, 그래서 잠금 뒤에 다시
     * 조회해도 하이버네이트가 <b>영속성 컨텍스트에 남은 낡은 인스턴스</b>를 돌려주므로 재조회가
     * 소용없다. 그래서 잠금 전에는 계정 id 만 스칼라로 묻는다
     * ({@code findAccountIdByReference}).
     *
     * <p>동시 해제 12건이 전부 잔고를 되돌리려 해 불변식 위반으로 터지던 것을
     * {@code PointHoldConcurrencyIT} 가 잡아 드러난 순서다.
     */
    private PointHold loadAuthoritative(String referenceType, String referenceId) {
        return holdPort.findByReference(referenceType, referenceId)
                .orElseThrow(() -> new PointInvariantViolationException(
                        "선점이 사라졌습니다: ref=" + referenceType + ":" + referenceId));
    }
}
