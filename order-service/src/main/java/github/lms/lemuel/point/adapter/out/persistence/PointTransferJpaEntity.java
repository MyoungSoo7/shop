package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointTransfer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code point_transfers} 매핑.
 *
 * <p>{@code apply} 가 없다. 선물은 만들어진 뒤 바뀌지 않는 기록이라 갱신 경로 자체를 두지 않는다 —
 * 있으면 언젠가 쓰인다.
 */
@Entity
@Table(name = "point_transfers")
public class PointTransferJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_no", nullable = false, length = 40, updatable = false)
    private String transferNo;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private Long senderUserId;

    @Column(name = "receiver_user_id", nullable = false, updatable = false)
    private Long receiverUserId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "message", length = 200, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PointTransferJpaEntity() {
    }

    static PointTransferJpaEntity from(PointTransfer transfer) {
        PointTransferJpaEntity entity = new PointTransferJpaEntity();
        entity.id = transfer.getId();
        entity.transferNo = transfer.getTransferNo();
        entity.requestId = transfer.getRequestId();
        entity.senderUserId = transfer.getSenderUserId();
        entity.receiverUserId = transfer.getReceiverUserId();
        entity.amount = transfer.getAmount();
        entity.message = transfer.getMessage();
        entity.createdAt = transfer.getCreatedAt();
        return entity;
    }

    PointTransfer toDomain() {
        return PointTransfer.rehydrate(id, transferNo, requestId, senderUserId, receiverUserId,
                amount, message, createdAt);
    }

    Long getId() {
        return id;
    }
}
