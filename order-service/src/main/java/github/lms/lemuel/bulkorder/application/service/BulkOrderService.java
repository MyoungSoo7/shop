package github.lms.lemuel.bulkorder.application.service;

import github.lms.lemuel.bulkorder.application.port.in.BulkOrderUseCase;
import github.lms.lemuel.bulkorder.application.port.out.BulkOrderColumnSpecPort;
import github.lms.lemuel.bulkorder.application.port.out.BulkOrderDraftPort;
import github.lms.lemuel.bulkorder.application.port.out.PlaceBulkOrderLinePort;
import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;
import github.lms.lemuel.bulkorder.domain.BulkOrderRow;
import github.lms.lemuel.bulkorder.domain.exception.BulkOrderNotFoundException;
import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 대량주문 초안 업로드·검증·확정.
 *
 * <p><b>확정 실패의 격리</b>가 이 서비스의 어려운 부분이다. 행 하나가 실패했다고 전체를 롤백하면
 * 앞서 성공한 수백 건의 주문·재고 차감이 되돌아가는데, 그 사이 다른 주문이 같은 재고를 가져갔다면
 * 재시도는 같은 결과를 내지 못한다. 그래서 <b>행 단위로 독립 커밋</b>({@link BulkOrderLineCommitter})
 * 하고, 실패한 행만 사유를 남겨 다시 확정할 수 있게 둔다.
 *
 * <p>이미 주문이 나간 행은 {@code createdOrderId} 로 걸러진다 — 재확정이 중복 주문이 되지 않는
 * 유일한 근거다.
 */
@Service
public class BulkOrderService implements BulkOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(BulkOrderService.class);

    private static final String ITEM_PRODUCT_ID = "product_id";
    private static final String ITEM_QUANTITY = "quantity";
    private static final String ITEM_RECIPIENT_NAME = "recipient_name";
    private static final String ITEM_RECIPIENT_PHONE = "recipient_phone";
    private static final String ITEM_POSTAL_CODE = "postal_code";
    private static final String ITEM_ADDRESS1 = "address1";
    private static final String ITEM_ADDRESS2 = "address2";
    private static final String ITEM_MEMO = "delivery_memo";

    private final BulkOrderDraftPort draftPort;
    private final BulkOrderColumnSpecPort columnSpecPort;
    private final BulkOrderLineCommitter lineCommitter;
    private final Clock clock;

    public BulkOrderService(BulkOrderDraftPort draftPort,
                            BulkOrderColumnSpecPort columnSpecPort,
                            BulkOrderLineCommitter lineCommitter,
                            Clock clock) {
        this.draftPort = draftPort;
        this.columnSpecPort = columnSpecPort;
        this.lineCommitter = lineCommitter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkOrderDraft uploadAndValidate(Long uploaderUserId, String fileName,
                                            List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new InvalidBulkOrderFileException("데이터 행이 없습니다. 헤더만 있는 파일인지 확인해 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        List<BulkOrderRow> parsed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            parsed.add(BulkOrderRow.uploaded(i + 1, rows.get(i)));
        }
        BulkOrderDraft draft = BulkOrderDraft.upload(uploaderUserId, fileName, parsed, now);
        draft.validate(columnSpecPort.findAllOrdered(), now);

        BulkOrderDraft saved = draftPort.save(draft);
        log.info("대량주문 초안 업로드: draftId={}, 행={}, 통과={}, status={}",
                saved.getId(), saved.getRows().size(), saved.validRowCount(), saved.getStatus());
        return saved;
    }

    @Override
    @Transactional
    public BulkOrderDraft revalidate(Long draftId, Long requesterUserId) {
        BulkOrderDraft draft = loadOwned(draftId, requesterUserId);
        draft.validate(columnSpecPort.findAllOrdered(), LocalDateTime.now(clock));
        return draftPort.save(draft);
    }

    /**
     * 확정 — 행별 독립 커밋.
     *
     * <p>이 메서드 자체는 트랜잭션을 열지 않는다. 열면 행별 {@code REQUIRES_NEW} 가 바깥 트랜잭션과
     * 얽혀 "행은 커밋됐는데 초안 상태는 롤백" 같은 어긋남이 생긴다. 초안 저장은 마지막에 한 번.
     */
    @Override
    public ConfirmResult confirm(Long draftId, Long requesterUserId) {
        BulkOrderDraft draft = loadOwned(draftId, requesterUserId);
        draft.requireConfirmable();

        List<BulkOrderColumnSpec> specs = columnSpecPort.findAllOrdered();
        List<ConfirmResult.Line> lines = new ArrayList<>();
        int created = 0;
        int failed = 0;

        for (BulkOrderRow row : draft.pendingRows()) {
            try {
                Long orderId = lineCommitter.commit(draft.getUploaderUserId(), toLine(row, specs));
                row.markOrderCreated(orderId);
                created++;
                lines.add(new ConfirmResult.Line(row.getRowNumber(), orderId, null));
            } catch (RuntimeException e) {
                // 재고 부족·상품 없음 등 — 이 행만 실패로 남기고 나머지는 계속 진행한다.
                failed++;
                row.markConfirmFailed(reasonOf(e));
                lines.add(new ConfirmResult.Line(row.getRowNumber(), null, reasonOf(e)));
                log.warn("대량주문 확정 실패 행: draftId={}, row={}, reason={}",
                        draftId, row.getRowNumber(), e.getMessage());
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (failed == 0) {
            draft.markConfirmed(now);
        } else {
            // 되돌리지 않는다 — 성공한 주문은 이미 나갔다. 실패 행만 고쳐 다시 확정한다.
            draft.markPartiallyConfirmed(now);
        }
        BulkOrderDraft saved = draftPort.save(draft);

        log.info("대량주문 확정: draftId={}, 생성={}, 실패={}, status={}",
                draftId, created, failed, saved.getStatus());
        return new ConfirmResult(draftId, saved.getStatus().name(), created, failed, lines);
    }

    @Override
    @Transactional
    public void discard(Long draftId, Long requesterUserId) {
        BulkOrderDraft draft = loadOwned(draftId, requesterUserId);
        draft.discard(LocalDateTime.now(clock));
        draftPort.save(draft);
    }

    @Override
    @Transactional(readOnly = true)
    public BulkOrderDraft get(Long draftId, Long requesterUserId) {
        return loadOwned(draftId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BulkOrderDraft> listMine(Long requesterUserId) {
        return draftPort.findByUploader(requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BulkOrderColumnSpec> columnSpecs() {
        return columnSpecPort.findAllOrdered();
    }

    private BulkOrderDraft loadOwned(Long draftId, Long requesterUserId) {
        BulkOrderDraft draft = draftPort.findById(draftId)
                .orElseThrow(() -> new BulkOrderNotFoundException("대량주문 초안이 없습니다: id=" + draftId));
        if (requesterUserId == null || !draft.ownedBy(requesterUserId)) {
            throw new AccessDeniedException("본인이 올린 대량주문 초안만 다룰 수 있습니다");
        }
        return draft;
    }

    private PlaceBulkOrderLinePort.Line toLine(BulkOrderRow row, List<BulkOrderColumnSpec> specs) {
        return new PlaceBulkOrderLinePort.Line(
                Long.valueOf(requiredValue(row, specs, ITEM_PRODUCT_ID)),
                Integer.parseInt(requiredValue(row, specs, ITEM_QUANTITY)),
                row.value(specs, ITEM_RECIPIENT_NAME),
                row.value(specs, ITEM_RECIPIENT_PHONE),
                row.value(specs, ITEM_POSTAL_CODE),
                row.value(specs, ITEM_ADDRESS1),
                row.value(specs, ITEM_ADDRESS2),
                row.value(specs, ITEM_MEMO));
    }

    /**
     * 주문을 만들 수 없는 필수 셀을 사유가 읽히는 예외로 바꾼다.
     *
     * <p>검증을 통과한 행이라도 여기서 빈 값이 나올 수 있다: 업로드·검증과 확정 사이에 운영자가
     * 열 정의를 지우면 값을 꺼낼 업무 코드가 사라져 {@code value()} 가 null 을 돌려준다. 그대로
     * 두면 {@code NullPointerException} 이 행 사유로 남는데, 운영자에게는 조사할 단서가 0 이다.
     */
    private static String requiredValue(BulkOrderRow row, List<BulkOrderColumnSpec> specs, String itemCode) {
        String value = row.value(specs, itemCode);
        if (value == null || value.isBlank()) {
            throw new InvalidBulkOrderFileException(
                    "필수 값이 비어 있습니다: itemCode=" + itemCode + ", row=" + row.getRowNumber()
                            + " (열 정의가 지워졌는지 확인해 주세요)");
        }
        return value.trim();
    }

    /** 운영자에게 보일 사유. 메시지가 없는 예외는 타입 이름이라도 남긴다(빈 칸이면 조사할 단서가 0). */
    private static String reasonOf(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
