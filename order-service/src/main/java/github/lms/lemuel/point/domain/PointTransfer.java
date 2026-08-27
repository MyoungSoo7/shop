package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.PointTransferRejectedException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 회원 간 포인트 선물 1건 — <b>이 행이 존재한다는 것이 곧 양쪽 원장이 모두 기입됐다는 뜻</b>이다.
 *
 * <p>여기에 상태 칼럼을 두지 않은 것이 이 설계의 핵심이다. 레거시(ssg_front)는 선물 행을
 * {@code st='0'} 으로 먼저 넣고, 포인트 이동에 성공하면 {@code st='1'} 로 올리는 2단계였다.
 * 그 사이에서 끊기면 <b>포인트는 옮겨졌는데 st 는 '0' 인 행</b>이 남고, 반대로 포인트 이동이
 * 실패해도 행 자체는 남는다. 어느 쪽이든 나중에 이 표를 읽는 사람은 무엇이 진짜 일어난 일인지
 * 알 수 없다. 여기서는 행 삽입과 양쪽 원장 기입이 <b>한 트랜잭션</b>이므로 중간 상태가
 * 표현될 수 없다 — 없는 칼럼은 어긋날 수 없다.
 *
 * <p><b>식별자가 둘</b>인 것도 의도다.
 * <ul>
 *   <li>{@code transferNo} — 사람이 보는 번호이자 양쪽 원장 엔트리의 {@code referenceId}.
 *       DB 시퀀스가 발급한다. 레거시는 {@code SELECT MAX(idx)+1} 로 잡아, 동시에 두 건이 들어오면
 *       같은 번호를 읽었다.
 *   <li>{@code requestId} — 화면이 만드는 멱등 키. {@code (senderUserId, requestId)} 가 DB UNIQUE 라
 *       버튼 두 번 누르기가 두 배 송금이 되지 않는다. 보내는 이별로 유일하므로, 남이 만든 값과
 *       겹쳐 남의 선물을 되돌려 받는 일도 없다.
 * </ul>
 *
 * <p>불변식은 생성 시점에 강제하고 DB CHECK 가 같은 것을 다시 막는다. 도메인을 우회하는
 * 경로(배치·수기 SQL)가 생겨도 표가 스스로를 지킨다.
 */
public class PointTransfer {

    /** 남기는 메시지의 최대 길이 — DB 칼럼과 같다. */
    public static final int MAX_MESSAGE_LENGTH = 200;

    /** 멱등 키의 최대 길이 — DB 칼럼과 같다. */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    private Long id;
    private final String transferNo;
    private final String requestId;
    private final Long senderUserId;
    private final Long receiverUserId;
    private final BigDecimal amount;
    private final String message;
    private final OffsetDateTime createdAt;

    private PointTransfer(Long id, String transferNo, String requestId, Long senderUserId,
                          Long receiverUserId, BigDecimal amount, String message,
                          OffsetDateTime createdAt) {
        this.id = id;
        this.transferNo = transferNo;
        this.requestId = requestId;
        this.senderUserId = senderUserId;
        this.receiverUserId = receiverUserId;
        this.amount = amount;
        this.message = message;
        this.createdAt = createdAt;
    }

    /**
     * 선물 1건을 만든다.
     *
     * @param message 받는 이에게 남기는 한 줄. 비면 null 로 정규화한다 — 빈 문자열과 없음을
     *                구분해 봐야 화면이 둘 다 "메시지 없음"으로 그린다
     */
    public static PointTransfer create(String transferNo, String requestId, Long senderUserId,
                                       Long receiverUserId, BigDecimal amount, String message,
                                       OffsetDateTime createdAt) {
        if (transferNo == null || transferNo.isBlank()) {
            throw PointTransferRejectedException.malformed("선물 번호가 비었습니다");
        }
        String key = requestId == null ? "" : requestId.strip();
        if (key.isEmpty()) {
            throw PointTransferRejectedException.malformed("요청 식별자가 비었습니다");
        }
        if (key.length() > MAX_REQUEST_ID_LENGTH) {
            throw PointTransferRejectedException.malformed(
                    "요청 식별자는 " + MAX_REQUEST_ID_LENGTH + "자를 넘을 수 없습니다");
        }
        if (senderUserId == null || receiverUserId == null || createdAt == null) {
            throw PointTransferRejectedException.malformed("선물에 필요한 식별 정보가 비었습니다");
        }
        if (senderUserId.equals(receiverUserId)) {
            throw PointTransferRejectedException.self();
        }
        BigDecimal value = PointAmounts.requirePoint(amount, "transfer");
        String trimmed = message == null || message.isBlank() ? null : message.strip();
        if (trimmed != null && trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw PointTransferRejectedException.malformed(
                    "메시지는 " + MAX_MESSAGE_LENGTH + "자를 넘을 수 없습니다");
        }
        return new PointTransfer(null, transferNo, key, senderUserId, receiverUserId,
                value, trimmed, createdAt);
    }

    public static PointTransfer rehydrate(Long id, String transferNo, String requestId,
                                          Long senderUserId, Long receiverUserId, BigDecimal amount,
                                          String message, OffsetDateTime createdAt) {
        return new PointTransfer(id, transferNo, requestId, senderUserId, receiverUserId,
                PointAmounts.normalize(amount, "rehydrate"), message, createdAt);
    }

    /**
     * 이 선물이 <b>이 사용자에게</b> 어느 방향인가. 목록 화면은 보낸 것과 받은 것을 한 표에
     * 섞어 보여 주므로, 방향을 행마다 계산하지 않고 여기서 한 번에 답한다.
     */
    public boolean isOutgoingFor(Long userId) {
        return senderUserId.equals(userId);
    }

    /** 이 선물에서 {@code userId} 의 상대방. 이력 화면이 이름을 채울 때 쓴다. */
    public Long counterpartOf(Long userId) {
        return isOutgoingFor(userId) ? receiverUserId : senderUserId;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw PointTransferRejectedException.malformed("이미 ID 가 할당된 선물입니다: " + this.id);
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public String getTransferNo() { return transferNo; }
    public String getRequestId() { return requestId; }
    public Long getSenderUserId() { return senderUserId; }
    public Long getReceiverUserId() { return receiverUserId; }
    public BigDecimal getAmount() { return amount; }
    public String getMessage() { return message; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
