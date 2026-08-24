package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.util.List;

/**
 * 도메인 서비스가 동일 @Transactional 안에서 outbox 레코드를 기록하기 위한 포트.
 */
public interface SaveOutboxEventPort {
    OutboxEvent save(OutboxEvent event);

    /**
     * 배치 폴러가 한 트랜잭션에서 발행 결과(PUBLISHED/FAILED) 를 일괄 반영하기 위한 경로.
     * JDBC 배치와 맞물려 라운드트립을 줄인다.
     */
    void saveAll(List<OutboxEvent> events);
}
