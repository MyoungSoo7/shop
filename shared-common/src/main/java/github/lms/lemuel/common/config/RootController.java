package github.lms.lemuel.common.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Root", description = "루트 상태 및 문서 링크")
@RestController
public class RootController {

    @Operation(summary = "서비스 상태 확인", description = "서비스가 기동 중인지 확인하고 Swagger UI 경로를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정상")
    })
    @GetMapping("/")
    public ResponseEntity<Map<String, String>> root() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "docs", "/swagger-ui/index.html"
        ));
    }
}
