package github.lms.lemuel.coupon.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 쿠폰 운영 콘솔 조회 유스케이스.
 *
 * <p><b>왜 필요한가</b>: 관리자 조회라곤 {@code GET /coupons}(전체를 페이징 없이 한 번에)뿐이었다.
 * 쿠폰이 수백 장을 넘기면 응답이 커지다 어느 날 터지고, 그 전까지도 "지금 살아 있는 쿠폰이
 * 무엇인지", "어느 쿠폰이 다 소진됐는지"를 화면에서 알 수 없다.
 *
 * <p><b>수명 상태를 서버가 계산하는 이유</b>: 화면이 {@code isActive}·{@code expiresAt}·
 * {@code usedCount/maxUses} 셋을 조합해 "만료됨"을 판정하면, 같은 판정이 화면마다 조금씩
 * 달라진다. 소진·만료·예정은 <b>하나의 축</b>이므로 서버가 한 번 계산해 내려보낸다.
 */
public interface SearchCouponsUseCase {

    /** 조건에 맞는 쿠폰을 최신 생성순 페이지로 조회한다. */
    CouponPage search(CouponQuery query);

    /** 같은 조건의 수명 상태별 장수. */
    List<CouponLifecycleCount> countByLifecycle(CouponQuery query);

    /** 같은 조건의 내보내기용 목록(상한 있음). */
    CouponExport export(CouponQuery query);

    /** 쿠폰 한 장의 사용 내역(최신순). 회수된 이력도 함께 보여 준다. */
    List<CouponUsageRow> usages(Long couponId, int limit);

    /**
     * 쿠폰 수명 상태 — {@code is_active}·기간·소진을 한 축으로 합친 판정.
     *
     * <p>우선순위가 있다: 꺼져 있으면 기간·소진과 무관하게 INACTIVE 다. 운영자가 손으로 끈
     * 쿠폰을 "만료됨"으로 보여 주면 왜 안 나가는지 오해하게 된다.
     */
    enum CouponLifecycle {
        /** 운영자가 껐다. */
        INACTIVE,
        /** 시작 전. */
        SCHEDULED,
        /** 기간이 지났다. */
        EXPIRED,
        /** 발급 한도를 다 썼다. */
        EXHAUSTED,
        /** 지금 쓸 수 있다. */
        ACTIVE
    }

    /**
     * 조회 조건.
     *
     * @param code      코드 부분일치(대소문자 무시). 공백/null 이면 미적용
     * @param lifecycle 수명 상태. null 이면 전체
     * @param type      할인 유형(FIXED/PERCENTAGE) 정확일치. 공백/null 이면 미적용
     * @param from      생성일 시작(포함). null 이면 미적용
     * @param to        생성일 종료(포함). null 이면 미적용
     */
    record CouponQuery(
            String code,
            CouponLifecycle lifecycle,
            String type,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
    }

    /** 한 페이지. */
    record CouponPage(
            List<CouponRow> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /** 목록 한 줄. */
    record CouponRow(
            Long id,
            String code,
            String type,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscountAmount,
            int maxUses,
            int usedCount,
            String targetType,
            Long targetId,
            LocalDateTime startsAt,
            LocalDateTime expiresAt,
            boolean active,
            String lifecycle,
            LocalDateTime createdAt) {
    }

    /** 수명 상태별 장수. */
    record CouponLifecycleCount(String lifecycle, long count) {
    }

    /** 사용 내역 한 줄. {@code revokedAt} 이 있으면 주문 취소·환불로 되돌려진 이력이다. */
    record CouponUsageRow(
            Long id,
            Long userId,
            String userEmail,
            Long orderId,
            LocalDateTime usedAt,
            LocalDateTime revokedAt,
            String revokeReason) {
    }

    /** 내보내기 결과. */
    record CouponExport(List<CouponRow> rows, boolean truncated, long totalElements) {
    }
}
