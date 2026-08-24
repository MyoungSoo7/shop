package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.in.ManageCouponUseCase;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.application.port.out.SearchCouponsPort;
import github.lms.lemuel.coupon.application.port.out.SearchCouponsPort.CouponCriteria;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠폰 운영 콘솔 서비스.
 *
 * <p><b>기존 {@code CouponService} 와 나눈 이유</b>: 저쪽은 고객 경로(검증·사용·회수)이고 규칙의
 * 중심이 동시성과 멱등이다. 여기는 운영자 경로라 관심사가 목록·집계·중단이다. 한 클래스에 두면
 * 조회를 고치다 사용 경로의 원자적 UPDATE 를 건드리게 된다.
 *
 * <p><b>{@link Clock} 을 주입받는 이유</b>: 수명 상태 판정이 "지금"에 달려 있어, 테스트가 시각을
 * 고정하지 못하면 만료·예정 경계를 검증할 수 없다. 한 요청 안에서는 같은 순간을 목록과 집계에
 * 함께 넘겨 둘이 어긋나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class CouponConsoleService implements SearchCouponsUseCase, ManageCouponUseCase {

    /** 한 페이지 최대 장수. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 장수. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** CSV 내보내기 최대 행수. */
    public static final int MAX_EXPORT_ROWS = 5_000;

    /** 사용 내역 조회 최대 건수. 인기 쿠폰은 사용 이력이 수만 건이 된다. */
    public static final int MAX_USAGE_ROWS = 500;

    private final SearchCouponsPort searchCouponsPort;
    private final LoadCouponPort loadCouponPort;
    private final SaveCouponPort saveCouponPort;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CouponPage search(CouponQuery query) {
        CouponCriteria criteria = toCriteria(query);
        int page = Math.max(query.page(), 0);
        int size = normalizeSize(query.size());

        long total = searchCouponsPort.count(criteria);
        List<CouponRow> content = total == 0
                ? List.of()
                : searchCouponsPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new CouponPage(content, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponLifecycleCount> countByLifecycle(CouponQuery query) {
        return searchCouponsPort.countByLifecycle(toCriteria(query));
    }

    @Override
    @Transactional(readOnly = true)
    public CouponExport export(CouponQuery query) {
        CouponCriteria criteria = toCriteria(query);
        long total = searchCouponsPort.count(criteria);
        if (total == 0) {
            return new CouponExport(List.of(), false, 0);
        }

        int wanted = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<CouponRow> rows = new ArrayList<>(wanted);
        for (int page = 0; rows.size() < wanted; page++) {
            List<CouponRow> chunk = searchCouponsPort.search(criteria, page, MAX_PAGE_SIZE);
            if (chunk.isEmpty()) {
                break;
            }
            for (CouponRow row : chunk) {
                if (rows.size() == wanted) {
                    break;
                }
                rows.add(row);
            }
        }

        return new CouponExport(rows, total > MAX_EXPORT_ROWS, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponUsageRow> usages(Long couponId, int limit) {
        int capped = limit <= 0 ? 100 : Math.min(limit, MAX_USAGE_ROWS);
        return searchCouponsPort.usages(couponId, capped);
    }

    @Override
    @Transactional
    public Coupon activate(String code) {
        Coupon coupon = require(code);
        coupon.activate();
        return saveCouponPort.save(coupon);
    }

    @Override
    @Transactional
    public Coupon deactivate(String code) {
        Coupon coupon = require(code);
        coupon.deactivate();
        return saveCouponPort.save(coupon);
    }

    private Coupon require(String code) {
        if (code == null || code.isBlank()) {
            throw new CouponInvariantViolationException("쿠폰 코드가 필요합니다.");
        }
        return loadCouponPort.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new CouponInvariantViolationException(
                        "쿠폰을 찾을 수 없습니다. code=" + code));
    }

    /**
     * 화면 질의를 어댑터 조건으로 옮긴다.
     *
     * <p>"지금"을 여기서 한 번 읽어 목록·집계에 같은 값을 넘긴다 — 두 SQL 이 각자
     * {@code NOW()} 를 부르면 만료 경계에 걸친 쿠폰이 합계와 목록에서 다르게 세어진다.
     */
    private CouponCriteria toCriteria(CouponQuery query) {
        LocalDate from = query.from();
        LocalDate to = query.to();
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        return new CouponCriteria(
                blankToNull(query.code()),
                query.lifecycle() != null ? query.lifecycle().name() : null,
                blankToNull(query.type()),
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                LocalDateTime.now(clock));
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
