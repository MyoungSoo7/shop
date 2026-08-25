package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase;
import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort.SalesCriteria;
import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 판매 통계 서비스 — 기간·상태 정규화가 이 계층의 일이다.
 *
 * <p><b>기간에 기본값을 두는 이유</b>({@link SearchOrdersService} 와 반대인 이유):
 * 주문 조회는 "언제 들어왔는지 모르는 주문 한 건"을 찾는 일이라 몰래 깐 기간이 곧 빈 화면이
 * 된다. 통계는 반대로 <b>기간 자체가 질문</b>이다 — 기간 없는 판매 랭킹은 개점 이래 전체 집계라
 * 최근 흐름을 전혀 보여 주지 않으면서 테이블 전체를 훑는다. 기본값을 두되 응답에 실어 보내
 * 화면이 무엇을 보고 있는지 말할 수 있게 한다.
 *
 * <p><b>상태 기본값이 "전부"가 아닌 이유</b>: 결제도 안 된 주문(CREATED)과 이미 환불한 주문이
 * 판매 실적에 섞이면 매출이 실제보다 크게 나온다. 그 오차는 화면 어디에도 표시되지 않으므로
 * 운영자가 알아챌 방법이 없다. 기본은 <b>돈이 아직 우리 쪽에 있는 상태</b>다.
 */
@Service
public class SalesStatsService implements ViewSalesStatsUseCase {

    /** 기간을 지정하지 않았을 때 볼 일수(오늘 포함). */
    public static final int DEFAULT_RANGE_DAYS = 30;

    /** 한 번에 볼 수 있는 최대 일수. 넘으면 거부한다 — 조용히 잘라내면 부분 집계를 전체로 오인한다. */
    public static final int MAX_RANGE_DAYS = 366;

    /** 랭킹 기본 행 수. */
    public static final int DEFAULT_LIMIT = 20;

    /** 랭킹 최대 행 수. */
    public static final int MAX_LIMIT = 200;

    /**
     * 기본 집계 대상 상태 — <b>돈이 아직 우리 쪽에 있는</b> 주문.
     *
     * <p>취소·환불 <i>요청</i> 상태를 포함하는 이유는 그 시점에 아직 아무것도 되돌려주지 않았기
     * 때문이다. 승인({@code CANCELLATION_APPROVED})부터가 반환이 확정된 지점이라 거기서 뺀다.
     * 라인 단위 부분 취소는 상태와 무관하게 {@code canceled_at} 으로 이미 빠진다.
     */
    public static final List<OrderStatus> DEFAULT_STATUSES = List.of(
            OrderStatus.PAID,
            OrderStatus.SHIPPING_PENDING,
            OrderStatus.IN_TRANSIT,
            OrderStatus.DELIVERED,
            OrderStatus.CANCELLATION_REQUESTED,
            OrderStatus.REFUND_REQUESTED);

    private final LoadSalesStatsPort salesPort;
    private final Clock clock;

    public SalesStatsService(LoadSalesStatsPort salesPort, Clock clock) {
        this.salesPort = salesPort;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductRanking topProducts(SalesQuery query) {
        Resolved resolved = resolve(query);
        int limit = normalizeLimit(query.limit());

        List<ProductSales> rows = salesPort.topProducts(resolved.criteria(), limit);
        SalesTotal total = salesPort.total(resolved.criteria());

        return new ProductRanking(resolved.from(), resolved.to(), resolved.statuses(), limit, rows, total);
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryBreakdown byCategory(SalesQuery query) {
        Resolved resolved = resolve(query);

        List<CategorySales> rows = salesPort.byCategory(resolved.criteria());
        SalesTotal total = salesPort.total(resolved.criteria());

        return new CategoryBreakdown(resolved.from(), resolved.to(), resolved.statuses(), rows, total);
    }

    /**
     * 질의를 확정된 기간·상태·조건으로 옮긴다.
     *
     * <p>랭킹과 합계가 <b>같은 {@link SalesCriteria} 인스턴스</b>를 쓰도록 한 번만 만든다.
     * 두 번 만들면 그 사이에 자정이 지나는 순간 "오늘"의 뜻이 달라져 상위 N개의 합이 전체
     * 합계를 넘는 화면이 나온다 — 재현도 안 되고 원인도 안 보인다.
     */
    private Resolved resolve(SalesQuery query) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = query.to() != null ? query.to() : today;
        LocalDate from = query.from() != null ? query.from() : to.minusDays(DEFAULT_RANGE_DAYS - 1L);

        if (from.isAfter(to)) {
            // 뒤집힌 기간을 조용히 바로잡지 않는다. 주문 조회에서는 달력 오조작이라 바로잡는
            // 편이 낫지만, 통계는 그 숫자가 보고서로 나간다 — 요청한 기간과 다른 기간의 매출을
            // 돌려주면 운영자는 자기가 무엇을 보고 있는지 모른 채 그 값을 인용한다.
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다: " + from + " ~ " + to);
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "조회 기간은 최대 " + MAX_RANGE_DAYS + "일입니다 (요청: " + days + "일)");
        }

        List<String> statuses = resolveStatuses(query.statuses());
        SalesCriteria criteria = new SalesCriteria(
                statuses, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        return new Resolved(from, to, statuses, criteria);
    }

    /**
     * 상태 이름을 정규화한다. 모르는 값은 <b>던진다</b>.
     *
     * <p>조용히 버리면 {@code statuses=PAYED} 오타 하나가 "그 상태만 집계"가 아니라
     * "아무 상태도 집계 안 함"이 되어 매출 0 원이 나온다. 0 원은 장사가 안 된 것처럼 보이지
     * 실패처럼 보이지 않는다.
     */
    private static List<String> resolveStatuses(List<String> requested) {
        if (requested == null || requested.stream().allMatch(s -> s == null || s.isBlank())) {
            return DEFAULT_STATUSES.stream().map(Enum::name).toList();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            normalized.add(OrderStatus.fromString(raw).name());
        }
        return List.copyOf(normalized);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 확정된 조회 조건 묶음. */
    private record Resolved(LocalDate from, LocalDate to, List<String> statuses, SalesCriteria criteria) {
    }
}
