package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase.BackfillReport;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase.SignatureBackfillReport;
import github.lms.lemuel.product.application.port.in.ManageOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.adapter.in.web.response.OptionAxisResponse;
import github.lms.lemuel.product.adapter.in.web.response.OptionAxisValueResponse;
import github.lms.lemuel.product.domain.OptionInputType;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 옵션 카탈로그 관리 API — 표준 축 조회와 레거시 표시명 백필.
 *
 * <p>백필은 되돌릴 필요가 없도록 설계되어 있다(기존 데이터 무변경 + 멱등). 그래도 운영 데이터를
 * 대량으로 만드는 경로이므로 ADMIN 으로 제한한다.
 */
@Tag(name = "Admin Option Catalog", description = "옵션 축/값 카탈로그 관리 API")
@RestController
@RequestMapping("/admin/option-catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOptionCatalogController {

    private final BackfillOptionCatalogUseCase backfillUseCase;
    private final BackfillVariantSignatureUseCase signatureUseCase;
    private final LoadOptionCatalogPort loadCatalogPort;
    private final ManageOptionCatalogUseCase manageUseCase;

    public AdminOptionCatalogController(BackfillOptionCatalogUseCase backfillUseCase,
                                        BackfillVariantSignatureUseCase signatureUseCase,
                                        LoadOptionCatalogPort loadCatalogPort,
                                        ManageOptionCatalogUseCase manageUseCase) {
        this.backfillUseCase = backfillUseCase;
        this.signatureUseCase = signatureUseCase;
        this.loadCatalogPort = loadCatalogPort;
        this.manageUseCase = manageUseCase;
    }

    /** 축 생성 요청. 코드는 만든 뒤 바꿀 수 없다 — SKU 매핑이 코드가 아니라 id 로 묶이는 전제다. */
    public record CreateAxisRequest(String code, String name, OptionInputType inputType) {
    }

    public record UpdateAxisRequest(String name, OptionInputType inputType) {
    }

    public record AddValueRequest(String code, String name, String swatchHex, Integer sortOrder) {
    }

    public record UpdateValueRequest(String name, String swatchHex, Integer sortOrder) {
    }

    @Operation(summary = "표준 옵션 축 목록", description = "등록된 표준 옵션 축을 코드순으로 조회한다.")
    @GetMapping("/axes")
    public ResponseEntity<List<OptionAxisResponse>> getAxes() {
        return ResponseEntity.ok(loadCatalogPort.loadAllAxes().stream()
                .map(OptionAxisResponse::from)
                .toList());
    }

    @Operation(summary = "표준 옵션 축 생성", description = "코드가 이미 있으면 400 — 같은 축이 두 벌이면 파셋 집계가 갈라진다.")
    @PostMapping("/axes")
    public ResponseEntity<OptionAxisResponse> createAxis(@RequestBody CreateAxisRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OptionAxisResponse.from(
                manageUseCase.createAxis(request.code(), request.name(), request.inputType())));
    }

    @Operation(summary = "표준 옵션 축 수정", description = "표시 이름·표현 방식만 바꾼다. 코드는 불변이다.")
    @PatchMapping("/axes/{code}")
    public ResponseEntity<OptionAxisResponse> updateAxis(
            @Parameter(description = "축 코드", required = true) @PathVariable String code,
            @RequestBody UpdateAxisRequest request) {
        return ResponseEntity.ok(OptionAxisResponse.from(
                manageUseCase.updateAxis(code, request.name(), request.inputType())));
    }

    @Operation(summary = "표준 옵션 축 활성/비활성",
            description = "카탈로그에서 감추는 표시일 뿐이다 — 이미 그 값을 파는 상품의 판매를 멈추지 않는다.")
    @PatchMapping("/axes/{code}/active")
    public ResponseEntity<OptionAxisResponse> setAxisActive(@PathVariable String code,
                                                            @RequestParam boolean active) {
        return ResponseEntity.ok(OptionAxisResponse.from(manageUseCase.setAxisActive(code, active)));
    }

    @Operation(summary = "축의 표준 값 목록", description = "정렬 순서대로. 비활성 값도 포함한다(운영 화면이 되살릴 수 있어야 한다).")
    @GetMapping("/axes/{code}/values")
    public ResponseEntity<List<OptionAxisValueResponse>> getValues(@PathVariable String code) {
        return ResponseEntity.ok(manageUseCase.getValues(code).stream()
                .map(OptionAxisValueResponse::from)
                .toList());
    }

    @Operation(summary = "축에 표준 값 추가", description = "SWATCH 축은 표시색이 필수, TEXT 축은 표준값을 갖지 않는다.")
    @PostMapping("/axes/{code}/values")
    public ResponseEntity<OptionAxisValueResponse> addValue(@PathVariable String code,
                                                            @RequestBody AddValueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OptionAxisValueResponse.from(
                manageUseCase.addValue(code, request.code(), request.name(), request.swatchHex(),
                        request.sortOrder() == null ? 0 : request.sortOrder())));
    }

    @Operation(summary = "표준 값 수정", description = "이름·표시색·정렬만 바꾼다. 값 코드는 불변이다 — SKU 매핑이 걸려 있다.")
    @PatchMapping("/axes/{code}/values/{valueCode}")
    public ResponseEntity<OptionAxisValueResponse> updateValue(@PathVariable String code,
                                                               @PathVariable String valueCode,
                                                               @RequestBody UpdateValueRequest request) {
        return ResponseEntity.ok(OptionAxisValueResponse.from(
                manageUseCase.updateValue(code, valueCode, request.name(), request.swatchHex(),
                        request.sortOrder() == null ? 0 : request.sortOrder())));
    }

    @Operation(summary = "표준 값 활성/비활성")
    @PatchMapping("/axes/{code}/values/{valueCode}/active")
    public ResponseEntity<OptionAxisValueResponse> setValueActive(@PathVariable String code,
                                                                   @PathVariable String valueCode,
                                                                   @RequestParam boolean active) {
        return ResponseEntity.ok(OptionAxisValueResponse.from(
                manageUseCase.setValueActive(code, valueCode, active)));
    }

    @Operation(summary = "옵션 카탈로그 백필",
            description = "product_variants.option_name 을 파싱해 축/값 카탈로그를 역생성한다. 멱등 — 재실행해도 안전하다.")
    @PostMapping("/backfill")
    public ResponseEntity<BackfillReport> backfill(
            @Parameter(description = "특정 상품만 처리할 경우 상품 ID (생략 시 전체)")
            @RequestParam(required = false) Long productId) {
        BackfillReport report = productId == null
                ? backfillUseCase.backfillAll()
                : backfillUseCase.backfillProduct(productId);
        return ResponseEntity.ok(report);
    }

    @Operation(summary = "SKU 조합 매핑·서명 백필",
            description = "SKU 를 옵션 값에 매핑하고 조합 서명을 부여한다. 카탈로그 백필이 선행되어야 하며 멱등이다.")
    @PostMapping("/backfill-signatures")
    public ResponseEntity<SignatureBackfillReport> backfillSignatures(
            @Parameter(description = "특정 상품만 처리할 경우 상품 ID (생략 시 전체)")
            @RequestParam(required = false) Long productId) {
        SignatureBackfillReport report = productId == null
                ? signatureUseCase.backfillAll()
                : signatureUseCase.backfillProduct(productId);
        return ResponseEntity.ok(report);
    }
}
