package github.lms.lemuel.coupon.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.coupon.application.port.in.ManageCouponUseCase;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponExport;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycle;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycleCount;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponPage;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponQuery;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponRow;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponUsageRow;
import github.lms.lemuel.coupon.adapter.in.web.dto.CouponResponse;
import github.lms.lemuel.coupon.domain.CouponType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 쿠폰 운영 콘솔.
 *
 * <pre>
 *   GET  /admin/coupons                       → 조건 검색(생성 최신순 페이지)
 *   GET  /admin/coupons/lifecycle-counts      → 같은 조건의 수명 상태별 장수
 *   GET  /admin/coupons/enums                 → 필터 드롭다운용 상태·유형 목록
 *   GET  /admin/coupons/{couponId}/usages     → 쿠폰 한 장의 사용 내역(회수 이력 포함)
 *   GET  /admin/coupons/export                → 같은 조건의 CSV
 *   POST /admin/coupons/{code}/activate       → 즉시 재개
 *   POST /admin/coupons/{code}/deactivate     → 즉시 중단
 * </pre>
 *
 * <p><b>왜 필요한가</b>: 관리자 조회라곤 {@code GET /coupons}(전체 무페이징)뿐이었고,
 * {@code Coupon.deactivate()} 는 도메인에만 있고 부르는 경로가 없었다. 잘못 나간 쿠폰을 멈추는
 * 유일한 방법이 DB 직접 UPDATE 였다는 뜻이다 — 할인은 나가는 돈이라 그 상태 자체가 사고다.
 *
 * <p><b>생성은 여기 두지 않았다</b>: {@code POST /coupons} 가 이미 그 일을 한다. 같은 조작을 두
 * 표면에 두면 언젠가 한쪽만 고쳐진다. 대신 그 엔드포인트에 <b>빠져 있던 권한 매처를 채웠다</b> —
 * 그전에는 매처가 없어 {@code anyRequest().authenticated()} 로 새어, 로그인한 아무나 자기에게
 * 100% 할인 쿠폰을 만들 수 있었다.
 *
 * <p><b>삭제를 제공하지 않는 이유</b>: 이미 사용된 쿠폰을 지우면 사용 이력의 참조가 끊기고
 * 정산에서 그 할인이 어디서 왔는지 설명할 수 없게 된다. 끄는 것으로 충분하다.
 */
@Tag(name = "Admin Coupon", description = "쿠폰 검색 · 중단/재개 · 사용 내역")
@RestController
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponController {

    private final SearchCouponsUseCase searchCouponsUseCase;
    private final ManageCouponUseCase manageCouponUseCase;

    @GetMapping
    @Operation(summary = "쿠폰 검색", description = "코드·수명상태·할인유형·생성일로 좁혀 최신순 조회")
    public ResponseEntity<CouponPage> search(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchCouponsUseCase.search(
                toQuery(code, lifecycle, type, from, to, page, size)));
    }

    @GetMapping("/lifecycle-counts")
    @Operation(summary = "수명 상태별 장수", description = "살아 있는 쿠폰이 몇 장인지는 목록을 세어 알 일이 아니다")
    public ResponseEntity<List<CouponLifecycleCount>> lifecycleCounts(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        // 상태별 집계에 상태 필터를 걸면 고른 상태 하나만 남아 집계의 의미가 사라진다.
        return ResponseEntity.ok(searchCouponsUseCase.countByLifecycle(
                toQuery(code, null, type, from, to, 0, 1)));
    }

    @GetMapping("/enums")
    @Operation(summary = "수명 상태 · 할인 유형 목록", description = "필터 드롭다운용 — 서버 enum 이 정본이다")
    public ResponseEntity<CouponEnums> enums() {
        return ResponseEntity.ok(new CouponEnums(
                Arrays.stream(CouponLifecycle.values()).map(Enum::name).toList(),
                Arrays.stream(CouponType.values()).map(Enum::name).toList()));
    }

    /**
     * 쿠폰 한 장의 사용 내역.
     *
     * <p>회수된 이력({@code revokedAt})도 함께 보여 준다. "사용 100건인데 usedCount 가 90"처럼
     * 숫자가 어긋나 보일 때, 회수 이력이 없으면 버그인지 정상인지 판단할 수 없다.
     */
    @GetMapping("/{couponId}/usages")
    @Operation(summary = "쿠폰 사용 내역", description = "누가 언제 썼는지 + 주문 취소로 되돌려진 이력")
    public ResponseEntity<List<CouponUsageRow>> usages(@PathVariable Long couponId,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(searchCouponsUseCase.usages(couponId, limit));
    }

    @GetMapping("/export")
    @Operation(summary = "쿠폰 CSV", description = "화면과 같은 조건으로 최대 5000행")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        CouponExport exported = searchCouponsUseCase.export(
                toQuery(code, lifecycle, type, from, to, 0, 1));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "coupons",
                List.of("ID", "코드", "유형", "할인값", "최소주문액", "할인상한", "사용/한도",
                        "적용대상", "시작", "만료", "상태", "생성일시"),
                exported.rows(),
                AdminCouponController::toCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(exported.truncated()))
                .header("X-Export-Total", String.valueOf(exported.totalElements()))
                .body(csv.getBody());
    }

    @PostMapping("/{code}/deactivate")
    @Operation(summary = "쿠폰 중단", description = "즉시 사용 불가로 만든다. 이미 꺼져 있으면 그대로 둔다")
    public ResponseEntity<CouponResponse> deactivate(@PathVariable String code) {
        return ResponseEntity.ok(CouponResponse.from(manageCouponUseCase.deactivate(code)));
    }

    @PostMapping("/{code}/activate")
    @Operation(summary = "쿠폰 재개", description = "다시 사용 가능하게 한다. 기간·소진 조건은 그대로 적용된다")
    public ResponseEntity<CouponResponse> activate(@PathVariable String code) {
        return ResponseEntity.ok(CouponResponse.from(manageCouponUseCase.activate(code)));
    }

    private static List<String> toCells(CouponRow row) {
        return List.of(
                Objects.toString(row.id(), ""),
                Objects.toString(row.code(), ""),
                Objects.toString(row.type(), ""),
                Objects.toString(row.discountValue(), ""),
                Objects.toString(row.minOrderAmount(), ""),
                Objects.toString(row.maxDiscountAmount(), ""),
                row.usedCount() + "/" + (row.maxUses() > 0 ? String.valueOf(row.maxUses()) : "무제한"),
                Objects.toString(row.targetType(), "")
                        + (row.targetId() == null ? "" : ":" + row.targetId()),
                Objects.toString(row.startsAt(), ""),
                Objects.toString(row.expiresAt(), ""),
                Objects.toString(row.lifecycle(), ""),
                Objects.toString(row.createdAt(), ""));
    }

    /** 모르는 상태 이름은 필터 미적용으로 흘린다 — 오타 하나가 목록을 통째로 비우면 안 된다. */
    private static CouponQuery toQuery(String code, String lifecycle, String type,
                                       LocalDate from, LocalDate to, int page, int size) {
        CouponLifecycle parsed = null;
        if (lifecycle != null && !lifecycle.isBlank()) {
            try {
                parsed = CouponLifecycle.valueOf(lifecycle.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase();
        return new CouponQuery(code, parsed, normalizedType, from, to, page, size);
    }

    /** 필터 드롭다운 목록. */
    public record CouponEnums(List<String> lifecycles, List<String> types) {
    }
}
