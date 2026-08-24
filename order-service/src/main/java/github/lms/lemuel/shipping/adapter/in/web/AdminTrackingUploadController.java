package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase;
import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase.BulkTrackingResult;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 송장 일괄 업로드 콘솔.
 *
 * <pre>
 *   POST /admin/shipments/tracking-upload  (multipart: file)              → 미리보기(무변경)
 *   POST /admin/shipments/tracking-upload?dryRun=false                    → 실제 출고 처리
 *
 *   order_id,carrier,tracking_number
 *   7,CJ,1234567890
 * </pre>
 *
 * <p>수백 행이 한 번에 반영되는 작업이라 <b>미리보기가 기본값</b>이다 — 파라미터를 빠뜨린 호출이
 * 곧바로 출고 처리가 되어선 안 된다. 미리보기에서 거절된 행을 고쳐 다시 올리는 것이 정상 흐름이다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/shipments/**} 매처(ADMIN/MANAGER)로 제한된다.
 */
@RestController
@RequestMapping("/admin/shipments")
public class AdminTrackingUploadController {

    private final TrackingNumberCsvParser parser;
    private final RegisterTrackingNumbersUseCase useCase;

    public AdminTrackingUploadController(TrackingNumberCsvParser parser,
                                         RegisterTrackingNumbersUseCase useCase) {
        this.parser = parser;
        this.useCase = useCase;
    }

    @Operation(summary = "송장 일괄 등록 (CSV)",
            description = "dryRun 기본 true — 실제 출고는 dryRun=false 를 명시해야 한다. "
                    + "행별 통과/사유를 함께 돌려주므로 실패한 행만 고쳐 재업로드하면 된다.")
    @PostMapping("/tracking-upload")
    public ResponseEntity<BulkTrackingResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        try (var in = file.getInputStream()) {
            return ResponseEntity.ok(useCase.register(parser.parse(in), dryRun));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
