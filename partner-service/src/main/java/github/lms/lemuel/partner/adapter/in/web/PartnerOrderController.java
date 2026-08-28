package github.lms.lemuel.partner.adapter.in.web;

import github.lms.lemuel.partner.application.port.dto.OrderExport;
import github.lms.lemuel.partner.application.port.dto.OrderQuery;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderPage;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.application.port.in.ExportPartnerOrdersUseCase;
import github.lms.lemuel.partner.application.port.in.ResolvePartnerScopeUseCase;
import github.lms.lemuel.partner.application.port.in.ViewPartnerOrdersUseCase;
import github.lms.lemuel.partner.domain.PartnerScope;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 주문·결제 내역 목록, 단건, CSV 내려받기. */
@RestController
@RequestMapping("/api/partner")
@RequiredArgsConstructor
public class PartnerOrderController {

    /**
     * 잘린 내려받기임을 알리는 응답 헤더.
     *
     * <p>레퍼런스 백오피스는 무제한이라 큰 기간을 고르면 백오피스가 통째로 멎었다. 여기서는
     * 자르되 <b>잘랐다는 사실을 반드시 알린다</b> — 조용히 자르면 파트너는 그 CSV 를 전부라고
     * 믿고 회계에 쓴다. 파일이 열리고 숫자가 들어 있으므로 틀렸다는 신호가 어디에도 없다.
     */
    private static final String TRUNCATED_HEADER = "X-Partner-Export-Truncated";
    private static final String TOTAL_HEADER = "X-Partner-Export-Total";

    private final ResolvePartnerScopeUseCase resolveScope;
    private final ViewPartnerOrdersUseCase viewOrders;
    private final ExportPartnerOrdersUseCase exportOrders;

    @GetMapping("/orders")
    public PartnerOrderPage orders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return viewOrders.orders(scope(), new OrderQuery(from, to, orderId, page, size));
    }

    /**
     * 단건. 조회 기간에 매이지 않는다 — 목록에서 넘어온 링크를 나중에 다시 열었을 때
     * 기본 30일 밖이라는 이유로 404 가 나면, 존재하는 주문이 사라진 것처럼 보인다.
     */
    @GetMapping("/orders/{orderId}")
    public PartnerOrderView order(@PathVariable long orderId) {
        return viewOrders.order(scope(), orderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    @GetMapping("/exports/orders")
    public ResponseEntity<byte[]> exportOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long orderId) {
        OrderExport export = exportOrders.export(scope(), new OrderQuery(from, to, orderId, 0, 0));

        HttpHeaders headers = new HttpHeaders();
        // text/csv + UTF-8. 엑셀이 BOM 없는 UTF-8 CSV 를 CP949 로 읽어 한글이 깨지는 문제는
        // 서비스에서 BOM 을 앞에 붙여 해결한다(여기서 charset 만 선언해도 엑셀은 안 본다).
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build());
        headers.set(TOTAL_HEADER, String.valueOf(export.totalMatched()));
        headers.set(TRUNCATED_HEADER, String.valueOf(export.truncated()));
        return ResponseEntity.ok().headers(headers).body(export.csv());
    }

    private PartnerScope scope() {
        return resolveScope.resolve(CurrentPartnerUser.requireUserId());
    }
}
