package github.lms.lemuel.common.config.kafka;

import java.util.Map;
import java.util.Set;

/**
 * 브로커 토픽 조작 포트 — 프로비저닝 판단 로직을 Kafka {@code AdminClient} 에서 분리한다.
 *
 * <p><b>노출한 것과 노출하지 않은 것이 설계다.</b> 토픽 속성은 변경 가능성이 서로 다르다:
 *
 * <table>
 *   <tr><th>속성</th><th>변경 결과</th><th>이 포트의 취급</th></tr>
 *   <tr><td>파티션</td><td>키 재해시 → 순서 보장 <b>소급 붕괴</b>. 되돌릴 수 없다</td>
 *       <td>메서드 없음 — 부를 수 없으면 실수도 없다</td></tr>
 *   <tr><td>보존기간</td><td>로그 삭제 시점만 바뀐다. 키·순서와 무관하고 되돌릴 수 있다</td>
 *       <td>{@link #alterRetention} 제공 — 자동 정정이 안전하다</td></tr>
 *   <tr><td>복제본</td><td>파티션 재배치가 필요하고 브로커 수에 종속된다</td>
 *       <td>메서드 없음 — 관측만 하고 조치는 사람이 한다</td></tr>
 * </table>
 */
public interface TopicAdmin {

    /**
     * 브로커에 있는 토픽 하나의 실제 상태.
     *
     * @param retentionPinned 보존기간이 <b>토픽에 명시</b>됐는지(DYNAMIC_TOPIC_CONFIG). false 면 값이
     *                        맞더라도 클러스터 기본값(log_retention_ms)을 물려받은 것이라, 그 기본값이
     *                        바뀌는 순간 조용히 따라 바뀐다 — 카탈로그가 보장하는 상태가 아니다
     */
    record TopicState(int partitions, int replicas, int retentionDays, boolean retentionPinned) {
    }

    /** 주어진 이름 중 <b>실재하는</b> 토픽의 상태. 없는 토픽은 결과 맵에 담기지 않는다. */
    Map<String, TopicState> describe(Set<String> names);

    /** 토픽을 새로 만든다. 이미 있으면 호출되지 않는다. */
    void create(TopicCatalog.Spec spec);

    /** 보존기간을 토픽에 명시적으로 고정한다(클러스터 기본값 상속을 끊는다). */
    void alterRetention(String name, int retentionDays);
}
