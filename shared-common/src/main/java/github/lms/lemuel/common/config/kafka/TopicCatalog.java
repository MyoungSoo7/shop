package github.lms.lemuel.common.config.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Kafka 토픽 전송 속성의 단일 출처 (ADR 0035).
 *
 * <p>이벤트 <b>페이로드</b> 계약은 ADR 0024(JSON Schema, testFixtures)가 정본이다. 이 카탈로그는
 * 그 계약이 다루지 않는 <b>전송</b> 속성 — 파티션 수·보존기간·순서키·소유 서비스 — 을 맡는다.
 * 페이로드 계약과 달리 전송 속성은 프로덕션 기동 시점에 필요하므로 {@code src/main/resources} 에 둔다.
 *
 * <p><b>왜 파티션 수가 코드에 있어야 하는가.</b> 이 저장소는 outbox 의 {@code aggregateId} 를 메시지
 * 키로 써서 "같은 결제/정산의 이벤트는 같은 파티션 → 시간 순서 보장"을 얻는다. 파티션 수 N 이 바뀌면
 * {@code hash(key) % N} 이 바뀌어 같은 애그리거트의 이벤트가 다른 파티션으로 흩어진다 — 이미 쌓인
 * 메시지에 대해서까지 순서 보장이 소급 붕괴한다. 되돌릴 수 없는 결정이므로 코드 리뷰를 거쳐야 하고,
 * 그러려면 값이 코드 안에 있어야 한다. 브로커 자동생성에 맡기면 리뷰할 대상 자체가 없다.
 */
public final class TopicCatalog {

    /** DLT 보존기간(일) — 원본보다 길게. 운영자가 사후 분석·재처리할 시간을 남긴다. */
    public static final int DLT_RETENTION_DAYS = 30;

    private static final String NAMESPACE = "lemuel.";
    private static final String DLT_SUFFIX = ".DLT";
    private static final String DEFAULT_RESOURCE = "/kafka/topic-catalog.json";

    /**
     * 토픽 하나의 전송 속성.
     *
     * @param name          토픽명 ({@code lemuel.<aggregate>.<event>})
     * @param owner         이 토픽을 <b>발행</b>하는 Gradle 모듈명. 토픽을 만드는 주체는 프로듀서 하나뿐이다
     * @param orderingKey   메시지 키의 도메인 의미 = outbox {@code aggregateId} 가 담는 값. 이 키 단위로
     *                      시간 순서가 보장된다. 선언을 강제해 "무엇의 순서를 지키는가"를 항상 명시하게 한다
     * @param partitions    파티션 수. 컨슈머 병렬 소비의 상한이자 키 해시의 제수 — 변경은 순서 보장을 깬다
     * @param replicas      복제본 수. 브로커 수를 넘을 수 없다(로컬 단일 브로커 = 1, 프로덕션 권장 3).
     *                      코드 상수가 아니라 토픽별 선언인 이유는 파티션과 같다 — 리뷰 대상이어야 한다
     * @param retentionDays 보존기간(일). 명시하지 않으면 클러스터 기본값(log_retention_ms)을 물려받아
     *                      그 값이 바뀔 때 조용히 따라 바뀐다 — 그래서 프로비저너가 토픽에 고정한다
     */
    public record Topic(String name, String owner, String orderingKey, int partitions, int replicas,
                        int retentionDays) {

        public Topic {
            if (name == null || !name.startsWith(NAMESPACE)) {
                throw new InvalidTopicCatalogException(
                        "토픽명은 '" + NAMESPACE + "' 로 시작해야 한다: " + name);
            }
            if (name.endsWith(DLT_SUFFIX)) {
                throw new InvalidTopicCatalogException(
                        "DLT 는 카탈로그에 직접 등록할 수 없다 — 원본에서 파생되어야 파티션이 갈리지 않는다: " + name);
            }
            if (owner == null || owner.isBlank()) {
                throw new InvalidTopicCatalogException("owner 가 비었다: " + name);
            }
            if (orderingKey == null || orderingKey.isBlank()) {
                throw new InvalidTopicCatalogException(
                        "orderingKey 가 비었다 — 무엇의 시간 순서를 보장하는지 선언하지 않은 토픽은 둘 수 없다: " + name);
            }
            if (replicas < 1) {
                throw new InvalidTopicCatalogException("replicas 는 1 이상이어야 한다: " + name + "=" + replicas);
            }
            if (partitions < 1) {
                throw new InvalidTopicCatalogException("partitions 는 1 이상이어야 한다: " + name + "=" + partitions);
            }
            if (retentionDays < 1) {
                throw new InvalidTopicCatalogException("retentionDays 는 1 이상이어야 한다: " + name);
            }
        }

        /** 브로커에 만들 때 필요한 값만 추린 것. */
        public Spec spec() {
            return new Spec(name, partitions, replicas, retentionDays);
        }

        /**
         * 이 토픽의 격리 토픽 스펙. <b>파티션 수는 원본과 같다</b> — 계산해서 파생시키는 이유는 실측 사고
         * 때문이다: 원본이 6 파티션인데 DLT 가 3 이면 파티션 3~5 의 레코드는 존재하지 않는 파티션으로
         * 라우팅되어 격리 발행 자체가 실패한다(notification-service 에 기록된 실제 사례). 파생값이면
         * 둘이 어긋날 수 없다.
         */
        public Spec deadLetterSpec() {
            return new Spec(name + DLT_SUFFIX, partitions, replicas, DLT_RETENTION_DAYS);
        }
    }

    /**
     * 브로커에 실제로 만들 토픽 하나의 스펙. 원본과 DLT 를 같은 타입으로 다뤄 프로비저너가 둘을
     * 구분하지 않게 한다 — 구분이 없으면 한쪽만 빠뜨릴 수도 없다.
     */
    public record Spec(String name, int partitions, int replicas, int retentionDays) {
    }

    private final List<Topic> topics;

    private TopicCatalog(List<Topic> topics) {
        this.topics = List.copyOf(topics);
    }

    /** 불변식을 검증하고 카탈로그를 만든다. 위반은 기동 실패로 드러난다. */
    public static TopicCatalog of(List<Topic> topics) {
        Set<String> seen = new HashSet<>();
        for (Topic topic : topics) {
            if (!seen.add(topic.name())) {
                throw new InvalidTopicCatalogException("토픽명이 중복됐다: " + topic.name());
            }
        }
        return new TopicCatalog(topics);
    }

    /** 클래스패스의 정본 카탈로그를 읽는다. */
    public static TopicCatalog loadDefault() {
        try (InputStream in = TopicCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new InvalidTopicCatalogException("카탈로그 리소스가 없다: " + DEFAULT_RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new InvalidTopicCatalogException("카탈로그를 읽지 못했다: " + DEFAULT_RESOURCE, e);
        }
    }

    /** JSON 스트림에서 카탈로그를 읽는다. */
    public static TopicCatalog load(InputStream json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            List<Topic> parsed = new ArrayList<>();
            for (JsonNode node : root.path("topics")) {
                parsed.add(new Topic(
                        node.path("name").asText(null),
                        node.path("owner").asText(null),
                        node.path("orderingKey").asText(null),
                        node.path("partitions").asInt(0),
                        node.path("replicas").asInt(0),
                        node.path("retentionDays").asInt(0)));
            }
            return of(parsed);
        } catch (IOException e) {
            throw new InvalidTopicCatalogException("카탈로그 JSON 파싱 실패", e);
        }
    }

    public List<Topic> all() {
        return topics;
    }

    /** 이 모듈이 <b>발행</b>하는 토픽만. 컨슈머 전용 서비스는 빈 목록을 받는다. */
    public List<Topic> ownedBy(String module) {
        return topics.stream().filter(t -> t.owner().equals(module)).toList();
    }

    public Optional<Topic> find(String name) {
        return topics.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
