package github.lms.lemuel.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.outbox.OutboxJson;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 2 호환 매퍼 자동 구성 — 스캔 범위와 무관하게 라이브러리가 스스로 채운다.
 *
 * <p><b>왜 자동 구성인가</b> — shared-common 은 버전드 라이브러리인데 빈 등록을 소비 서비스의
 * 컴포넌트 스캔 범위에 의존하고 있었다. 스캔을 좁힌 서비스(ai·company)는 새 공용 빈이 생길 때마다
 * {@code @Import} 를 손으로 추가해야 했고, 빠뜨리면 <b>런타임에야</b> 드러난다(CLAUDE.md 의 알려진 함정).
 * 김영한 「스프링 부트 - 핵심 원리와 활용」 §자동 구성 라이브러리 만들기가 정리하는 대로,
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 로
 * 선언해 사용자 편의를 라이브러리 쪽 책임으로 옮긴다.
 *
 * <p><b>이중 등록 방지</b> — 루트를 스캔하는 서비스는 이미 {@link JacksonCompatConfig} 를 빈으로
 * 잡는다. 그래서 <b>그 설정 클래스가 빈으로 있으면 이 자동 구성 전체가 물러난다</b>
 * ({@code @ConditionalOnMissingBean(JacksonCompatConfig.class)}).
 *
 * <p>조건 대상이 {@code ObjectMapper} 가 아니라 <b>설정 클래스</b>인 이유가 중요하다. 컴포넌트 스캔으로
 * 발견된 {@code @Configuration} 의 {@code @Bean} 정의는 <b>다음 파싱 라운드</b>에 등록되는 반면
 * 자동 구성은 첫 라운드에서 등록된다. 그래서 매퍼 타입으로 조건을 걸면 자동 구성이 먼저 등록되고,
 * 뒤이어 스캔된 정의가 <b>같은 빈 이름</b>으로 등록을 시도해
 * {@code BeanDefinitionOverrideException} 으로 기동이 깨진다(account-service 에서 실측).
 * 반면 스캔된 <b>설정 클래스 빈</b>은 스캔 시점에 바로 등록되므로 조건이 확실히 성립한다.
 *
 * <p>생성 로직은 {@link JacksonCompatConfig#legacyMapper()} / {@link OutboxJson#mapper()} 로
 * 단일화돼 있어 두 경로가 어긋날 수 없다.
 *
 * <p>{@code app.jackson.compat.enabled=false} 로 끌 수 있다(기본 on).
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnMissingBean(JacksonCompatConfig.class)
@ConditionalOnProperty(prefix = "app.jackson.compat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JacksonCompatAutoConfiguration {

    /**
     * 범용 Jackson 2 매퍼. 서비스가 자기 {@link ObjectMapper} 를 따로 정의했다면 그쪽을 존중한다.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jacksonLegacyObjectMapper() {
        return JacksonCompatConfig.legacyMapper();
    }

    /**
     * Outbox payload 전용 매퍼 — 금액을 plain string 으로 직렬화(DATA-STANDARD N5).
     * 이름 기준으로 판단해, 범용 매퍼만 따로 정의한 서비스에도 누락 없이 채워진다.
     */
    @Bean("outboxObjectMapper")
    @ConditionalOnMissingBean(name = "outboxObjectMapper")
    public ObjectMapper outboxObjectMapper() {
        return OutboxJson.mapper();
    }
}
