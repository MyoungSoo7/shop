package github.lms.lemuel.common.outbox.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbox 네이티브 쿼리(SKIP LOCKED 폴링)의 스키마 한정자를 서비스별로 주입하기 위한 홀더.
 *
 * <p>JPA 엔티티({@code @Table(name="outbox_events")})는 hibernate {@code default_schema} 를 따라
 * 서비스별 스키마(order=opslab, settlement=public)로 해석되지만, <b>네이티브 SQL 은 default_schema 를
 * 무시</b>한다. ADR 0020 Phase 4 의 DB-per-service 분리 후 settlement 는 {@code public} 스키마를
 * 쓰는데 네이티브 쿼리가 {@code opslab.} 를 하드코딩하면 {@code relation "opslab.outbox_events" does
 * not exist} 로 Outbox 발행이 전량 실패한다.
 *
 * <p>각 서비스가 이미 선언한 {@code spring.jpa.properties.hibernate.default_schema} 를 단일 진실로
 * 재사용해, {@link SpringDataOutboxEventRepositoryCustomImpl} 이 네이티브 SQL 조립 시 스키마
 * 한정자로 주입한다. pgbouncer transaction pooling 에서 불안정한 search_path 의존 없이 명시적
 * 한정자를 유지한다.
 */
@Component("outboxSchema")
public class OutboxSchema {

    private final String name;

    public OutboxSchema(@Value("${spring.jpa.properties.hibernate.default_schema:public}") String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
