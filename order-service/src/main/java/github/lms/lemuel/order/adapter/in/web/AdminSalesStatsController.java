package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.CategoryBreakdown;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.ProductRanking;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.SalesQuery;
import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.ProductSales;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * 판매 통계 콘솔 — 무엇이 팔렸는가.
 *
 * <pre>
 *   GET /admin/sales/products            → 상품 판매 랭킹(순액 내림차순 상위 N)
 *   GET /admin/sales/products/export     → 같은 조건의 CSV
 *   GET /admin/sales/categories          → 카테고리별 분포(미분류 포함, 잘라내기 없음)
 *   GET /admin/sales/categories/export   → 같은 조건의 CSV
 * </pre>
 *
 * <p><b>왜 필요한가</b>: 기존 관리자 통계는 {@code /orders/admin/summary}(주문 상태별 건수·금액)와
 * 운영 대시보드의 오늘 카운터뿐이었다. 둘 다 세는 단위가 <b>주문</b>이라 "무엇이 팔렸는가"에는
 * 답하지 못한다 — 주문 한 건에 상품 다섯 개가 들어 있어도 그 다섯을 구분할 축이 없기 때문이다.
 * 매입·재고·프로모션 판단은 전부 상품·카테고리 축에서 이뤄지는데, 그 축이 통째로 없었다.
 *
 * <p><b>{@code /orders/admin/summary} 와 숫자가 다른 것은 정상이다</b>: 그쪽은 {@code orders.amount}
 * (배송비 포함)를 더하고 여기는 라인 순액({@code line_amount - allocated_discount}, 배송비 제외)을
 * 더한다. 두 값을 억지로 맞추면 어느 한쪽이 거짓말을 하게 된다. 응답에 기간·상태를 함께 실어
 * 보내는 이유도 같다 — 화면이 자기가 무엇을 세고 있는지 말할 수 있어야 한다.
 *
 * <p><b>권한</b>: SecurityConfig 의 {@code /admin/sales/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 없어서, 매처를 빠뜨리면 {@code anyRequest().authenticated()} 로
 * 새어 <b>로그인만 한 사용자가 전사 매출을 읽는다</b>. MANAGER 에게도 열지 않는다 — 리뷰·환불
 * 콘솔과 달리 이건 CS 업무가 아니라 경영 정보다.
 *
 * <p><b>감사 로그를 남기지 않는 이유</b>: 여기서 나가는 것은 개인정보가 아니라 집계값이고, 행에
 * 사람이 없다. 운영자 명부 CSV 를 감사에 남긴 것은 그 파일이 <i>계정 목록</i>이었기 때문이다.
 * 통계 조회까지 남기면 감사 로그가 일상 조회로 덮여 정작 봐야 할 조작이 묻힌다.
 */
@Tag(name = "Admin Sales Stats", description = "상품 판매 랭킹 · 카테고리별 분포")
@RestController
@RequestMapping("/admin/sales")
@RequiredArgsConstructor
public class AdminSalesStatsController {

    private final ViewSalesStatsUseCase viewSalesStatsUseCase;

    @GetMapping("/products")
    @Operation(summary = "상품 판매 랭킹",
            description = "라인 순액 내림차순 상위 N. 기간 미지정 시 최근 30일, 상태 미지정 시 결제가 살아 있는 상태")
    public ResponseEntity<ProductRanking> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) Integer limit) {

        return ResponseEntity.ok(viewSalesStatsUseCase.topProducts(new SalesQuery(from, to, statuses, limit)));
    }

    /**
     * 랭킹 CSV.
     *
     * <p>{@code X-Export-Truncated} 를 붙이는 이유: 랭킹은 <b>본래</b> 잘라낸 목록이라, 받은 사람이
     * 그 사실을 모른 채 열 합계를 내면 "전체 매출"이라고 믿게 된다. 전 범위 합계를
     * {@code X-Export-Net-Total} 로 같이 보내 그 비교가 가능하게 한다.
     */
    @GetMapping("/products/export")
    @Operation(summary = "상품 판매 랭킹 CSV", description = "화면과 같은 조건. 상위 N 만 담기므로 헤더로 전체 합계를 함께 보낸다")
    public ResponseEntity<ByteArrayResource> exportProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) Integer limit) {

        ProductRanking ranking = viewSalesStatsUseCase.topProducts(new SalesQuery(from, to, statuses, limit));

        // 순위는 행의 속성이 아니라 위치라, 행을 하나씩 보는 매퍼로는 알 수 없다. 먼저 인덱스를
        // 붙여 셀을 만들어 두고 매퍼는 그대로 흘려보낸다 — 목록에서 위치를 되찾는(indexOf) 방식은
        // 값이 같은 행이 생기는 순간 조용히 틀린 순위를 매긴다.
        List<List<String>> cells = IntStream.range(0, ranking.rows().size())
                .mapToObj(i -> toProductCells(i + 1, ranking.rows().get(i)))
                .toList();

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "sales_products",
                List.of("순위", "상품ID", "상품명", "수량", "순매출", "주문수"),
                cells,
                Function.identity());

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(ranking.rows().size() >= ranking.limit()))
                .header("X-Export-Net-Total", Objects.toString(ranking.total().netAmount(), "0"))
                .header("X-Export-Range", ranking.from() + "~" + ranking.to())
                .body(csv.getBody());
    }

    @GetMapping("/categories")
    @Operation(summary = "카테고리별 판매 분포",
            description = "대표 분류 기준. 잘라내지 않으며 대표 분류가 없는 상품은 '미분류' 한 줄로 나온다")
    public ResponseEntity<CategoryBreakdown> byCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> statuses) {

        return ResponseEntity.ok(viewSalesStatsUseCase.byCategory(new SalesQuery(from, to, statuses, null)));
    }

    /**
     * 카테고리 CSV.
     *
     * <p>여기서는 잘라내기가 없으므로 {@code X-Export-Truncated} 를 붙이지 않는다. 대신 행의
     * 순매출 합이 {@code X-Export-Net-Total} 과 <b>정확히 같아야 한다</b> — 다르다면 미분류 줄이
     * 빠졌거나 한 라인이 여러 분류로 중복 계산된 것이다.
     */
    @GetMapping("/categories/export")
    @Operation(summary = "카테고리별 판매 CSV", description = "화면과 같은 조건. 행 합계가 전체 합계와 일치해야 한다")
    public ResponseEntity<ByteArrayResource> exportCategories(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> statuses) {

        CategoryBreakdown breakdown = viewSalesStatsUseCase.byCategory(new SalesQuery(from, to, statuses, null));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "sales_categories",
                List.of("카테고리ID", "카테고리명", "경로", "깊이", "수량", "순매출", "주문수"),
                breakdown.rows(),
                AdminSalesStatsController::toCategoryCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Net-Total", Objects.toString(breakdown.total().netAmount(), "0"))
                .header("X-Export-Range", breakdown.from() + "~" + breakdown.to())
                .body(csv.getBody());
    }

    private static List<String> toProductCells(int rank, ProductSales row) {
        return List.of(
                String.valueOf(rank),
                Objects.toString(row.productId(), ""),
                Objects.toString(row.productName(), ""),
                String.valueOf(row.quantity()),
                Objects.toString(row.netAmount(), "0"),
                String.valueOf(row.orderCount()));
    }

    /**
     * 미분류 줄은 ID·경로·깊이를 비운 채 이름만 채운다.
     *
     * <p>빈 칸이 아니라 <b>이름</b>을 넣는 이유: CSV 를 받은 사람에게 빈 이름은 "값이 없다"가
     * 아니라 "읽다 만 파일"로 보인다. 그 줄이야말로 팔리는데 분류가 없는 상품이라 가장 먼저
     * 눈에 띄어야 한다.
     */
    private static List<String> toCategoryCells(CategorySales row) {
        return List.of(
                Objects.toString(row.categoryId(), ""),
                row.unclassified() ? "미분류" : Objects.toString(row.categoryName(), ""),
                Objects.toString(row.pathSlug(), ""),
                Objects.toString(row.depth(), ""),
                String.valueOf(row.quantity()),
                Objects.toString(row.netAmount(), "0"),
                String.valueOf(row.orderCount()));
    }
}
