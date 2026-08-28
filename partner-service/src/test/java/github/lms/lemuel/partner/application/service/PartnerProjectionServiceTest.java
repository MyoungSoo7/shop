package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.in.RecordCatalogUseCase;
import github.lms.lemuel.partner.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.partner.application.port.in.RecordSalesUseCase;
import github.lms.lemuel.partner.application.port.out.PartnerCatalogProjectionPort;
import github.lms.lemuel.partner.application.port.out.PartnerDirectoryProjectionPort;
import github.lms.lemuel.partner.application.port.out.PartnerSalesProjectionPort;
import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.SellerTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 쓰기 경로 전부. 검증 로직은 거의 없고, 대신 규칙이 하나 있다 —
 * <b>이벤트가 말한 것만 적고 말하지 않은 것은 비워 둔다.</b>
 *
 * <p>여기서 가장 값나가는 테스트는 {@code deriveSellerId} 다. 이 한 줄이 조직 간 격리 경계
 * 전부이기 때문이다: 유도에 실패했을 때 0 이나 -1 로 메우면 실패한 조직들이 전부 같은 셀러로
 * 뭉쳐 <b>서로의 매출을 본다</b>. 그래서 "실패하면 null" 을 여러 입력 모양으로 고정한다.
 */
class PartnerProjectionServiceTest {

    private static final LocalDateTime CAPTURED = LocalDateTime.of(2026, 8, 27, 13, 40);
    private static final OffsetDateTime OCCURRED =
            OffsetDateTime.of(2026, 8, 27, 13, 40, 0, 0, ZoneOffset.ofHours(9));

    private PartnerDirectoryProjectionPort directoryPort;
    private PartnerSalesProjectionPort salesPort;
    private PartnerCatalogProjectionPort catalogPort;
    private PartnerProjectionService service;

    @BeforeEach
    void setUp() {
        directoryPort = mock(PartnerDirectoryProjectionPort.class);
        salesPort = mock(PartnerSalesProjectionPort.class);
        catalogPort = mock(PartnerCatalogProjectionPort.class);
        service = new PartnerProjectionService(directoryPort, salesPort, catalogPort);
    }

    private static RecordDirectoryUseCase.OrganizationCreated created(OrgType type, String externalRef) {
        return new RecordDirectoryUseCase.OrganizationCreated(7L, "명수상사", type, externalRef, 42L);
    }

    // ------------------------------------------------------------------ 셀러 ID 유도

    @Test
    void 접두사가_붙은_externalRef_는_마지막_하이픈_뒤를_읽는다() {
        service.organizationCreated(created(OrgType.SELLER, "SELLER-777"));

        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "SELLER-777", 777L, 42L);
    }

    @Test
    void 숫자만_온_externalRef_는_그대로_셀러_ID_다() {
        service.organizationCreated(created(OrgType.SELLER, "777"));

        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "777", 777L, 42L);
    }

    @Test
    void 앞뒤_공백은_털어_낸다() {
        service.organizationCreated(created(OrgType.SELLER, "  SELLER-42  "));

        // 원본 문자열은 손대지 않고 그대로 보관한다 — 유도 결과만 정제한다.
        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "  SELLER-42  ", 42L, 42L);
    }

    @Test
    void 숫자가_아니면_0_으로_메우지_않고_비워_둔다() {
        service.organizationCreated(created(OrgType.SELLER, "SELLER-A"));

        // ★ 이 테스트가 이 파일의 존재 이유다. 여기서 0 이 들어가는 순간, 유도에 실패한 모든
        //   조직이 0 번 셀러로 뭉쳐 서로의 매출을 본다. null 이면 그 조직의 매출만 막힌다.
        verify(directoryPort).upsertOrganization(
                eq(7L), eq("명수상사"), eq(OrgType.SELLER), eq("SELLER-A"), isNull(), eq(42L));
    }

    @Test
    void externalRef_가_비어_있어도_비워_둔다() {
        service.organizationCreated(created(OrgType.SELLER, ""));

        verify(directoryPort).upsertOrganization(
                eq(7L), eq("명수상사"), eq(OrgType.SELLER), eq(""), isNull(), eq(42L));
    }

    @Test
    void externalRef_가_null_이어도_터지지_않는다() {
        service.organizationCreated(created(OrgType.SELLER, null));

        verify(directoryPort).upsertOrganization(
                eq(7L), eq("명수상사"), eq(OrgType.SELLER), isNull(), isNull(), eq(42L));
    }

    @Test
    void 법인의_externalRef_는_숫자여도_셀러_ID_가_아니다() {
        // CORPORATE 의 externalRef 는 종목코드다. 타입 검사를 먼저 하지 않으면 종목코드가
        // 셀러 ID 로 둔갑해, 그 법인 화면에 남의 매출이 뜬다.
        service.organizationCreated(created(OrgType.CORPORATE, "005930"));

        verify(directoryPort).upsertOrganization(
                eq(7L), eq("명수상사"), eq(OrgType.CORPORATE), eq("005930"), isNull(), eq(42L));
    }

    @Test
    void 하이픈_뒤가_숫자가_아닌_경우도_비워_둔다() {
        service.organizationCreated(created(OrgType.SELLER, "not-a-number"));

        verify(directoryPort).upsertOrganization(anyLong(), any(), any(), any(), isNull(), anyLong());
    }

    @Test
    void 유도에_실패해도_조직_자체는_적는다() {
        service.organizationCreated(created(OrgType.SELLER, "SELLER-A"));

        // 조직은 실제로 존재한다. 적지 않으면 그 구성원은 로그인해도 "미소속" 으로 보이고,
        // 원인이 externalRef 형식이라는 단서가 어디에도 남지 않는다.
        verify(directoryPort).upsertOrganization(anyLong(), any(), any(), any(), isNull(), anyLong());
        verifyNoInteractions(salesPort);
    }

    @Test
    void 음수_모양_ref_는_마지막_하이픈_뒤를_읽는다() {
        // "-5" 는 5 로 읽힌다. 의도된 동작이고, 여기서 값이 바뀌면 기존 조직의 매출 화면이
        // 통째로 다른 셀러를 가리키게 되므로 고정해 둔다.
        service.organizationCreated(created(OrgType.SELLER, "-5"));

        verify(directoryPort).upsertOrganization(7L, "명수상사", OrgType.SELLER, "-5", 5L, 42L);
    }

    // ------------------------------------------------------------------ 구성원

    @Test
    void 가입은_membershipId_로_적는다() {
        service.memberJoined(new RecordDirectoryUseCase.MemberJoined(11L, 7L, 42L, MemberRole.MANAGER));

        verify(directoryPort).upsertMembership(11L, 7L, 42L, MemberRole.MANAGER);
    }

    @Test
    void 탈퇴도_membershipId_로_지운다() {
        service.memberRemoved(new RecordDirectoryUseCase.MemberRemoved(11L, 7L, 42L));

        // (조직, 사용자) 로 지우면 늦게 온 옛 탈퇴가 재가입으로 새로 생긴 멤버십을 지운다.
        // 그 사람은 화면이 그냥 안 열리고, 단서는 아무 데도 남지 않는다.
        verify(directoryPort).markRemoved(11L);
    }

    @Test
    void 역할_변경은_새_역할만_넘긴다() {
        service.memberRoleChanged(
                new RecordDirectoryUseCase.MemberRoleChanged(11L, 7L, 42L, MemberRole.STAFF));

        verify(directoryPort).changeRole(11L, MemberRole.STAFF);
    }

    // ------------------------------------------------------------------ 매출

    @Test
    void 결제는_실린_값_그대로_적는다() {
        service.captured(new RecordSalesUseCase.SaleCaptured(
                55L, 10231L, 777L, new BigDecimal("50000"), "VIP", "WEEKLY", "CARD", CAPTURED, false));

        verify(salesPort).upsertSale(55L, 10231L, 777L, new BigDecimal("50000"),
                "VIP", "WEEKLY", "CARD", CAPTURED, false);
    }

    @Test
    void 셀러_미할당_결제도_버리지_않고_담아_둔다() {
        service.captured(new RecordSalesUseCase.SaleCaptured(
                55L, 10231L, null, new BigDecimal("50000"), null, null, "CARD", CAPTURED, true));

        // 지우면 나중에 셀러가 붙어 같은 paymentId 로 재발행돼도 살아나지 않는다.
        verify(salesPort).upsertSale(eq(55L), eq(10231L), isNull(), eq(new BigDecimal("50000")),
                isNull(), isNull(), eq("CARD"), eq(CAPTURED), eq(true));
    }

    @Test
    void 환불은_결제_행을_찾지_않고_따로_쌓는다() {
        service.refunded(new RecordSalesUseCase.SaleRefunded(
                55L, "refund-1", 10231L, new BigDecimal("10000"), new BigDecimal("10000")));

        // 토픽이 달라 환불이 결제보다 먼저 올 수 있다. "결제 없으면 스킵" 하면 그 환불은
        // 영영 반영되지 않으므로, 여기서 결제 행을 건드리지 않는 것이 맞다.
        verify(salesPort).upsertRefund(55L, "refund-1", 10231L,
                new BigDecimal("10000"), new BigDecimal("10000"));
        verifyNoInteractions(catalogPort);
    }

    @Test
    void 누적_환불액이_없으면_null_로_넘어간다() {
        service.refunded(new RecordSalesUseCase.SaleRefunded(
                55L, "evt-9", 10231L, BigDecimal.ZERO, null));

        // 0 으로 메우면 "누적 0원 확정" 이 되어 delta 합보다 작은 값이 사실처럼 읽힌다.
        verify(salesPort).upsertRefund(eq(55L), eq("evt-9"), eq(10231L), eq(BigDecimal.ZERO), isNull());
    }

    // ------------------------------------------------------------------ 보조

    @Test
    void 주문은_상품이_없어도_적는다() {
        service.orderCreated(new RecordCatalogUseCase.OrderCreated(
                10231L, 42L, null, "PAID", new BigDecimal("50000"), CAPTURED));

        verify(catalogPort).upsertOrder(eq(10231L), eq(42L), isNull(), eq("PAID"),
                eq(new BigDecimal("50000")), eq(CAPTURED));
    }

    @Test
    void 상품명은_null_도_그대로_넘긴다() {
        service.productChanged(11L, null);

        // 계약상 name 은 nullable 이다. 빈 문자열로 바꾸면 "이름이 빈 상품" 과
        // "이름을 아직 못 받은 상품" 이 구분되지 않는다.
        verify(catalogPort).upsertProduct(eq(11L), isNull());
    }

    @Test
    void 등급_변경은_그대로_반영한다() {
        service.sellerTierChanged(new RecordCatalogUseCase.SellerTierChanged(
                777L, SellerTier.VIP, "PROMOTION", LocalDate.of(2026, 8, 1), OCCURRED, false));

        verify(catalogPort).upsertSellerTier(777L, SellerTier.VIP, "PROMOTION",
                LocalDate.of(2026, 8, 1), OCCURRED);
    }

    @Test
    void 백필도_등급_값은_똑같이_반영한다() {
        service.sellerTierChanged(new RecordCatalogUseCase.SellerTierChanged(
                777L, SellerTier.STRATEGIC, "BACKFILL", LocalDate.of(2026, 1, 1), OCCURRED, true));

        // 백필은 "이때 바뀌었다" 가 아니라 "이미 이랬다" 다. 값은 반영하되 로그로 구분한다.
        // 반영을 건너뛰면 그 셀러의 등급이 영영 비어 있게 된다.
        verify(catalogPort).upsertSellerTier(777L, SellerTier.STRATEGIC, "BACKFILL",
                LocalDate.of(2026, 1, 1), OCCURRED);
    }
}
