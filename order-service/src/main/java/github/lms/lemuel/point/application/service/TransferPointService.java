package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.TransferPointUseCase;
import github.lms.lemuel.point.application.port.out.LoadTransferRecipientPort;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PointTransferPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotSelector;
import github.lms.lemuel.point.domain.PointTransfer;
import github.lms.lemuel.point.domain.exception.PointTransferRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 회원 간 포인트 선물 — 보내는 이의 잔고에서 빼고 받는 이에게 새 로트로 얹는다.
 *
 * <p><b>이벤트를 하나도 발행하지 않는다.</b> 이것이 이 서비스가 {@link PointSpendRecorder} 를
 * 재사용하지 않고 원장을 직접 쓰는 이유다. {@code point.used} 는 회계상 <i>차) 포인트부채 /
 * 대) 현금</i>, {@code point.granted} 는 <i>차) 판촉비 / 대) 포인트부채</i>다. 선물에 이 둘을 태우면
 * 나가지 않은 현금이 나간 것으로, 쓰지 않은 판촉비가 쓴 것으로 잡힌다. 선물은 <b>회사 밖으로
 * 아무것도 움직이지 않는다</b> — 같은 부채 계정 안에서 주인만 바뀔 뿐이라 총 포인트부채가
 * 그대로다. 분개할 것이 없으면 이벤트도 없다.
 *
 * <p>레거시(ssg_front)의 선물 기능이 가지고 있던 결함 넷을 각각 구조로 막는다.
 *
 * <ol>
 *   <li><b>채번 경쟁</b> — {@code MAX(idx)+1} 대신 DB 시퀀스({@link PointTransferPort#nextTransferNo}).
 *   <li><b>잔액 판정이 둘</b> — 화면과 서버가 각자 계산하던 것을, {@link PointAccount#use} 하나로
 *       모았다. 가용 잔고는 이미 잠긴 몫을 뺀 값이라 여기 통과하면 그것이 곧 진실이다.
 *   <li><b>상태 칼럼과 실제 이동의 어긋남</b> — {@link PointTransfer} 에 상태가 없다. 행 삽입과
 *       양쪽 원장 기입이 이 트랜잭션 하나에 들어 있어 중간 상태가 존재할 수 없다.
 *   <li><b>자기 자신에게 보내기</b> — 도메인 팩토리와 DB CHECK 가 각각 막는다. 레거시에서는
 *       이것이 유효기간을 초기화하는 수단이었다.
 * </ol>
 *
 * <p><b>유효기간 승계</b>: 받는 이의 로트는 보내는 이가 <b>실제로 소비한 로트들 중 가장 이른
 * 만료일</b>을 물려받는다. 승계하지 않으면 만료가 임박한 포인트를 남에게(또는 부계정에) 보내
 * 기한을 되살릴 수 있다 — 회사가 이미 인식한 부채의 소멸 시점을 사용자가 마음대로 미루는 셈이다.
 *
 * <p><b>락 순서</b>: 두 계정을 언제나 userId 오름차순으로 잠근다. A→B 와 B→A 가 동시에 들어올 때
 * 각자 자기 계정부터 잠그면 서로를 기다리는 교착이 된다. 순서를 값으로 고정하면 교착이 성립하지
 * 않는다 — 먼저 잠근 쪽이 반드시 끝난다.
 */
@Service
@Transactional
public class TransferPointService implements TransferPointUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferPointService.class);

    /** 양쪽 원장 엔트리의 참조 유형. {@code referenceId} 는 선물 번호다. */
    static final String REFERENCE_TYPE = "POINT_TRANSFER";

    /** 이력 조회 한 번에 돌려주는 최대 건수 — 화면이 큰 수를 넣어도 여기서 잘린다. */
    static final int MAX_HISTORY_LIMIT = 100;

    private final PointTransferPort transferPort;
    private final LoadTransferRecipientPort recipientPort;
    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;

    public TransferPointService(PointTransferPort transferPort,
                                LoadTransferRecipientPort recipientPort,
                                PointAccountPort accountPort,
                                PointLotPort lotPort,
                                PointEntryPort entryPort) {
        this.transferPort = transferPort;
        this.recipientPort = recipientPort;
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
    }

    @Override
    public TransferPointResult transfer(TransferPointCommand command) {
        if (command.senderUserId() == null) {
            throw PointTransferRejectedException.malformed("보내는 분을 알 수 없습니다");
        }

        Optional<PointTransfer> already =
                transferPort.findBySenderAndRequestId(command.senderUserId(), normalizedKey(command));
        if (already.isPresent()) {
            log.info("포인트 선물 멱등 단축 반환: senderUserId={}, transferNo={}",
                    command.senderUserId(), already.get().getTransferNo());
            return replay(already.get(), command.recipientEmail());
        }

        LoadTransferRecipientPort.Recipient recipient = recipientPort
                .findActiveRecipient(command.recipientEmail(), command.recipientName())
                .orElseThrow(PointTransferRejectedException::recipientUnknown);
        if (recipient.userId().equals(command.senderUserId())) {
            throw PointTransferRejectedException.self();
        }

        OffsetDateTime now = OffsetDateTime.now();
        // 도메인이 금액·메시지·자기송금을 먼저 거른다. 채번은 그 뒤다 — 거절될 요청에 번호를
        // 태우면 시퀀스에 구멍이 남고, 그 구멍이 "사라진 선물"처럼 보인다.
        String transferNo = transferPort.nextTransferNo();
        PointTransfer transfer = PointTransfer.create(transferNo, normalizedKey(command),
                command.senderUserId(), recipient.userId(), command.amount(), command.message(), now);

        // 두 계정을 잠그기 전에 연다 — openIfAbsent 는 락을 잡지 않으므로 순서 규칙 밖이다.
        accountPort.openIfAbsent(command.senderUserId());
        accountPort.openIfAbsent(recipient.userId());
        Locked locked = lockInUserIdOrder(command.senderUserId(), recipient.userId());

        BigDecimal amount = transfer.getAmount();
        OffsetDateTime inheritedExpiry = debitSender(locked.sender(), transfer, amount, now);
        creditReceiver(locked.receiver(), transfer, amount, inheritedExpiry, now);

        PointTransfer saved = transferPort.save(transfer);
        log.info("포인트 선물: transferNo={}, {} -> {}, amount={}, 잔액={}",
                saved.getTransferNo(), command.senderUserId(), recipient.userId(),
                amount, locked.sender().getAvailable());

        return new TransferPointResult(saved.getTransferNo(), maskEmail(command.recipientEmail()),
                recipient.name(), amount, locked.sender().getAvailable(), saved.getCreatedAt(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointTransferHistoryEntry> history(Long userId, int limit) {
        if (userId == null) {
            throw PointTransferRejectedException.malformed("조회할 사용자를 알 수 없습니다");
        }
        int capped = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        // 이름은 상대방 수만큼만 조회한다 — 같은 사람과 여러 번 주고받은 이력이 흔해서,
        // 행마다 부르면 같은 질문을 반복한다.
        List<PointTransfer> transfers = transferPort.findByParticipant(userId, capped);
        Map<Long, String> names = new HashMap<>();
        List<PointTransferHistoryEntry> entries = new ArrayList<>(transfers.size());
        for (PointTransfer transfer : transfers) {
            Long counterpart = transfer.counterpartOf(userId);
            String name = names.computeIfAbsent(counterpart,
                    id -> recipientPort.findNameById(id).orElse("(탈퇴한 회원)"));
            entries.add(new PointTransferHistoryEntry(transfer.getTransferNo(),
                    transfer.isOutgoingFor(userId), name, transfer.getAmount(),
                    transfer.getMessage(), transfer.getCreatedAt()));
        }
        return entries;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내부
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 보내는 이의 차감 — 잔고를 줄이고 만료 임박 로트부터 소비한 뒤 USE 엔트리를 남긴다.
     *
     * @return 소비한 로트들 중 <b>아직 오지 않은</b> 가장 이른 만료일. 없으면 null
     */
    private OffsetDateTime debitSender(PointAccount sender, PointTransfer transfer,
                                       BigDecimal amount, OffsetDateTime now) {
        sender.use(amount);

        int sequence = entryPort.nextSequence(sender.getId(), PointEntryType.USE,
                REFERENCE_TYPE, transfer.getTransferNo());
        List<PointLot> lots = lotPort.loadConsumable(sender.getId());
        List<PointLotConsumption> allocations = PointLotSelector.consume(lots, amount);

        PointEntry entry = PointEntry.use(sender.getId(), amount, REFERENCE_TYPE,
                transfer.getTransferNo(), sequence, allocations, actorOf(transfer.getSenderUserId()));

        accountPort.save(sender);
        lotPort.saveAll(lots);
        entryPort.append(entry);

        return earliestFutureExpiry(lots, allocations, now);
    }

    /**
     * 받는 이의 적립 — 새 로트를 발급해 식별자를 얻은 뒤 그 식별자로 GRANT 엔트리를 남긴다.
     * 순서를 뒤집으면 "어느 로트로 들어왔는지 모르는 엔트리"가 생겨 환불·소멸이 되돌아갈 곳을 잃는다.
     */
    private void creditReceiver(PointAccount receiver, PointTransfer transfer, BigDecimal amount,
                                OffsetDateTime inheritedExpiry, OffsetDateTime now) {
        receiver.grant(amount);

        PointLot lot = PointLot.issue(receiver.getId(), PointLotOrigin.TRANSFER_IN, amount,
                now, inheritedExpiry, REFERENCE_TYPE, transfer.getTransferNo());
        PointLot savedLot = lotPort.save(lot);

        int sequence = entryPort.nextSequence(receiver.getId(), PointEntryType.GRANT,
                REFERENCE_TYPE, transfer.getTransferNo());
        PointEntry entry = PointEntry.grant(receiver.getId(), amount, REFERENCE_TYPE,
                transfer.getTransferNo(), sequence,
                List.of(new PointLotConsumption(savedLot.getId(), amount)),
                actorOf(transfer.getSenderUserId()),
                "회원 선물 수신 (" + transfer.getTransferNo() + ")");

        accountPort.save(receiver);
        entryPort.append(entry);
    }

    /**
     * 소비한 로트들의 만료일 중 가장 이른 것. <b>이미 지난 만료일은 세지 않는다</b> —
     * 새 로트의 만료일은 발급 시각보다 뒤여야 하므로(도메인 규칙) 지난 값을 물려줄 수 없다.
     *
     * <p>지난 만료일을 가진 로트가 소비됐다는 것은 소멸 배치가 아직 쓸어 가지 못했다는 뜻이다.
     * 그건 이 기능이 만든 문제가 아니라 소멸 주기의 문제이고, 그 지연을 받는 이의 포인트를
     * 거절하는 것으로 갚게 할 이유가 없다. 다만 조용히 넘기지는 않는다.
     */
    private OffsetDateTime earliestFutureExpiry(List<PointLot> lots,
                                                List<PointLotConsumption> allocations,
                                                OffsetDateTime now) {
        Map<Long, PointLot> byId = new HashMap<>();
        for (PointLot lot : lots) {
            byId.put(lot.getId(), lot);
        }
        OffsetDateTime earliest = null;
        boolean sawStaleExpiry = false;
        for (PointLotConsumption allocation : allocations) {
            PointLot lot = byId.get(allocation.lotId());
            if (lot == null || lot.getExpiresAt() == null) {
                continue;
            }
            OffsetDateTime expiresAt = lot.getExpiresAt();
            if (!expiresAt.isAfter(now)) {
                sawStaleExpiry = true;
                continue;
            }
            if (earliest == null || expiresAt.isBefore(earliest)) {
                earliest = expiresAt;
            }
        }
        if (sawStaleExpiry && earliest == null) {
            log.warn("만료일이 지난 로트가 선물로 소비됐다 — 소멸 배치 지연을 확인할 것. 새 로트는 무기한으로 발급된다");
        }
        return earliest;
    }

    /** 같은 요청 식별자를 다시 받았을 때 첫 결과를 그대로 재현한다. 잔액만 지금 값을 읽는다. */
    private TransferPointResult replay(PointTransfer transfer, String recipientEmail) {
        BigDecimal balance = accountPort.load(transfer.getSenderUserId())
                .map(PointAccount::getAvailable)
                .orElse(BigDecimal.ZERO);
        String name = recipientPort.findNameById(transfer.getReceiverUserId()).orElse("(탈퇴한 회원)");
        return new TransferPointResult(transfer.getTransferNo(), maskEmail(recipientEmail), name,
                transfer.getAmount(), balance, transfer.getCreatedAt(), true);
    }

    /**
     * 두 계정을 userId 오름차순으로 잠근다 — 잠그는 순서만 값에 따라 정하고, 돌려줄 때는 다시
     * 보내는 이/받는 이로 갈라 준다. 호출부가 순서를 신경 쓰지 않아도 되게 하는 것이 요점이다.
     */
    private Locked lockInUserIdOrder(Long senderUserId, Long receiverUserId) {
        boolean senderFirst = senderUserId < receiverUserId;
        Long firstId = senderFirst ? senderUserId : receiverUserId;
        Long secondId = senderFirst ? receiverUserId : senderUserId;
        PointAccount first = requireAccount(firstId);
        PointAccount second = requireAccount(secondId);
        return senderFirst ? new Locked(first, second) : new Locked(second, first);
    }

    private PointAccount requireAccount(Long userId) {
        return accountPort.loadForUpdate(userId)
                .orElseThrow(() -> PointTransferRejectedException.malformed(
                        "포인트 계정을 찾을 수 없습니다: userId=" + userId));
    }

    private static String normalizedKey(TransferPointCommand command) {
        return command.requestId() == null ? "" : command.requestId().strip();
    }

    private static String actorOf(Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 확인용 이메일 마스킹. 보내는 이가 직접 입력한 주소이므로 숨기는 것이 목적은 아니고,
     * 응답이 로그나 화면 캡처로 남았을 때 남의 주소가 통째로 따라다니지 않게 하는 것이다.
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        String trimmed = email.strip();
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "*" + domain;
        }
        return local.substring(0, 2) + "*".repeat(local.length() - 2) + domain;
    }

    private record Locked(PointAccount sender, PointAccount receiver) {
    }
}
