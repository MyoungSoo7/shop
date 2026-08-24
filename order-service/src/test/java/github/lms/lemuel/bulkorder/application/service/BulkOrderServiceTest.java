package github.lms.lemuel.bulkorder.application.service;

import github.lms.lemuel.bulkorder.application.port.in.BulkOrderUseCase;
import github.lms.lemuel.bulkorder.application.port.out.BulkOrderColumnSpecPort;
import github.lms.lemuel.bulkorder.application.port.out.BulkOrderDraftPort;
import github.lms.lemuel.bulkorder.application.port.out.PlaceBulkOrderLinePort;
import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;
import github.lms.lemuel.bulkorder.domain.BulkOrderStatus;
import github.lms.lemuel.bulkorder.domain.BulkOrderValidationType;
import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderFileException;
import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대량주문 업로드·확정 흐름.
 *
 * <p>지키는 것은 <b>확정 실패의 격리</b>다: 행 하나가 실패해도 앞서 성공한 주문은 살아남고,
 * 이미 주문이 나간 행은 재확정에서 건너뛴다.
 */
@DisplayName("BulkOrderService — 업로드 · 확정 · 소유권")
class BulkOrderServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 14, 0);
    private static final long UPLOADER = 9L;

    private static final List<BulkOrderColumnSpec> SPECS = List.of(
            new BulkOrderColumnSpec(0, "product_id", "상품번호", true, 18,
                    BulkOrderValidationType.NUMERIC, null),
            new BulkOrderColumnSpec(1, "quantity", "수량", true, 6,
                    BulkOrderValidationType.NUMERIC, null),
            new BulkOrderColumnSpec(2, "recipient_name", "수령인", true, 50,
                    BulkOrderValidationType.ALNUM, null),
            new BulkOrderColumnSpec(3, "recipient_phone", "연락처", true, 20,
                    BulkOrderValidationType.PHONE, null),
            new BulkOrderColumnSpec(4, "postal_code", "우편번호", true, 6,
                    BulkOrderValidationType.NUMERIC, null),
            new BulkOrderColumnSpec(5, "address1", "주소", true, 200,
                    BulkOrderValidationType.NONE, null),
            new BulkOrderColumnSpec(6, "address2", "상세주소", false, 200,
                    BulkOrderValidationType.NONE, null),
            new BulkOrderColumnSpec(7, "delivery_memo", "배송메모", false, 100,
                    BulkOrderValidationType.NONE, null));

    private BulkOrderDraftPort draftPort;
    private BulkOrderColumnSpecPort columnSpecPort;
    private PlaceBulkOrderLinePort placeLinePort;
    private BulkOrderService service;
    private final AtomicLong idSequence = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        draftPort = mock(BulkOrderDraftPort.class);
        columnSpecPort = mock(BulkOrderColumnSpecPort.class);
        placeLinePort = mock(PlaceBulkOrderLinePort.class);
        when(columnSpecPort.findAllOrdered()).thenReturn(SPECS);
        when(draftPort.save(any())).thenAnswer(inv -> {
            BulkOrderDraft draft = inv.getArgument(0);
            if (draft.getId() == null) {
                draft.assignId(idSequence.getAndIncrement());
            }
            return draft;
        });
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        service = new BulkOrderService(draftPort, columnSpecPort,
                new BulkOrderLineCommitter(placeLinePort), fixed);
    }

    private static List<String> goodRow(String productId) {
        return List.of(productId, "2", "홍길동", "010-1234-5678", "06236",
                "서울시 강남구 테헤란로 1", "3층", "부재 시 경비실");
    }

    @Test
    @DisplayName("업로드는 검증까지 하되 주문은 만들지 않는다 — 이 분리가 기능의 요점")
    void uploadValidatesButPlacesNoOrder() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"), goodRow("101")));

        assertThat(draft.getStatus()).isEqualTo(BulkOrderStatus.VALIDATED);
        verify(placeLinePort, never()).place(anyLong(), any());
    }

    @Test
    @DisplayName("오류 행이 있으면 REJECTED — 확정이 잠긴다")
    void uploadWithBadRowIsRejected() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"),
                        List.of("abc", "2", "홍길동", "010-1234-5678", "06236", "주소", "", "")));

        assertThat(draft.getStatus()).isEqualTo(BulkOrderStatus.REJECTED);
    }

    @Test
    @DisplayName("데이터 행이 없는 파일은 초안이 되지 않는다")
    void emptyUploadRejected() {
        assertThatThrownBy(() -> service.uploadAndValidate(UPLOADER, "empty.csv", List.of()))
                .isInstanceOf(InvalidBulkOrderFileException.class);
    }

    @Test
    @DisplayName("확정: 전 행이 주문으로 나가고 초안은 종단이 된다")
    void confirmAllRows() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"), goodRow("101")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(placeLinePort.place(anyLong(), any())).thenReturn(555L, 556L);

        BulkOrderUseCase.ConfirmResult result = service.confirm(draft.getId(), UPLOADER);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("행 하나가 실패해도 나머지 주문은 살아남는다 — 전체 롤백은 재시도를 불가능하게 만든다")
    void partialFailureKeepsSucceededOrders() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"), goodRow("101")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(placeLinePort.place(anyLong(), any()))
                .thenReturn(555L)
                .thenThrow(new IllegalStateException("재고가 부족합니다"));

        BulkOrderUseCase.ConfirmResult result = service.confirm(draft.getId(), UPLOADER);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("REJECTED"); // 고쳐서 다시 확정할 수 있다
        assertThat(result.lines()).anySatisfy(line ->
                assertThat(line.error()).contains("재고"));
    }

    @Test
    @DisplayName("재확정은 이미 주문이 나간 행을 건너뛴다 — 재시도가 중복 주문이 되지 않는다")
    void reconfirmSkipsPlacedRows() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"), goodRow("101")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(placeLinePort.place(anyLong(), any()))
                .thenReturn(555L)
                .thenThrow(new IllegalStateException("재고가 부족합니다"));
        service.confirm(draft.getId(), UPLOADER);

        // 실패 행을 고쳐 재검증한 뒤 다시 확정.
        // doReturn 인 이유: when(mock.method()) 은 스텁 과정에서 실제 호출을 한 번 하는데,
        // 앞 스텁이 throw 로 끝나 있어 스텁 자체가 그 예외로 터진다.
        org.mockito.Mockito.doReturn(556L).when(placeLinePort).place(anyLong(), any());
        service.revalidate(draft.getId(), UPLOADER);
        BulkOrderUseCase.ConfirmResult second = service.confirm(draft.getId(), UPLOADER);

        assertThat(second.created()).isEqualTo(1); // 2건이 아니다 — 1건은 이미 나갔다
        assertThat(second.status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("검증을 통과하지 않은 초안은 확정할 수 없다")
    void cannotConfirmRejectedDraft() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(List.of("abc", "2", "홍길동", "010-1234-5678", "06236", "주소", "", "")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.confirm(draft.getId(), UPLOADER))
                .isInstanceOf(InvalidBulkOrderStateException.class);
        verify(placeLinePort, never()).place(anyLong(), any());
    }

    @Test
    @DisplayName("남의 초안은 확정도 조회도 못 한다 — 수백 명의 개인정보가 들어 있는 파일이다")
    void ownershipEnforced() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv", List.of(goodRow("100")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.confirm(draft.getId(), 999L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get(draft.getId(), 999L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("폐기한 초안은 확정할 수 없다")
    void discardedCannotBeConfirmed() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv", List.of(goodRow("100")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.discard(draft.getId(), UPLOADER);

        assertThatThrownBy(() -> service.confirm(draft.getId(), UPLOADER))
                .isInstanceOf(InvalidBulkOrderStateException.class);
    }

    @Test
    @DisplayName("확정은 배송지까지 함께 넘긴다 — 배송지 없는 주문이 남으면 손으로 채워야 한다")
    void confirmCarriesShippingAddress() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv", List.of(goodRow("100")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(placeLinePort.place(anyLong(), any())).thenReturn(555L);

        service.confirm(draft.getId(), UPLOADER);

        verify(placeLinePort).place(UPLOADER, new PlaceBulkOrderLinePort.Line(
                100L, 2, "홍길동", "010-1234-5678", "06236",
                "서울시 강남구 테헤란로 1", "3층", "부재 시 경비실"));
    }

    @Test
    @DisplayName("확정 시점에 필수 열 정의가 사라졌으면 그 행만 사유를 남기고 실패한다 — 사유가 NPE 면 단서가 0")
    void confirmWithMissingRequiredSpecLeavesReadableReason() {
        BulkOrderDraft draft = service.uploadAndValidate(UPLOADER, "bulk.csv",
                List.of(goodRow("100"), goodRow("101")));
        when(draftPort.findById(draft.getId())).thenReturn(Optional.of(draft));

        // 업로드·검증을 마친 뒤 운영자가 상품번호 열 정의를 지운 상황. 행에는 값이 남아 있지만
        // 업무 코드로 값을 꺼낼 근거가 사라져 value() 가 null 을 돌려준다.
        when(columnSpecPort.findAllOrdered()).thenReturn(
                SPECS.stream().filter(spec -> !"product_id".equals(spec.itemCode())).toList());

        BulkOrderUseCase.ConfirmResult result = service.confirm(draft.getId(), UPLOADER);

        assertThat(result.created()).isZero();
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.lines()).allSatisfy(line ->
                assertThat(line.error()).contains("product_id"));
        verify(placeLinePort, never()).place(anyLong(), any());
    }
}
