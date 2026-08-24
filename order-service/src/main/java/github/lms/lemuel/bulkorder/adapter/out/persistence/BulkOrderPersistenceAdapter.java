package github.lms.lemuel.bulkorder.adapter.out.persistence;

import github.lms.lemuel.bulkorder.application.port.out.BulkOrderColumnSpecPort;
import github.lms.lemuel.bulkorder.application.port.out.BulkOrderDraftPort;
import github.lms.lemuel.bulkorder.domain.BulkOrderCell;
import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;
import github.lms.lemuel.bulkorder.domain.BulkOrderRow;
import github.lms.lemuel.bulkorder.domain.BulkOrderStatus;
import github.lms.lemuel.bulkorder.domain.BulkOrderValidationType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 대량주문 초안 영속 어댑터.
 *
 * <p>저장은 <b>행·셀을 통째로 갈아 끼운다</b>(replaceRows). 재검증은 모든 셀의 valid/errorMessage 가
 * 바뀔 수 있는 연산이라 부분 갱신을 시도하면 "이번에 통과한 셀의 지난 오류 메시지가 남는" 종류의
 * 버그가 난다. 초안은 파일 하나가 통째로 한 덩어리다.
 */
@Component
public class BulkOrderPersistenceAdapter implements BulkOrderDraftPort, BulkOrderColumnSpecPort {

    private final SpringDataBulkOrderDraftRepository draftRepository;
    private final SpringDataBulkOrderColumnSpecRepository specRepository;

    public BulkOrderPersistenceAdapter(SpringDataBulkOrderDraftRepository draftRepository,
                                       SpringDataBulkOrderColumnSpecRepository specRepository) {
        this.draftRepository = draftRepository;
        this.specRepository = specRepository;
    }

    @Override
    public BulkOrderDraft save(BulkOrderDraft draft) {
        BulkOrderDraftJpaEntity entity = draft.getId() == null
                ? new BulkOrderDraftJpaEntity()
                : draftRepository.findDetailById(draft.getId()).orElseGet(BulkOrderDraftJpaEntity::new);

        entity.setId(draft.getId());
        entity.setUploaderUserId(draft.getUploaderUserId());
        entity.setFileName(draft.getFileName());
        entity.setStatus(draft.getStatus().name());
        entity.setUploadedAt(draft.getUploadedAt());
        entity.setUpdatedAt(draft.getUpdatedAt());
        entity.replaceRows(toRowEntities(draft.getRows()));

        BulkOrderDraftJpaEntity saved = draftRepository.save(entity);
        draft.assignId(saved.getId());
        return toDomain(saved);
    }

    @Override
    public Optional<BulkOrderDraft> findById(Long id) {
        return draftRepository.findDetailById(id).map(this::toDomain);
    }

    @Override
    public List<BulkOrderDraft> findByUploader(Long uploaderUserId) {
        return draftRepository.findByUploaderUserIdOrderByUploadedAtDesc(uploaderUserId).stream()
                .map(this::toSummaryDomain)
                .toList();
    }

    @Override
    public List<BulkOrderColumnSpec> findAllOrdered() {
        return specRepository.findAllByOrderByColumnIndexAsc().stream()
                .map(spec -> new BulkOrderColumnSpec(
                        spec.getColumnIndex(),
                        spec.getItemCode(),
                        spec.getName(),
                        Boolean.TRUE.equals(spec.getRequired()),
                        spec.getMaxLength(),
                        BulkOrderValidationType.fromStorage(spec.getValidationType()),
                        spec.getValidationText()))
                .toList();
    }

    private List<BulkOrderRowJpaEntity> toRowEntities(List<BulkOrderRow> rows) {
        List<BulkOrderRowJpaEntity> entities = new ArrayList<>();
        for (BulkOrderRow row : rows) {
            BulkOrderRowJpaEntity entity = new BulkOrderRowJpaEntity();
            entity.setLineNumber(row.getRowNumber());
            entity.setValid(row.isValid());
            entity.setErrorMessage(row.getErrorMessage());
            entity.setCreatedOrderId(row.getCreatedOrderId());

            List<BulkOrderCellJpaEntity> cells = new ArrayList<>();
            for (BulkOrderCell cell : row.getCells()) {
                BulkOrderCellJpaEntity cellEntity = new BulkOrderCellJpaEntity();
                cellEntity.setColumnIndex(cell.getColumnIndex());
                cellEntity.setCellValue(cell.getValue());
                cellEntity.setValid(cell.isValid());
                cellEntity.setErrorMessage(cell.getErrorMessage());
                cells.add(cellEntity);
            }
            entity.replaceCells(cells);
            entities.add(entity);
        }
        return entities;
    }

    private BulkOrderDraft toDomain(BulkOrderDraftJpaEntity entity) {
        List<BulkOrderRow> rows = new ArrayList<>();
        for (BulkOrderRowJpaEntity rowEntity : entity.getRows()) {
            List<BulkOrderCell> cells = rowEntity.getCells().stream()
                    .map(c -> BulkOrderCell.rehydrate(c.getId(), c.getColumnIndex(), c.getCellValue(),
                            Boolean.TRUE.equals(c.getValid()), c.getErrorMessage()))
                    .toList();
            rows.add(BulkOrderRow.rehydrate(rowEntity.getId(), rowEntity.getLineNumber(), cells,
                    Boolean.TRUE.equals(rowEntity.getValid()), rowEntity.getErrorMessage(),
                    rowEntity.getCreatedOrderId()));
        }
        return BulkOrderDraft.rehydrate(entity.getId(), entity.getUploaderUserId(), entity.getFileName(),
                rows, BulkOrderStatus.valueOf(entity.getStatus()),
                entity.getUploadedAt(), entity.getUpdatedAt());
    }

    /**
     * 목록용 — 행을 채우지 않는다. {@code BulkOrderDraft} 는 빈 행 목록을 거부하므로 rehydrate 로
     * 복원한다(생성 팩토리와 달리 rehydrate 는 저장된 사실을 그대로 재구성하는 경로다).
     */
    private BulkOrderDraft toSummaryDomain(BulkOrderDraftJpaEntity entity) {
        return BulkOrderDraft.rehydrate(entity.getId(), entity.getUploaderUserId(), entity.getFileName(),
                List.of(), BulkOrderStatus.valueOf(entity.getStatus()),
                entity.getUploadedAt(), entity.getUpdatedAt());
    }
}
