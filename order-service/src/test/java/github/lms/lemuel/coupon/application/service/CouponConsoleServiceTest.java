package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponExport;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycle;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycleCount;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponPage;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponQuery;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponRow;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponUsageRow;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.application.port.out.SearchCouponsPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 쿠폰 콘솔 서비스 단위 테스트.
 *
 * <p>가장 중요한 계약은 <b>목록과 집계가 같은 "지금"을 쓴다</b>는 것이다. 두 SQL 이 각자
 * {@code NOW()} 를 부르면 만료 경계에 걸친 쿠폰이 합계와 목록에서 다르게 세어지고, 그 차이는
 * 재현하기도 어렵다. 고정 Clock 으로 그 계약을 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class CouponConsoleServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED = LocalDateTime.of(2026, 3, 15, 10, 0)
            .atZone(KST).toInstant();

    static final class RecordingPort implements SearchCouponsPort {
        final List<CouponCriteria> criteriaSeen = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        final List<Integer> usageLimits = new ArrayList<>();
        long total;
        List<CouponRow> rows = List.of();
        List<CouponLifecycleCount> lifecycleCounts = List.of();

        @Override
        public List<CouponRow> search(CouponCriteria criteria, int page, int size) {
            criteriaSeen.add(criteria);
            pages.add(page);
            sizes.add(size);
            return rows;
        }

        @Override
        public long count(CouponCriteria criteria) {
            criteriaSeen.add(criteria);
            return total;
        }

        @Override
        public List<CouponLifecycleCount> countByLifecycle(CouponCriteria criteria) {
            criteriaSeen.add(criteria);
            return lifecycleCounts;
        }

        @Override
        public List<CouponUsageRow> usages(Long couponId, int limit) {
            usageLimits.add(limit);
            return List.of();
        }
    }

    @Mock LoadCouponPort loadCouponPort;
    @Mock SaveCouponPort saveCouponPort;

    RecordingPort port;
    CouponConsoleService service;

    @BeforeEach
    void setUp() {
        port = new RecordingPort();
        service = new CouponConsoleService(port, loadCouponPort, saveCouponPort,
                Clock.fixed(FIXED, KST));
    }

    private static CouponQuery query(int page, int size) {
        return new CouponQuery(null, null, null, null, null, page, size);
    }

    private static CouponRow row() {
        return new CouponRow(1L, "WELCOME10", "PERCENTAGE", BigDecimal.TEN, BigDecimal.ZERO, null,
                100, 3, "ALL", null, null, null, true, "ACTIVE",
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private static Coupon coupon(boolean active) {
        Coupon created = Coupon.create("WELCOME10", CouponType.PERCENTAGE, BigDecimal.TEN,
                BigDecimal.ZERO, null, 100, LocalDateTime.of(2027, 1, 1, 0, 0));
        if (!active) {
            created.deactivate();
        }
        return created;
    }

    @Test
    @DisplayName("목록과 집계는 같은 '지금'을 쓴다 — 각자 NOW() 를 부르면 만료 경계 쿠폰이 다르게 세어진다")
    void listAndCountShareSameNow() {
        port.total = 1;
        port.rows = List.of(row());

        service.search(query(0, 10));
        service.countByLifecycle(query(0, 1));

        LocalDateTime expected = LocalDateTime.of(2026, 3, 15, 10, 0);
        assertThat(port.criteriaSeen).extracting(SearchCouponsPort.CouponCriteria::now)
                .containsOnly(expected);
    }

    @Test
    @DisplayName("생성일 종료일은 그날을 포함한다")
    void endDateIsInclusiveDay() {
        port.total = 0;

        service.search(new CouponQuery(null, null, null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 0, 10));

        SearchCouponsPort.CouponCriteria criteria = port.criteriaSeen.get(0);
        assertThat(criteria.from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.toExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("뒤집힌 생성일 구간은 바로잡는다")
    void swapsInvertedRange() {
        port.total = 0;

        service.search(new CouponQuery(null, null, null,
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1), 0, 10));

        assertThat(port.criteriaSeen.get(0).from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    @Test
    @DisplayName("수명 상태는 enum 이름으로 넘긴다")
    void mapsLifecycleToName() {
        port.total = 0;

        service.search(new CouponQuery(null, CouponLifecycle.EXHAUSTED, null, null, null, 0, 10));

        assertThat(port.criteriaSeen.get(0).lifecycle()).isEqualTo("EXHAUSTED");
    }

    @Test
    @DisplayName("size 는 상한 200 으로 잘리고 0 이하는 50 이 된다")
    void clampsPageSize() {
        port.total = 10_000;
        port.rows = List.of(row());

        service.search(query(0, 5_000));
        assertThat(port.sizes.get(0)).isEqualTo(200);

        service.search(query(0, 0));
        assertThat(port.sizes.get(1)).isEqualTo(50);
    }

    @Test
    @DisplayName("총 장수 0 이면 목록 쿼리를 던지지 않는다")
    void skipsListQueryWhenEmpty() {
        port.total = 0;

        CouponPage result = service.search(query(0, 10));

        assertThat(port.sizes).isEmpty();
        assertThat(result.content()).isEmpty();
    }

    @Test
    @DisplayName("사용 내역 조회는 상한 500 으로 잘린다 — 인기 쿠폰은 이력이 수만 건이다")
    void clampsUsageLimit() {
        service.usages(1L, 100_000);
        assertThat(port.usageLimits.get(0)).isEqualTo(500);

        service.usages(1L, 0);
        assertThat(port.usageLimits.get(1)).isEqualTo(100);
    }

    @Test
    @DisplayName("내보내기는 상한 5000 에서 끊고 잘렸다고 알린다")
    void exportTruncates() {
        port.total = 12_345;
        List<CouponRow> chunk = new ArrayList<>();
        for (int i = 0; i < 200; i++) chunk.add(row());
        port.rows = chunk;

        CouponExport export = service.export(query(0, 10));

        assertThat(export.rows()).hasSize(5_000);
        assertThat(export.truncated()).isTrue();
    }

    @Test
    @DisplayName("중단은 코드를 대문자로 정규화해 찾는다 — 운영자가 소문자로 입력해도 멈춰야 한다")
    void deactivateNormalizesCode() {
        Coupon target = coupon(true);
        when(loadCouponPort.findByCode("WELCOME10")).thenReturn(Optional.of(target));
        when(saveCouponPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Coupon result = service.deactivate("  welcome10  ");

        assertThat(result.isActive()).isFalse();
        verify(saveCouponPort).save(target);
    }

    @Test
    @DisplayName("재개는 다시 켠다")
    void activate() {
        Coupon target = coupon(false);
        when(loadCouponPort.findByCode("WELCOME10")).thenReturn(Optional.of(target));
        when(saveCouponPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.activate("WELCOME10").isActive()).isTrue();
    }

    @Test
    @DisplayName("없는 쿠폰은 거부하고 저장까지 가지 않는다")
    void rejectsMissingCoupon() {
        when(loadCouponPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate("NOPE"))
                .isInstanceOf(CouponInvariantViolationException.class);

        verify(saveCouponPort, never()).save(any());
    }

    @Test
    @DisplayName("빈 코드는 조회하기 전에 거부한다")
    void rejectsBlankCode() {
        assertThatThrownBy(() -> service.deactivate("  "))
                .isInstanceOf(CouponInvariantViolationException.class);

        verify(saveCouponPort, never()).save(any());
    }
}
