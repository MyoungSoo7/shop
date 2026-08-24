package github.lms.lemuel.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration for the Settlement (lemuel) service.
 *
 * <p>Swagger UI is exposed at {@code /swagger-ui/index.html} and the raw
 * OpenAPI document is available at {@code /v3/api-docs}.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Settlement (lemuel) API",
                version = "0.0.1-SNAPSHOT",
                description = "주문/결제/정산/승인 통합 시스템 REST API. "
                        + "헥사고날 아키텍처 기반의 도메인(user, order, payment, settlement, "
                        + "product, category, coupon, review) 엔드포인트를 제공한다.",
                contact = @Contact(name = "lemuel team", url = "https://github.com/MyoungSoo7/settlement"),
                license = @License(name = "Apache License 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")
        ),
        servers = {
                @Server(url = "http://localhost:8080", description = "Local development server"),
                @Server(url = "https://api.lemuel.example.com", description = "Production server")
        }
)
public class OpenApiConfig {
}
