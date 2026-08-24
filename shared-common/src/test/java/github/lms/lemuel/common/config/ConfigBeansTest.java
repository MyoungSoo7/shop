package github.lms.lemuel.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.adapter.in.AuditContextFilter;
import github.lms.lemuel.common.audit.config.AuditAopConfig;
import github.lms.lemuel.common.config.cache.CacheNames;
import github.lms.lemuel.common.config.elasticsearch.AsyncConfig;
import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.common.config.observability.ObservabilityConfig;
import github.lms.lemuel.common.config.observability.TraceIdFilter;
import github.lms.lemuel.common.config.scheduling.SchedulingLockConfig;
import github.lms.lemuel.common.observability.aop.AopObservabilityConfig;
import github.lms.lemuel.common.observability.aop.MethodTraceAspect;
import github.lms.lemuel.common.observability.aop.ObservabilityAopProperties;
import github.lms.lemuel.common.observability.aop.TransactionTraceAspect;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Configuration 클래스의 @Bean 팩토리 메서드를 직접 호출로 커버한다(가능한 것은 순수 인스턴스화,
 * 의존성이 필요한 것은 목/리플렉션 주입). HttpSecurity 가 필요한 SecurityConfig.securityFilterChain
 * 은 별도 컨텍스트 테스트(SecurityConfigContextTest)에서 다룬다.
 */
class ConfigBeansTest {

    private static JwtUtil jwtUtil() {
        JwtProperties props = new JwtProperties();
        props.setIssuer("t");
        props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
        props.setTtlSeconds(3600);
        return new JwtUtil(props);
    }

    @Test
    @DisplayName("CacheConfig: Caffeine CacheManager 가 선언된 캐시명을 갖는다")
    void cacheConfig() {
        CacheManager manager = new CacheConfig().cacheManager();
        assertThat(manager.getCacheNames()).containsAll(CacheNames.ALL);
    }

    @Test
    @DisplayName("AsyncConfig: taskExecutor 가 초기화된다")
    void asyncConfig() {
        Executor executor = new AsyncConfig().taskExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("JacksonCompatConfig: JavaTimeModule 등록된 레거시 ObjectMapper")
    void jacksonCompatConfig() throws Exception {
        ObjectMapper mapper = new JacksonCompatConfig().jacksonLegacyObjectMapper();
        String json = mapper.writeValueAsString(java.time.LocalDate.of(2026, 7, 11));
        assertThat(json).isEqualTo("\"2026-07-11\"");
    }

    @Test
    @DisplayName("RootController: 상태/문서 링크 반환")
    void rootController() {
        ResponseEntity<Map<String, String>> res = new RootController().root();
        assertThat(res.getBody()).containsEntry("status", "ok").containsEntry("docs", "/swagger-ui/index.html");
    }

    @Test
    @DisplayName("애노테이션 전용 설정 클래스도 인스턴스화 가능")
    void annotationOnlyConfigs() {
        assertThat(new OpenApiConfig()).isNotNull();
        assertThat(new AuditAopConfig()).isNotNull();
        assertThat(new WebConfig()).isNotNull();
    }

    @Test
    @DisplayName("ObservabilityConfig: 필터 및 등록빈(order/urlPattern) 생성")
    void observabilityConfig() {
        ObservabilityConfig config = new ObservabilityConfig();
        TraceIdFilter traceFilter = config.traceIdFilter();
        assertThat(config.traceIdFilterRegistration(traceFilter).getUrlPatterns()).contains("/*");
        AuditContextFilter auditFilter = config.auditContextFilter();
        assertThat(config.auditContextFilterRegistration(auditFilter).getUrlPatterns()).contains("/*");
    }

    @Test
    @DisplayName("AopObservabilityConfig: 두 Aspect 빈 생성")
    void aopObservabilityConfig() {
        AopObservabilityConfig config = new AopObservabilityConfig();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        MethodTraceAspect method = config.methodTraceAspect(new ObservabilityAopProperties(), provider);
        TransactionTraceAspect tx = config.transactionTraceAspect();
        assertThat(method).isNotNull();
        assertThat(tx).isNotNull();
    }

    @Test
    @DisplayName("SchedulingLockConfig: JdbcTemplate 기반 LockProvider 생성")
    void schedulingLockConfig() {
        DataSource ds = mock(DataSource.class);
        LockProvider provider = new SchedulingLockConfig().shedLockProvider(ds, "public");
        assertThat(provider).isNotNull();
    }

    @Test
    @DisplayName("QueryDslConfig: EntityManager 주입 후 JPAQueryFactory 생성")
    void queryDslConfig() {
        QueryDslConfig config = new QueryDslConfig();
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(config, "entityManager", em);
        assertThat(config.jpaQueryFactory()).isNotNull();
    }

    @Test
    @DisplayName("ReadReplicaDataSourceConfig: write/read DS + 라우팅 DS 조립, readOnly 라우팅 키")
    void readReplicaConfig() {
        ReadReplicaDataSourceConfig config = new ReadReplicaDataSourceConfig();
        HikariDataSource write = config.writeDataSource();
        HikariDataSource read = config.readDataSource();
        assertThat(config.dataSource(write, read)).isNotNull();

        ReadReplicaDataSourceConfig.RoutingDataSource routing = new ReadReplicaDataSourceConfig.RoutingDataSource();
        // 트랜잭션 미시작 → readOnly=false → WRITE
        assertThat(routing.determineCurrentLookupKey()).isEqualTo(ReadReplicaDataSourceConfig.Route.WRITE);
        write.close();
        read.close();
    }

    @Test
    @DisplayName("WebConfig: /api 매핑 CORS 등록 + UTF-8 메시지 컨버터 구성")
    void webConfigCorsAndConverters() {
        WebConfig config = new WebConfig();
        CorsRegistry registry = new CorsRegistry();
        config.addCorsMappings(registry);
        assertThat(registry).isNotNull();

        var builder = mock(org.springframework.http.converter.HttpMessageConverters.ServerBuilder.class);
        when(builder.withStringConverter(any())).thenReturn(builder);
        when(builder.withJsonConverter(any())).thenReturn(builder);
        config.configureMessageConverters(builder);
        org.mockito.Mockito.verify(builder).withStringConverter(any());
        org.mockito.Mockito.verify(builder).withJsonConverter(any());
    }

    @Test
    @DisplayName("WebMvcConfig: /assets 정적 리소스 핸들러 등록")
    void webMvcResourceHandlers() {
        WebMvcConfig config = new WebMvcConfig();
        ReflectionTestUtils.setField(config, "uploadDir", "/data/uploads");
        var registry = mock(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry.class);
        var registration = mock(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration.class);
        when(registry.addResourceHandler(any())).thenReturn(registration);
        when(registration.addResourceLocations(any(String.class))).thenReturn(registration);

        config.addResourceHandlers(registry);

        org.mockito.Mockito.verify(registry).addResourceHandler("/assets/**");
        org.mockito.Mockito.verify(registration).addResourceLocations("file:/data/uploads/");
    }

    @Test
    @DisplayName("SecurityConfig: passwordEncoder(BCrypt) + corsConfigurationSource(환경변수 origin)")
    void securityConfigBeans() {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtUtil());
        InternalApiKeyFilter internalFilter = new InternalApiKeyFilter("k");
        SecurityConfig config = new SecurityConfig(jwtFilter, internalFilter);

        PasswordEncoder encoder = config.passwordEncoder();
        assertThat(encoder.matches("pw", encoder.encode("pw"))).isTrue();

        // 환경변수 origin 분기
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://a.com,https://b.com");
        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cfg = ((UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations().get("/**");
        assertThat(cfg.getAllowedOrigins()).containsExactly("https://a.com", "https://b.com");

        // 기본(localhost) 분기
        SecurityConfig config2 = new SecurityConfig(jwtFilter, internalFilter);
        ReflectionTestUtils.setField(config2, "corsAllowedOrigins", "");
        CorsConfiguration defaults = ((UrlBasedCorsConfigurationSource) config2.corsConfigurationSource())
                .getCorsConfigurations().get("/**");
        assertThat(defaults.getAllowedOrigins()).contains("http://localhost:3000");
    }
}
