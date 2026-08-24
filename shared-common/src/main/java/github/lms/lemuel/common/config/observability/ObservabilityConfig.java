package github.lms.lemuel.common.config.observability;

import github.lms.lemuel.common.audit.adapter.in.AuditContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Observability 관련 필터 등록:
 * - TraceIdFilter: 최상위 우선순위 (모든 로그에 traceId 부착)
 * - AuditContextFilter: Security 이후 실행되어 actor 정보 추출
 *
 * <p>필터를 FilterRegistrationBean 으로 등록하므로 WebMvcTest 슬라이스에서 자동 등록되지 않는다.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    public AuditContextFilter auditContextFilter() {
        return new AuditContextFilter();
    }

    @Bean
    public FilterRegistrationBean<AuditContextFilter> auditContextFilterRegistration(
            AuditContextFilter filter) {
        FilterRegistrationBean<AuditContextFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
