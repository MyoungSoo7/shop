package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.in.RecordCommerceUseCase;
import github.lms.lemuel.seller.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.application.port.out.SellerCommerceProjectionPort;
import github.lms.lemuel.seller.application.port.out.SellerDirectoryProjectionPort;
import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 아홉 토픽이 도착하는 곳 — 여기서 고정하는 것은 <b>모르는 값을 채우지 않는다</b>는 규칙이다.
 *
 * <p>특히 {@code externalRef} 에서 셀러 ID 를 유도하지 못했을 때 0 이나 -1 로 메우지 않는 것.
 * 파트너 콘솔에서 그 실수는 남의 매출을 보는 것이었지만, 여기서는 유도에 실패한 조직들이 전부
 * 같은 셀러로 뭉쳐 <b>남의 이름으로 상품을 올리는</b> 경로가 된다.
 *
 * <p>{@code productRegistered} 만 따로 무거운 이유는 그것만 사본이 아니라 우리 원장을 건드리기
 * 때문이다 — 우리가 낸 요청의 회신이라서다.
 */
class SellerProjectionServiceTest {

    private static final OffsetDateTime SUBMITTED_AT =
            OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private SellerDirectoryProjectionPort directoryPort;
    private SellerCommerceProjectionPort commercePort;
    private ProductSubmissionPort submissionPort;
    private SellerProjectionService service;

    @BeforeEach
    void setUp() {
        directoryPort = mock(SellerDirectoryProjectionPort.class);
        commercePort = mock(SellerCommerceProjectionPort.class);
        submissionPort = mock(ProductSubmissionPort.class);
        service = new SellerProjectionService(directoryPort, commercePort, submissionPort);
    }

    private static ProductSubmission approved() {
        return ProductSubmission.draft(777L, 7L, 42L, SubmissionType.NEW, null,
                        new ProductContent("사과 1kg", null, new BigDecimal("12900"), 10, null, null, true))
                .withId(1L)
                .submit(SUBMITTED_AT)
                .approve(9L, SUBMITTED_AT);
    }

    // ------------------------------------------------------------------ 조직

    @Test
    void 셀러_조직은_externalRef_에서_셀러_ID_를_유도한다() {
        service.organizationCreated(new RecordDirectoryUseCase.OrganizationCreated(
                7L, "명수상사", OrgType.SELLER, "SELLER-777", 42L));

        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "SELLER-777", 777L, 42L);
    }

    @Test
    void 숫자만_있는_externalRef_도_받는다() {
        service.organizationCreated(new RecordDirectoryUseCase.OrganizationCreated(
                7L, "명수상사", OrgType.SELLER, "777", 42L));

        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "777", 777L, 42L);
    }

    @Test
    void 유도에_실패하면_비워_둔다() {
        service.organizationCreated(new RecordDirectoryUseCase.OrganizationCreated(
                7L, "명수상사", OrgType.SELLER, "SELLER-ABC", 42L));

        // 0 이나 -1 로 메우면 유도에 실패한 조직들이 전부 같은 셀러로 뭉친다.
        verify(directoryPort).upsertOrganization(
                eq(7L), eq("명수상사"), eq(OrgType.SELLER), eq("SELLER-ABC"), isNull(), eq(42L));
    }

    @Test
    void 법인_조직은_externalRef_가_숫자여도_셀러가_되지_않는다() {
        service.organizationCreated(new RecordDirectoryUseCase.OrganizationCreated(
                9L, "르무엘법인", OrgType.CORPORATE, "777", 42L));

        // 타입이 먼저다. 여기서 유도하면 법인 고객이 셀러 콘솔의 쓰기 경로를 얻는다.
        verify(directoryPort).upsertOrganization(
                eq(9L), eq("르무엘법인"), eq(OrgType.CORPORATE), eq("777"), isNull(), eq(42L));
    }

    @Test
    void 구성원_이벤트는_membershipId_로_처리한다() {
        service.memberJoined(new RecordDirectoryUseCase.MemberJoined(1L, 7L, 42L, MemberRole.MANAGER));
        service.memberRemoved(new RecordDirectoryUseCase.MemberRemoved(1L, 7L, 42L));
        service.memberRoleChanged(new RecordDirectoryUseCase.MemberRoleChanged(2L, 7L, 42L, MemberRole.STAFF));

        verify(directoryPort).upsertMembership(1L, 7L, 42L, MemberRole.MANAGER);
        // (조직, 사용자) 로 지우면 늦게 도착한 옛 탈퇴 이벤트가 재가입으로 생긴 멤버십을 지운다 —
        // 여기서는 그게 상품 등록·송장 등록 권한이 함께 사라진다는 뜻이다.
        verify(directoryPort).markRemoved(1L);
        verify(directoryPort).changeRole(2L, MemberRole.STAFF);
    }

    // ------------------------------------------------------------------ 주문·결제

    @Test
    void 결제와_환불과_주문은_받은_값을_그대로_적는다() {
        LocalDateTime capturedAt = LocalDateTime.of(2026, 8, 30, 14, 0);
        service.captured(new RecordCommerceUseCase.SaleCaptured(
                200L, 100L, 777L, new BigDecimal("12900"), "CARD", capturedAt, true));
        service.refunded(new RecordCommerceUseCase.SaleRefunded(
                200L, "rf-1", 100L, new BigDecimal("1000"), new BigDecimal("1000")));
        service.orderCreated(new RecordCommerceUseCase.OrderCreated(
                100L, 42L, 5001L, "PAID", new BigDecimal("12900"), capturedAt));
        service.productChanged(5001L, "사과 1kg");

        verify(commercePort).upsertSale(200L, 100L, 777L, new BigDecimal("12900"), "CARD", capturedAt, true);
        verify(commercePort).upsertRefund(200L, "rf-1", 100L, new BigDecimal("1000"), new BigDecimal("1000"));
        verify(commercePort).upsertOrder(100L, 42L, 5001L, "PAID", new BigDecimal("12900"), capturedAt);
        verify(commercePort).upsertProduct(5001L, "사과 1kg");
    }

    @Test
    void 셀러가_없는_결제도_그대로_적는다() {
        // 어느 셀러 화면에도 뜨지 않지만, 버리면 나중에 sellerId 가 채워져도 복구할 수 없다.
        service.captured(new RecordCommerceUseCase.SaleCaptured(
                200L, 100L, null, new BigDecimal("12900"), "CARD", LocalDateTime.of(2026, 8, 30, 14, 0), false));

        verify(commercePort).upsertSale(eq(200L), eq(100L), isNull(), any(), eq("CARD"), any(), eq(false));
    }

    // ------------------------------------------------------------------ 회신

    @Test
    void 카탈로그_회신은_사본과_원장을_모두_갱신한다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(approved()));

        service.productRegistered(new RecordCommerceUseCase.ProductRegistered(5001L, "사과 1kg", 1L, 777L));

        verify(commercePort).linkProduct(5001L, "사과 1kg", 1L);
        ArgumentCaptor<ProductSubmission> saved = ArgumentCaptor.forClass(ProductSubmission.class);
        verify(submissionPort).save(saved.capture());
        assertEquals(5001L, saved.getValue().productId());
    }

    @Test
    void 우리_신청서가_아니면_사본만_남기고_넘어간다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.empty());

        service.productRegistered(new RecordCommerceUseCase.ProductRegistered(5001L, "사과 1kg", 1L, 777L));

        // 예외로 올리면 이 이벤트는 재시도를 다 쓰고 DLT 로 가서 사람 손을 부른다 — 재처리로
        // 풀릴 일이 아니다.
        verify(commercePort).linkProduct(5001L, "사과 1kg", 1L);
        verify(submissionPort, never()).save(any());
    }

    @Test
    void 같은_상품번호로_재전달되면_아무_일도_하지_않는다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(approved().catalogRegistered(5001L)));

        service.productRegistered(new RecordCommerceUseCase.ProductRegistered(5001L, "사과 1kg", 1L, 777L));

        // at-least-once 라 같은 이벤트가 두 번 온다. 두 번째는 조용히 통과해야 한다.
        verify(submissionPort, never()).save(any());
    }

    @Test
    void 다른_상품번호로_회신이_오면_덮어쓰지_않는다() {
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(approved().catalogRegistered(5001L)));

        service.productRegistered(new RecordCommerceUseCase.ProductRegistered(6002L, "사과 1kg", 1L, 777L));

        // 한 신청서로 상품이 둘 생겼다는 뜻이다. 덮어쓰면 남은 하나가 미아가 되어 아무도 못 찾는다.
        verify(submissionPort, never()).save(any());
    }

    @Test
    void 승인_상태가_아닌_신청서에는_상품번호를_붙이지_않는다() {
        ProductSubmission submittedOnly = ProductSubmission
                .draft(777L, 7L, 42L, SubmissionType.NEW, null,
                        new ProductContent("사과", null, BigDecimal.ONE, 1, null, null, true))
                .withId(1L)
                .submit(SUBMITTED_AT);
        when(submissionPort.loadAny(1L)).thenReturn(Optional.of(submittedOnly));

        service.productRegistered(new RecordCommerceUseCase.ProductRegistered(5001L, "사과", 1L, 777L));

        // 붙이면 화면이 앞뒤가 안 맞고, 예외로 올리면 재시도로 풀리지 않는 이벤트가 DLT 로 간다.
        // 사실만 남기고 넘어간다.
        assertNull(submittedOnly.productId());
        verify(submissionPort, never()).save(any());
    }
}
