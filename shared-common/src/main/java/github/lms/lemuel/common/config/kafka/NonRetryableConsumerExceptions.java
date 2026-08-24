package github.lms.lemuel.common.config.kafka;

import java.util.List;

/**
 * 서비스가 "재시도해도 결과가 같은 예외"를 공용 Kafka 에러 핸들러에 기여하는 확장 포인트.
 *
 * <p>{@link KafkaConsumerErrorHandlingConfig} 는 기본 3종(파싱 실패·인풋 검증 실패·상태 위반)을
 * 즉시 DLT 로 보낸다. 다만 OO 게이트상 generic {@code IllegalArgumentException} 대신 도메인 타입
 * 예외를 던지는 서비스(account 의 {@code AccountDomainException} 등)는 그 타입이 기본 3종에
 * 걸리지 않아 무의미한 재시도 3회를 돌게 된다. 그런 서비스는 이 인터페이스를 빈으로 등록해
 * 자신의 도메인 예외를 기여한다.
 *
 * <pre>{@code
 * @Bean
 * NonRetryableConsumerExceptions accountDomainExceptions() {
 *     return () -> List.of(AccountDomainException.class);
 * }
 * }</pre>
 *
 * <p>기여자가 없어도 배선은 정상 동작한다 — 대부분의 서비스는 기본 3종으로 충분하다.
 */
@FunctionalInterface
public interface NonRetryableConsumerExceptions {

    /** 재시도 없이 즉시 DLT 로 보낼 예외 타입들. */
    List<Class<? extends Exception>> exceptions();
}
