package github.lms.lemuel.coupon.application.port.out;

import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycleCount;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponRow;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponUsageRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 쿠폰 콘솔 조회 포트.
 *
 * <p>{@code now} 를 조건에 함께 넘기는 이유: 수명 상태(만료·예정)는 <b>지금이 언제인가</b>에
 * 달렸다. 어댑터가 {@code NOW()} 를 직접 부르면 목록 SQL 과 집계 SQL 이 서로 다른 순간을
 * 기준으로 판정해, 합계와 목록이 미묘하게 어긋난다. 한 요청 안에서는 같은 시각을 쓴다.
 */
public interface SearchCouponsPort {

    /** 조건에 맞는 쿠폰을 생성 최신순으로 한 페이지 조회한다. */
    List<CouponRow> search(CouponCriteria criteria, int page, int size);

    /** 같은 조건의 총 장수. */
    long count(CouponCriteria criteria);

    /** 같은 조건의 수명 상태별 장수. */
    List<CouponLifecycleCount> countByLifecycle(CouponCriteria criteria);

    /** 쿠폰 한 장의 사용 내역(최신순). */
    List<CouponUsageRow> usages(Long couponId, int limit);

    /** 정규화된 조회 조건. 값이 null 이면 그 조건은 적용하지 않는다. */
    record CouponCriteria(
            String code,
            String lifecycle,
            String type,
            LocalDateTime from,
            LocalDateTime toExclusive,
            LocalDateTime now) {
    }
}
