package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SubmissionPage;
import github.lms.lemuel.seller.application.port.dto.SubmissionQuery;
import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import github.lms.lemuel.seller.domain.SubmissionType;
import github.lms.lemuel.seller.domain.exception.InsufficientSellerRoleException;
import github.lms.lemuel.seller.domain.exception.NotASellerException;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 셀러가 자기 상품을 올리는 경로 — 여기서 고정하는 것은 <b>인가와 조회 대상</b>이다.
 *
 * <p>상태 전이 자체는 {@code ProductSubmissionTest} 가 본다. 같은 규칙을 두 곳에서 검사하면
 * 한쪽만 고쳐진 채 남고, 그때 어느 쪽이 진짜 규칙인지 코드가 말해 주지 않는다.
 */
class ProductSubmissionServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-01T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ProductSubmissionPort submissionPort;
    private ProductSubmissionService service;

    @BeforeEach
    void setUp() {
        submissionPort = mock(ProductSubmissionPort.class);
        service = new ProductSubmissionService(submissionPort, FIXED);
        // save 는 대부분의 검사에서 관심사가 아니다 — 번호만 붙여 그대로 돌려준다.
        when(submissionPort.save(any())).thenAnswer(call -> {
            ProductSubmission given = call.getArgument(0);
            return given.submissionId() == null ? given.withId(1L) : given;
        });
    }

    private static SellerScope scope(MemberRole role) {
        return new SellerScope(7L, "명수상사", OrgType.SELLER, 777L, role);
    }

    private static ProductContent content(String name) {
        return new ProductContent(name, null, new BigDecimal("12900"), 10, null, null, true);
    }

    private static ProductSubmission stored(long sellerId, SubmissionStatus status) {
        ProductSubmission draft = ProductSubmission
                .draft(sellerId, 7L, 42L, SubmissionType.NEW, null, content("사과 1kg"))
                .withId(1L);
        return status == SubmissionStatus.DRAFT
                ? draft
                : draft.submit(OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    // ------------------------------------------------------------------ 작성

    @Test
    void 작성은_스코프의_셀러로만_만들어진다() {
        service.create(scope(MemberRole.OWNER), 42L, SubmissionType.NEW, null, content("사과 1kg"));

        ArgumentCaptor<ProductSubmission> saved = ArgumentCaptor.forClass(ProductSubmission.class);
        verify(submissionPort).save(saved.capture());
        // 셀러 ID 가 요청이 아니라 스코프에서 온다는 것이 이 서비스의 보안 전부다.
        assertEquals(777L, saved.getValue().sellerId());
        assertEquals(7L, saved.getValue().organizationId());
        assertEquals(42L, saved.getValue().createdByUserId());
        assertEquals(SubmissionStatus.DRAFT, saved.getValue().status());
    }

    @Test
    void 신규인데_대상_상품번호가_실려오면_버린다() {
        service.create(scope(MemberRole.OWNER), 42L, SubmissionType.NEW, 5001L, content("사과 1kg"));

        ArgumentCaptor<ProductSubmission> saved = ArgumentCaptor.forClass(ProductSubmission.class);
        verify(submissionPort).save(saved.capture());
        // 조용히 들고 가면 화면에 "수정 대상" 으로 보이는데 실제로는 아무 데도 안 쓰인다.
        assertNull(saved.getValue().baseProductId());
    }

    @Test
    void 수정_신청은_대상_상품번호를_유지한다() {
        service.create(scope(MemberRole.OWNER), 42L, SubmissionType.UPDATE, 5001L, content("사과 2kg"));

        ArgumentCaptor<ProductSubmission> saved = ArgumentCaptor.forClass(ProductSubmission.class);
        verify(submissionPort).save(saved.capture());
        assertEquals(5001L, saved.getValue().baseProductId());
    }

    @Test
    void STAFF_는_작성_수정_제출_모두_막힌다() {
        SellerScope staff = scope(MemberRole.STAFF);

        assertThrows(InsufficientSellerRoleException.class,
                () -> service.create(staff, 42L, SubmissionType.NEW, null, content("사과")));
        assertThrows(InsufficientSellerRoleException.class,
                () -> service.update(staff, 1L, content("사과")));
        assertThrows(InsufficientSellerRoleException.class, () -> service.submit(staff, 1L));
        // 막힌 요청이 저장소에 닿지도 않아야 한다. 닿은 뒤 예외가 나면 부분 반영이 생길 수 있다.
        verify(submissionPort, never()).save(any());
        verify(submissionPort, never()).load(anyLong(), anyLong());
    }

    @Test
    void 법인_조직은_쓰기_경로_전체가_막힌다() {
        SellerScope corporate = new SellerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.OWNER);

        assertThrows(NotASellerException.class,
                () -> service.create(corporate, 42L, SubmissionType.NEW, null, content("사과")));
    }

    // ------------------------------------------------------------------ 수정·제출

    @Test
    void 수정과_제출은_내_셀러의_신청서만_불러온다() {
        when(submissionPort.load(1L, 777L)).thenReturn(Optional.of(stored(777L, SubmissionStatus.DRAFT)));

        service.update(scope(MemberRole.OWNER), 1L, content("사과 2kg"));

        // 인자 둘 다 필요하다는 것이 요점이다 — sellerId 없이 부르는 오버로드가 없어야 한다.
        verify(submissionPort).load(1L, 777L);
    }

    @Test
    void 남의_신청서는_없는_것과_같이_다룬다() {
        when(submissionPort.load(1L, 777L)).thenReturn(Optional.empty());

        // 404 와 403 을 구분하지 않는다. 구분하면 번호를 훑어 남의 신청서 존재를 알아낼 수 있다.
        assertThrows(SubmissionNotFoundException.class,
                () -> service.update(scope(MemberRole.OWNER), 1L, content("사과")));
        assertThrows(SubmissionNotFoundException.class,
                () -> service.submit(scope(MemberRole.OWNER), 1L));
    }

    @Test
    void 제출_시각은_주입된_시계에서_온다() {
        when(submissionPort.load(1L, 777L)).thenReturn(Optional.of(stored(777L, SubmissionStatus.DRAFT)));

        SubmissionView view = service.submit(scope(MemberRole.OWNER), 1L);

        assertEquals(SubmissionStatus.SUBMITTED, view.status());
        // KST 시계다. UTC 로 두면 한국 시간 오전 9시 이전 제출이 전날로 기록된다.
        assertEquals(10, view.submittedAt().getHour());
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 목록은_총건수가_0이면_두번째_쿼리를_쏘지_않는다() {
        when(submissionPort.countBySeller(777L, null)).thenReturn(0L);

        SubmissionPage page = service.mine(scope(MemberRole.STAFF), new SubmissionQuery(null, 0, 20));

        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalPages());
        verify(submissionPort, never()).findBySeller(anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void 목록은_정규화된_페이지로_오프셋을_계산한다() {
        when(submissionPort.countBySeller(777L, SubmissionStatus.SUBMITTED)).thenReturn(45L);
        when(submissionPort.findBySeller(777L, SubmissionStatus.SUBMITTED, 20, 40L))
                .thenReturn(List.of(stored(777L, SubmissionStatus.SUBMITTED)));

        SubmissionPage page = service.mine(
                scope(MemberRole.STAFF), new SubmissionQuery(SubmissionStatus.SUBMITTED, 2, 0));

        // size=0 은 기본값 20 으로, offset 은 page*size 로. 20*2=40 이 그대로 포트에 간다.
        assertEquals(2, page.page());
        assertEquals(20, page.size());
        assertEquals(45L, page.totalElements());
        assertEquals(3, page.totalPages());
        assertEquals(1, page.content().size());
    }

    @Test
    void 상세도_셀러_스코프로만_찾는다() {
        when(submissionPort.load(1L, 777L)).thenReturn(Optional.of(stored(777L, SubmissionStatus.DRAFT)));

        Optional<SubmissionView> found = service.mine(scope(MemberRole.STAFF), 1L);

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().submissionId());
        verify(submissionPort).load(1L, 777L);
    }

    @Test
    void 심사_대기열은_셀러_스코프_없이_조회한다() {
        when(submissionPort.countPending()).thenReturn(1L);
        when(submissionPort.findPending(20, 0L)).thenReturn(List.of(stored(999L, SubmissionStatus.SUBMITTED)));

        SubmissionPage page = service.pending(new SubmissionQuery(null, 0, 20));

        // 여기만 스코프가 없다. 유일한 방어가 웹 계층의 ROLE_ADMIN 이라는 사실을 고정해 둔다 —
        // 이 메서드를 다른 곳에서 부르려면 같은 잠금을 다시 만들어야 한다.
        assertEquals(1, page.content().size());
        assertEquals(999L, page.content().get(0).sellerId());
        verify(submissionPort, never()).findBySeller(anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void 심사_대기열도_총건수가_0이면_두번째_쿼리를_쏘지_않는다() {
        when(submissionPort.countPending()).thenReturn(0L);

        assertTrue(service.pending(new SubmissionQuery(null, 0, 20)).content().isEmpty());
        verify(submissionPort, never()).findPending(anyInt(), anyLong());
    }

    @Test
    void 페이지_크기는_상한을_넘지_못한다() {
        when(submissionPort.countBySeller(777L, null)).thenReturn(1000L);
        when(submissionPort.findBySeller(777L, null, SubmissionQuery.MAX_SIZE, 0L)).thenReturn(List.of());

        SubmissionPage page = service.mine(scope(MemberRole.OWNER), new SubmissionQuery(null, -3, 9999));

        // 음수 페이지는 0 으로, 과대 size 는 상한으로. 상한이 없으면 한 번의 요청이 전체를 긁는다.
        assertEquals(0, page.page());
        assertEquals(SubmissionQuery.MAX_SIZE, page.size());
    }
}
