package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.OrderExport;
import github.lms.lemuel.partner.application.port.dto.OrderQuery;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderPage;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.application.port.in.ExportPartnerOrdersUseCase;
import github.lms.lemuel.partner.application.port.in.ViewPartnerOrdersUseCase;
import github.lms.lemuel.partner.application.port.out.PartnerSalesQueryPort;
import github.lms.lemuel.partner.domain.PartnerScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 주문(결제) 목록·상세·CSV.
 *
 * <p>세 경로가 모두 같은 조회 포트를 쓰고, 모두 첫 줄에서 {@code scope.requireSellerId()} 를
 * 통과한다. 목록과 CSV 가 서로 다른 쿼리를 쓰면 언젠가 한쪽에만 필터가 빠진다 — 그리고 그건
 * 대개 CSV 쪽이다(눈으로 확인하는 사람이 적다).
 */
@Service
@Transactional(readOnly = true)
public class PartnerOrderService implements ViewPartnerOrdersUseCase, ExportPartnerOrdersUseCase {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter CAPTURED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 맨 앞의 {@code ﻿} 는 UTF-8 BOM 이다. 빼면 엑셀이 이 파일을 CP949 로 읽어 상품명이
     * 전부 깨진다 — 응답에 {@code charset=UTF-8} 을 선언해도 엑셀은 그걸 보지 않는다. 파일이
     * 열리고 숫자도 멀쩡해서 "인코딩 문제" 로 보이지 않고 "데이터가 이상하다" 로 보고된다.
     */
    private static final String CSV_HEADER =
            "﻿결제일시,추정여부,주문번호,결제번호,상품번호,상품명,주문상태,결제수단,결제금액,환불금액,실매출\n";

    private final PartnerSalesQueryPort salesQueryPort;
    private final Clock clock;
    private final int maxExportRows;

    public PartnerOrderService(PartnerSalesQueryPort salesQueryPort,
                               Clock clock,
                               @Value("${app.partner.export.max-rows:50000}") int maxExportRows) {
        this.salesQueryPort = salesQueryPort;
        this.clock = clock;
        this.maxExportRows = maxExportRows;
    }

    @Override
    public PartnerOrderPage orders(PartnerScope scope, OrderQuery query) {
        long sellerId = scope.requireSellerId();
        OrderQuery q = query.normalized(LocalDate.now(clock));

        long total = salesQueryPort.countOrders(sellerId, q.from(), q.to(), q.orderId());
        List<PartnerOrderView> content = total == 0
                // 총건수가 0 이면 두 번째 쿼리는 반드시 빈 결과다. 안 쏘는 게 맞다.
                ? List.of()
                : salesQueryPort.findOrders(sellerId, q.from(), q.to(), q.orderId(),
                        q.size(), (long) q.page() * q.size());

        int totalPages = (int) Math.ceil((double) total / q.size());
        return new PartnerOrderPage(content, q.page(), q.size(), total, totalPages);
    }

    @Override
    public Optional<PartnerOrderView> order(PartnerScope scope, long orderId) {
        long sellerId = scope.requireSellerId();
        // 기간을 열어 두고 주문번호로만 찾는다. 상세 링크는 목록 밖(메일·메모)에서도 열리므로
        // 기본 30일 창을 적용하면 "목록에는 있는데 상세는 없다" 가 된다.
        List<PartnerOrderView> found = salesQueryPort.findOrders(
                sellerId, LocalDate.EPOCH, LocalDate.now(clock).plusDays(1), orderId, 1, 0L);
        return found.stream().findFirst();
    }

    @Override
    public OrderExport export(PartnerScope scope, OrderQuery query) {
        long sellerId = scope.requireSellerId();
        OrderQuery q = query.normalized(LocalDate.now(clock));

        long total = salesQueryPort.countOrders(sellerId, q.from(), q.to(), q.orderId());
        int take = (int) Math.min(total, maxExportRows);
        List<PartnerOrderView> rows = take == 0
                ? List.of()
                : salesQueryPort.findOrders(sellerId, q.from(), q.to(), q.orderId(), take, 0L);

        StringBuilder body = new StringBuilder(CSV_HEADER);
        for (PartnerOrderView row : rows) {
            appendRow(body, row);
        }

        String filename = "partner-orders_%s_%s-%s.csv".formatted(
                sellerId, q.from().format(FILE_STAMP), q.to().format(FILE_STAMP));
        return OrderExport.of(filename, body.toString(), total, rows.size());
    }

    private static void appendRow(StringBuilder out, PartnerOrderView row) {
        out.append(row.capturedAt() == null ? "" : row.capturedAt().format(CAPTURED_AT)).append(',')
                .append(row.capturedAtEstimated() ? "추정" : "").append(',')
                .append(row.orderId()).append(',')
                .append(row.paymentId()).append(',')
                .append(row.productId() == null ? "" : row.productId()).append(',')
                .append(csv(row.productName())).append(',')
                .append(csv(row.orderStatus())).append(',')
                .append(csv(row.paymentMethod())).append(',')
                .append(plain(row.amount())).append(',')
                .append(plain(row.refundedAmount())).append(',')
                .append(plain(row.netAmount())).append('\n');
    }

    /**
     * CSV 한 칸.
     *
     * <p>선행 {@code = + - @} 를 따옴표 안에 넣고 앞에 작은따옴표를 붙인다 — 엑셀은 그런 문자로
     * 시작하는 칸을 <b>수식으로 실행</b>한다(CSV 인젝션). 이 CSV 의 문자열 칸은 상품명·주문상태·
     * 결제수단이고 셋 다 다른 서비스가 만든 값이라, 우리 쪽에서 안전하다고 가정할 근거가 없다.
     */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        char head = escaped.charAt(0);
        if (head == '=' || head == '+' || head == '-' || head == '@') {
            escaped = "'" + escaped;
        }
        return '"' + escaped + '"';
    }

    /** 금액은 지수표기 없이 평문으로 — {@code 1E+5} 가 찍히면 엑셀에서 숫자로 안 읽힌다. */
    private static String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.toPlainString();
    }
}
