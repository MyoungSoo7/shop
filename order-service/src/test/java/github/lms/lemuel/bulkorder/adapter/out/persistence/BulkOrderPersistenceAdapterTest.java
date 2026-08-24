package github.lms.lemuel.bulkorder.adapter.out.persistence;

import github.lms.lemuel.bulkorder.domain.BulkOrderCell;
import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;
import github.lms.lemuel.bulkorder.domain.BulkOrderRow;
import github.lms.lemuel.bulkorder.domain.BulkOrderStatus;
import github.lms.lemuel.bulkorder.domain.BulkOrderValidationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대량주문 초안 영속 어댑터 단위 테스트.
 *
 * <p>이 어댑터에서 조용히 깨지기 쉬운 지점은 <b>행·셀 통째 교체(replaceRows)</b>다. 재검증은 모든
 * 셀의 valid/errorMessage 가 바뀔 수 있는 연산이라, 이전 상태가 한 칸이라도 남으면 "고쳤는데도
 * 지난 오류가 뜨는" 버그가 된다. 그래서 매핑이 맞는지뿐 아니라 <b>양방향 연관(draft↔row↔cell)이
 * 실제로 이어지는지</b>까지 본다 — 끊겨 있으면 JPA 가 자식을 저장하지 않는다.
 *
 * <p>DB 없이 검증 가능한 범위만 다룬다. cascade/orphanRemoval 이 실제로 지우는지는 통합 테스트 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class BulkOrderPersistenceAdapterTest {

    private static final LocalDateTime UPLOADED_AT = LocalDateTime.of(2026, 8, 22, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 22, 9, 5);

    @Mock SpringDataBulkOrderDraftRepository draftRepository;
    @Mock SpringDataBulkOrderColumnSpecRepository specRepository;

    private BulkOrderPersistenceAdapter adapter() {
        return new BulkOrderPersistenceAdapter(draftRepository, specRepository);
    }

    /** 유효한 1행 + 무효한 1행 — valid/errorMessage 양쪽 분기를 한 초안에 담는다. */
    private BulkOrderDraft draft(Long id) {
        BulkOrderRow ok = BulkOrderRow.rehydrate(11L, 1,
                List.of(BulkOrderCell.rehydrate(101L, 0, "SKU-1", true, null),
                        BulkOrderCell.rehydrate(102L, 1, "2", true, null)),
                true, null, 900L);
        BulkOrderRow bad = BulkOrderRow.rehydrate(12L, 2,
                List.of(BulkOrderCell.rehydrate(103L, 0, "", false, "필수 항목입니다"),
                        BulkOrderCell.rehydrate(104L, 1, "abc", false, "숫자가 아닙니다")),
                false, "2행: 입력 오류", null);
        return BulkOrderDraft.rehydrate(id, 7L, "orders.csv", List.of(ok, bad),
                BulkOrderStatus.REJECTED, UPLOADED_AT, UPDATED_AT);
    }

    private BulkOrderDraftJpaEntity entityOf(BulkOrderDraft draft, Long assignedId) {
        BulkOrderDraftJpaEntity e = new BulkOrderDraftJpaEntity();
        e.setId(assignedId);
        e.setUploaderUserId(draft.getUploaderUserId());
        e.setFileName(draft.getFileName());
        e.setStatus(draft.getStatus().name());
        e.setUploadedAt(UPLOADED_AT);
        e.setUpdatedAt(UPDATED_AT);
        return e;
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("신규 초안(id 없음)은 기존 행을 조회하지 않고 새 엔티티로 만든다")
        void newDraftSkipsLookup() {
            when(draftRepository.save(any())).thenAnswer(inv -> {
                BulkOrderDraftJpaEntity arg = inv.getArgument(0);
                arg.setId(42L);
                return arg;
            });
            BulkOrderDraft domain = draft(null);

            BulkOrderDraft result = adapter().save(domain);

            verify(draftRepository, never()).findDetailById(any());
            assertThat(domain.getId()).isEqualTo(42L);   // DB 가 채운 id 가 호출자 인스턴스에도 반영된다
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getFileName()).isEqualTo("orders.csv");
            assertThat(result.getStatus()).isEqualTo(BulkOrderStatus.REJECTED);
        }

        @Test
        @DisplayName("행·셀을 통째로 갈아 끼우고 양방향 연관을 잇는다 — 끊기면 자식이 저장되지 않는다")
        void replacesRowsAndWiresBackReferences() {
            when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            adapter().save(draft(42L));

            ArgumentCaptor<BulkOrderDraftJpaEntity> captor =
                    ArgumentCaptor.forClass(BulkOrderDraftJpaEntity.class);
            verify(draftRepository).save(captor.capture());
            BulkOrderDraftJpaEntity saved = captor.getValue();

            assertThat(saved.getRows()).hasSize(2);
            BulkOrderRowJpaEntity first = saved.getRows().get(0);
            assertThat(first.getDraft()).isSameAs(saved);          // row → draft 역참조
            assertThat(first.getLineNumber()).isEqualTo(1);
            assertThat(first.getValid()).isTrue();
            assertThat(first.getErrorMessage()).isNull();
            assertThat(first.getCreatedOrderId()).isEqualTo(900L);
            assertThat(first.getCells()).hasSize(2);
            assertThat(first.getCells().get(0).getRow()).isSameAs(first);   // cell → row 역참조
            assertThat(first.getCells().get(0).getCellValue()).isEqualTo("SKU-1");

            BulkOrderRowJpaEntity second = saved.getRows().get(1);
            assertThat(second.getValid()).isFalse();
            assertThat(second.getErrorMessage()).isEqualTo("2행: 입력 오류");
            assertThat(second.getCreatedOrderId()).isNull();
            assertThat(second.getCells().get(1).getValid()).isFalse();
            assertThat(second.getCells().get(1).getErrorMessage()).isEqualTo("숫자가 아닙니다");
        }

        @Test
        @DisplayName("기존 초안은 붙어 있던 엔티티를 재사용한다 — 새 엔티티면 orphanRemoval 이 헛돈다")
        void existingDraftReusesLoadedEntity() {
            BulkOrderDraft domain = draft(42L);
            BulkOrderDraftJpaEntity managed = entityOf(domain, 42L);
            when(draftRepository.findDetailById(42L)).thenReturn(Optional.of(managed));
            when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            adapter().save(domain);

            ArgumentCaptor<BulkOrderDraftJpaEntity> captor =
                    ArgumentCaptor.forClass(BulkOrderDraftJpaEntity.class);
            verify(draftRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(managed);
        }

        @Test
        @DisplayName("id 는 있는데 행이 안 잡히면(조회 실패) 새 엔티티로 진행한다")
        void fallsBackToNewEntityWhenLookupEmpty() {
            when(draftRepository.findDetailById(42L)).thenReturn(Optional.empty());
            when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BulkOrderDraft result = adapter().save(draft(42L));

            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getRows()).hasSize(2);
        }

        @Test
        @DisplayName("저장 결과를 도메인으로 복원할 때 행·셀 값이 왕복 보존된다")
        void roundTripsRowsAndCells() {
            when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BulkOrderDraft result = adapter().save(draft(42L));

            assertThat(result.getRows()).hasSize(2);
            BulkOrderRow first = result.getRows().get(0);
            assertThat(first.getRowNumber()).isEqualTo(1);
            assertThat(first.isValid()).isTrue();
            assertThat(first.getCreatedOrderId()).isEqualTo(900L);
            assertThat(first.getCells()).hasSize(2);
            assertThat(first.getCells().get(0).getValue()).isEqualTo("SKU-1");

            BulkOrderRow second = result.getRows().get(1);
            assertThat(second.isValid()).isFalse();
            assertThat(second.getErrorMessage()).isEqualTo("2행: 입력 오류");
            assertThat(second.getCells().get(0).getErrorMessage()).isEqualTo("필수 항목입니다");
        }
    }

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("findById: 있으면 행까지 채워 복원하고, 없으면 empty")
        void findById() {
            BulkOrderDraft domain = draft(42L);
            BulkOrderDraftJpaEntity managed = entityOf(domain, 42L);
            // 저장 경로를 빌려 행·셀이 붙은 엔티티를 만든다(테스트가 매핑 코드를 두 번 쓰지 않게).
            when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(draftRepository.findDetailById(42L)).thenReturn(Optional.of(managed));
            adapter().save(domain);

            Optional<BulkOrderDraft> found = adapter().findById(42L);

            assertThat(found).isPresent();
            assertThat(found.get().getRows()).hasSize(2);
            assertThat(found.get().getStatus()).isEqualTo(BulkOrderStatus.REJECTED);
        }

        @Test
        @DisplayName("findById: 없으면 empty")
        void findById_notFound() {
            when(draftRepository.findDetailById(404L)).thenReturn(Optional.empty());

            assertThat(adapter().findById(404L)).isEmpty();
        }

        @Test
        @DisplayName("findByUploader: 목록은 행을 채우지 않는다 — 파일 하나가 수천 행일 수 있다")
        void findByUploaderReturnsSummaries() {
            BulkOrderDraftJpaEntity a = entityOf(draft(1L), 1L);
            BulkOrderDraftJpaEntity b = entityOf(draft(2L), 2L);
            b.setStatus("CONFIRMED");
            when(draftRepository.findByUploaderUserIdOrderByUploadedAtDesc(7L)).thenReturn(List.of(a, b));

            List<BulkOrderDraft> result = adapter().findByUploader(7L);

            assertThat(result).hasSize(2);
            assertThat(result).allSatisfy(d -> assertThat(d.getRows()).isEmpty());
            assertThat(result.get(0).getId()).isEqualTo(1L);
            assertThat(result.get(1).getStatus()).isEqualTo(BulkOrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("findByUploader: 초안이 없으면 빈 목록")
        void findByUploaderEmpty() {
            when(draftRepository.findByUploaderUserIdOrderByUploadedAtDesc(99L)).thenReturn(List.of());

            assertThat(adapter().findByUploader(99L)).isEmpty();
        }

        @Test
        @DisplayName("findAllOrdered: 열 스펙을 순서대로 매핑하고 required=null 은 false 로 굳힌다")
        void findAllOrderedMapsSpecs() {
            when(specRepository.findAllByOrderByColumnIndexAsc())
                    .thenReturn(List.of(spec(0, "SKU", "상품코드", true, 40, "ENUM", "SKU-1,SKU-2"),
                                        spec(1, "QTY", "수량", null, 5, "NUMERIC", null)));

            List<BulkOrderColumnSpec> result = adapter().findAllOrdered();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).columnIndex()).isEqualTo(0);
            assertThat(result.get(0).itemCode()).isEqualTo("SKU");
            assertThat(result.get(0).required()).isTrue();
            assertThat(result.get(0).maxLength()).isEqualTo(40);
            assertThat(result.get(0).validationType()).isEqualTo(BulkOrderValidationType.ENUM);
            assertThat(result.get(0).validationText()).isEqualTo("SKU-1,SKU-2");
            assertThat(result.get(1).required()).isFalse();   // null → false (모르면 필수로 만들지 않는다)
        }

        @Test
        @DisplayName("findAllOrdered: 알 수 없는 검증 타입은 NONE 으로 낮춘다 — 시드 오타가 업로드를 막지 않게")
        void findAllOrderedDegradesUnknownValidationType() {
            when(specRepository.findAllByOrderByColumnIndexAsc())
                    .thenReturn(List.of(spec(0, "SKU", "상품코드", true, 40, "존재하지-않는-타입", null)));

            List<BulkOrderColumnSpec> result = adapter().findAllOrdered();

            assertThat(result.get(0).validationType()).isEqualTo(BulkOrderValidationType.NONE);
        }

        private BulkOrderColumnSpecJpaEntity spec(int index, String itemCode, String name,
                                                  Boolean required, Integer maxLength,
                                                  String validationType, String validationText) {
            BulkOrderColumnSpecJpaEntity e = new BulkOrderColumnSpecJpaEntity();
            e.setColumnIndex(index);
            e.setItemCode(itemCode);
            e.setName(name);
            e.setRequired(required);
            e.setMaxLength(maxLength);
            e.setValidationType(validationType);
            e.setValidationText(validationText);
            return e;
        }
    }
}
