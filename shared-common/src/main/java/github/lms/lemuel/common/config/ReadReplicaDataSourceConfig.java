package github.lms.lemuel.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 읽기/쓰기 분리 라우팅 데이터소스 (opt-in).
 *
 * <p>{@code app.datasource.read-replica.enabled=true} 일 때만 활성화된다. 비활성(기본)이면
 * Spring Boot 의 단일 데이터소스 자동구성이 그대로 쓰여 dev/test/기존 동작에 아무 영향이 없다.
 *
 * <p>활성화 시: {@code @Transactional(readOnly = true)} 트랜잭션은 읽기 레플리카로, 그 외(쓰기)는
 * 프라이머리로 라우팅된다. 상품/카테고리/주문조회/정산 대시보드 등 읽기 TPS 를 레플리카로 오프로드해
 * 프라이머리(쓰기) 부하를 줄인다.
 *
 * <p>핵심: {@link LazyConnectionDataSourceProxy} 로 실제 커넥션 획득을 첫 쿼리 시점까지 지연시켜야
 * 트랜잭션의 readOnly 플래그가 결정된 뒤 라우팅 키가 평가된다. 이 프록시 없이는 트랜잭션 시작 시점에
 * 커넥션을 잡아 항상 프라이머리로 간다.
 *
 * <p>Boot 4 의 autoconfigure 패키지 재편과 무관하도록 Spring 코어/Hikari 클래스만 사용한다.
 * 활성화하는 운영 환경은 {@code app.datasource.write.*}, {@code app.datasource.read.*} 에
 * Hikari 네이티브 프로퍼티(jdbc-url, username, password, maximum-pool-size 등)를 지정한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.datasource.read-replica.enabled", havingValue = "true")
public class ReadReplicaDataSourceConfig {

    enum Route { WRITE, READ }

    @Bean
    @ConfigurationProperties("app.datasource.write")
    public HikariDataSource writeDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.read")
    public HikariDataSource readDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource writeDataSource, HikariDataSource readDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of(
                Route.WRITE, writeDataSource,
                Route.READ, readDataSource));
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }

    /**
     * 현재 트랜잭션의 readOnly 여부로 라우팅 키 결정. readOnly → READ(레플리카), 그 외 → WRITE(프라이머리).
     */
    static class RoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ? Route.READ
                    : Route.WRITE;
        }
    }
}
