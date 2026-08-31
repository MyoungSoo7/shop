package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.application.port.out.PublishSellerEventPort;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import github.lms.lemuel.seller.domain.SubmissionType;
import github.lms.lemuel.seller.domain.exception.IllegalSubmissionStateException;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 운영자 심사 — 여기서 고정하는 것은 <b>무엇을 발행하고 무엇을 발행하지 않는가</b>이다.
 *
 * <p>승인은 저장과 발행이 한 트랜잭션이어야 하고, 반려는 발행하지 않는다. 후자를 테스트로
 * 못 박아 두는 이유는, 나중에 "반려도 이벤트를 내자" 가 아무 근거 없이 들어오기 쉬워서다 —
 * 아무도 구독하지 않는 토픽은 계약처럼 보이지만 계약이 아니다.
 */
class SubmissionReviewServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-01T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime SUBMITTED_AT =
            OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private ProductSubmissionPort submissionPort;
    private PublishSellerEventPort publishPort;
    private SubmissionReviewService service;

    @BeforeEach
    void setUp() {
        submissionPort = mock(ProductSubmissionPort.class);
        publishPort = mock(PublishSellerEventPort.class);
        service = new SubmissionReviewService(submissionPort, publishPort, FIXED);
        when(submissionPort.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static ProductSubmission submitted() {
        return ProductSubmission.draft(777L, 7L, 42L, SubmissionType.NEW, null,
                        new ProductContent("사과 1kg", null, new BigDecimal("12900"), 10, null, null, true))
                .withId(1L)
                .submit(SUBMITTED_AT);
    }

    @Test
    void 승인은_저장한_뒤에_발행한다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submitted()));

        SubmissionView view = service.approve(1L, 9L);

        assertEquals(SubmissionStatus.APPROVED, view.status());
        assertEquals(9L, view.decidedByUserId());
        assertTrue(view.awaitingCatalog());
        // 순서가 중요하다 — 저장 전에 발행하면 저장이 실패했을 때 카탈로그에만 상품이 생긴다.
        // (같은 트랜잭션의 outbox 라 실제로는 함께 롤백되지만, 순서를 코드에 남겨 둔다.)
        InOrder order = inOrder(submissionPort, publishPort);
        order.verify(submissionPort).save(any());
        order.verify(publishPort).productApproved(any());
    }

    @Test
    void 발행하는_것은_저장된_사본이다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submitted()));

        service.approve(1L, 9L);

        ArgumentCaptor<ProductSubmission> published = ArgumentCaptor.forClass(ProductSubmission.class);
        verify(publishPort).productApproved(published.capture());
        // 저장 전 사본을 발행하면 번호가 채번되기 전 값이 나가고, outbox 의 aggregate_id 가
        // 신청서를 가리키지 못한다.
        assertEquals(1L, published.getValue().requireSubmissionId());
        assertEquals(SubmissionStatus.APPROVED, published.getValue().status());
    }

    @Test
    void 반려는_발행하지_않는다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submitted()));

        SubmissionView view = service.reject(1L, 9L, "대표 이미지가 없습니다");

        assertEquals(SubmissionStatus.REJECTED, view.status());
        assertEquals("대표 이미지가 없습니다", view.rejectReason());
        // 바깥에서 할 일이 없다 — 셀러가 콘솔에서 사유를 보고 고쳐 다시 낸다.
        verifyNoInteractions(publishPort);
    }

    @Test
    void 심사는_셀러_스코프_없이_불러온다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submitted()));

        service.approve(1L, 9L);

        // 운영자는 어느 셀러에도 속하지 않으므로 loadAny 다. 그래서 이 두 메서드의 유일한
        // 방어가 웹 계층의 ROLE_ADMIN 이라는 사실이 여기에 드러나 있어야 한다.
        verify(submissionPort).loadAny(1L);
    }

    @Test
    void 없는_신청서는_404_로_끝난다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.empty());

        assertThrows(SubmissionNotFoundException.class, () -> service.approve(1L, 9L));
        assertThrows(SubmissionNotFoundException.class, () -> service.reject(1L, 9L, "사유"));
        verify(submissionPort, never()).save(any());
        verifyNoInteractions(publishPort);
    }

    @Test
    void 제출되지_않은_건을_승인하면_발행까지_가지_않는다() {
        ProductSubmission draft = ProductSubmission
                .draft(777L, 7L, 42L, SubmissionType.NEW, null,
                        new ProductContent("사과", null, BigDecimal.ONE, 1, null, null, true))
                .withId(1L);
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(draft));

        assertThrows(IllegalSubmissionStateException.class, () -> service.approve(1L, 9L));
        verifyNoInteractions(publishPort);
    }

    @Test
    void 심사_시각은_주입된_시계에서_온다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submitted()));

        SubmissionView view = service.approve(1L, 9L);

        assertEquals(10, view.decidedAt().getHour());
        // 승인은 상태만 바꾼다. 상품번호는 order-service 의 회신을 기다린다.
        assertNull(view.productId());
    }
}
