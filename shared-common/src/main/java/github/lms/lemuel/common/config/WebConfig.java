package github.lms.lemuel.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

/**
 * Spring MVC 설정
 * - UTF-8 인코딩 강제 설정 (한글 등 다국어 지원)
 * - CORS 설정
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * HTTP Message Converter에 UTF-8 인코딩 명시적 설정
     * - JSON 응답 시 한글이 깨지지 않도록 보장
     * - String 응답 시에도 UTF-8 적용
     *
     * <p>Spring 7 의 fluent {@link HttpMessageConverters.ServerBuilder} 를 사용하여
     * 기본 컨버터 세트에서 String/JSON 만 UTF-8 변형으로 교체한다.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setWriteAcceptCharset(false); // Accept-Charset 헤더 제거 (보안상 권장)

        JacksonJsonHttpMessageConverter jsonConverter = new JacksonJsonHttpMessageConverter();
        jsonConverter.setDefaultCharset(StandardCharsets.UTF_8);

        builder.withStringConverter(stringConverter)
               .withJsonConverter(jsonConverter);
    }

    /**
     * CORS 설정 (개발 환경용)
     * - 프론트엔드(React)에서 백엔드 API 호출 시 CORS 에러 방지
     * - 운영 환경에서는 SecurityConfig에서 더 엄격하게 관리
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",  // Vite 기본 포트
                        "http://localhost:3000",  // CRA 기본 포트
                        "http://localhost:8080"   // 같은 도메인
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With", "Idempotency-Key")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
