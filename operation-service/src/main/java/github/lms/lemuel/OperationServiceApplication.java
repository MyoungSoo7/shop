package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * operation-service 독립 부팅 진입점 — 운영 관제(인시던트·이상 탐지·AI 브리핑) 서비스.
 *
 * <p>★ operation-service 는 자체 DB(lemuel_operation) 를 소유하는 DB-per-service 이며,
 * loan-service 와 동일하게 루트 {@code github.lms.lemuel} 에서 스캔해
 * operation 패키지 + shared-common(JWT·Outbox·멱등 인프라) 빈만 잡는다.
 * order/settlement/loan 은 의존(build.gradle.kts)에 없어 클래스패스에 존재하지 않으므로
 * MSA 코드 경계가 유지된다.
 *
 * <p>Phase 1 범위: Alertmanager webhook 수신 → 인시던트 자동 적재(open/refire/auto-resolve)
 * + 운영자 인시던트 관리 API({@code /api/ops/**}). Kafka 신호 수집·이상 탐지·AI 브리핑은
 * Phase 2~4 에서 이 골격 위에 얹는다 (docs/design/operation-service-phase1.md).
 */
@SpringBootApplication
@EnableScheduling
// 타입 세이프 설정 등록 — OpsProperties(app.ops) + board 슬라이스 AttachmentProperties(app.board.attachment).
// @Component 대신 스캔으로 등록하는 이유: 이 애노테이션은 @WebMvcTest 슬라이스에도 적용되므로
// (앱 클래스가 @SpringBootConfiguration 루트) 웹 슬라이스가 물어 오는 필터(OpsWebhookAuthFilter)의
// 의존이 슬라이스에서도 채워진다 — @Component 등록이면 슬라이스 스캔에서 빠져 컨텍스트가 깨진다.
@ConfigurationPropertiesScan(basePackages = {
        "github.lms.lemuel.operation.config",
        "github.lms.lemuel.operation.board.config",
})
public class OperationServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(OperationServiceApplication.class, args);
    }
}
