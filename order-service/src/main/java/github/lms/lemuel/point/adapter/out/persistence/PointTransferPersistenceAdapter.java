package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.out.PointTransferPort;
import github.lms.lemuel.point.domain.PointTransfer;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** {@link PointTransferPort} 의 JPA 구현. */
@Component
@Transactional
public class PointTransferPersistenceAdapter implements PointTransferPort {

    /** 선물 번호의 날짜 부분은 <b>한국 날짜</b>다 — 번호를 읽는 사람이 한국에서 본다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PointTransferRepository transfers;

    public PointTransferPersistenceAdapter(PointTransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public PointTransfer save(PointTransfer transfer) {
        if (transfer.getId() != null) {
            throw new PointInvariantViolationException(
                    "이미 저장된 선물을 다시 저장하려 했습니다: id=" + transfer.getId());
        }
        PointTransferJpaEntity saved = transfers.save(PointTransferJpaEntity.from(transfer));
        transfer.assignId(saved.getId());
        return transfer;
    }

    @Override
    public String nextTransferNo() {
        long sequence = transfers.nextTransferSequence();
        return "PT" + LocalDate.now(SEOUL).format(DATE_PART) + "-" + String.format("%08d", sequence);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PointTransfer> findBySenderAndRequestId(Long senderUserId, String requestId) {
        if (senderUserId == null || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return transfers.findBySenderUserIdAndRequestId(senderUserId, requestId)
                .map(PointTransferJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointTransfer> findByParticipant(Long userId, int limit) {
        return transfers.findByParticipant(userId, PageRequest.of(0, limit)).stream()
                .map(PointTransferJpaEntity::toDomain)
                .toList();
    }
}
