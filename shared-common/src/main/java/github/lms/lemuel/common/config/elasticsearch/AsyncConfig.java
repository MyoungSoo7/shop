package github.lms.lemuel.common.config.elasticsearch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * Elasticsearch 인덱싱 이벤트를 비동기로 처리하기 위한 설정
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 비동기 작업을 위한 ThreadPool Executor
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);           // 기본 스레드 수
        executor.setMaxPoolSize(5);            // 최대 스레드 수
        executor.setQueueCapacity(100);        // 대기 큐 크기
        executor.setThreadNamePrefix("settlement-index-");
        // 큐(100) + maxPool(5)까지 가득 차면 기본 AbortPolicy 는 예외를 던져 작업을 버린다.
        // ES 인덱싱은 베스트에포트지만, 거부 시 호출 스레드가 직접 실행하는 CallerRunsPolicy 로
        // 무손실 + 백프레셔를 보장한다. (원장 작업은 더 이상 이 풀에 의존하지 않고 트랜잭셔널
        // 아웃박스 + 로컬 폴러로 처리된다 — LedgerOutboxPoller 참고.)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
