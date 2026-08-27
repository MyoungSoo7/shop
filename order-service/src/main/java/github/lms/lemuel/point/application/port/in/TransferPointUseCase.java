package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 회원 간 포인트 선물 유스케이스.
 *
 * <p>받는 이를 <b>userId 가 아니라 이메일 + 이름</b>으로 받는다. 화면이 남의 userId 를 알 수
 * 있는 경로가 없어야 하고(있으면 그 자체가 열거 수단이다), 이메일 하나만으로 받으면 오타 한 글자가
 * 곧 남에게 돈을 보내는 일이 되기 때문이다. 두 값을 모두 맞춰야 하므로 <b>보내는 이가 받는 이를
 * 실제로 알고 있다</b>는 것이 전제된다.
 *
 * <p>{@code requestId} 는 화면이 만든 멱등 키다. 같은 값으로 두 번 보내면 두 번째는 첫 결과를
 * 그대로 돌려준다 — 결제 버튼 두 번 누르기가 두 배 송금이 되지 않게 하는 유일한 장치다.
 */
public interface TransferPointUseCase {

    /**
     * @param requestId        화면이 만드는 멱등 키. 사람이 보는 선물 번호는 이것과 별개로
     *                         DB 시퀀스가 발급한다 — 화면이 준 값을 번호로 쓰면 남이 내 번호를
     *                         고를 수 있고, 번호 형식도 화면에 달리게 된다
     * @param recipientEmail   받는 이 이메일
     * @param recipientName    받는 이 이름. 이메일 오타로 남에게 보내는 사고를 막는 두 번째 자물쇠
     * @param message          받는 이에게 남기는 한 줄(선택)
     */
    record TransferPointCommand(Long senderUserId, String requestId, String recipientEmail,
                                String recipientName, BigDecimal amount, String message) {
    }

    /**
     * @param alreadyProcessed 같은 {@code requestId} 로 이미 처리된 건을 돌려준 경우 true.
     *                         화면이 "또 보냈나?"를 묻지 않고 그대로 성공으로 그릴 수 있게 한다
     */
    record TransferPointResult(String transferNo, String recipientMaskedEmail, String recipientName,
                               BigDecimal amount, BigDecimal remainingBalance,
                               OffsetDateTime transferredAt, boolean alreadyProcessed) {
    }

    /**
     * 선물 이력 한 줄. 보낸 것과 받은 것을 한 표에 섞으므로 {@code outgoing} 으로 방향을 준다.
     *
     * @param counterpartName        상대방 이름. 이메일은 주지 않는다 — 이력 조회가 남의
     *                               연락처를 알아내는 경로가 되면 안 된다
     */
    record PointTransferHistoryEntry(String transferNo, boolean outgoing, String counterpartName,
                                     BigDecimal amount, String message, OffsetDateTime transferredAt) {
    }

    TransferPointResult transfer(TransferPointCommand command);

    /** 내가 보낸 것과 받은 것을 최신순으로. */
    List<PointTransferHistoryEntry> history(Long userId, int limit);
}
