package github.lms.lemuel.common.config.kafka;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카탈로그가 선언한 토픽을 브로커에 반영한다 (ADR 0035).
 *
 * <p><b>속성마다 다르게 다룬다.</b> 처음에는 세 속성을 모두 "만들 때만 적용"으로 통일했는데, 그건
 * 파티션의 성질에 맞춘 규칙을 나머지에까지 적용한 실수였다. 실측에서 드러났듯이 기존 토픽의 보존기간은
 * 카탈로그가 뭐라 적혀 있든 클러스터 기본값(log_retention_ms)을 물려받고 있었다 — 선언은 있는데
 * 효력이 없는 <b>쓰기 전용 문서</b>였다.
 *
 * <ul>
 *   <li><b>파티션</b> — 만들 때만. 변경은 키 재해시라 이미 쌓인 메시지의 순서 보장까지 소급해서 깬다.
 *       불일치는 {@link Drift} 로 보고만 하고 조치는 사람이 판단한다.</li>
 *   <li><b>보존기간</b> — <b>항상 맞춘다.</b> 로그 삭제 시점만 바뀔 뿐 키·순서와 무관하고 되돌릴 수
 *       있다. 값이 같아도 토픽에 고정되지 않았다면 고정한다 — 상속 상태로 두면 클러스터 기본값이
 *       바뀌는 순간 조용히 따라 바뀐다.</li>
 *   <li><b>복제본</b> — 보고만. 파티션 재배치가 필요하고 브로커 수에 종속된다.</li>
 * </ul>
 */
public class TopicProvisioner {

    /** 프로비저닝 결과. */
    public record Report(List<String> created, List<Drift> drifted, List<String> retentionAligned) {
    }

    /** 브로커 실제 값이 카탈로그 선언과 다른 상태. 자동으로 고치지 않는 속성만 여기 담긴다. */
    public record Drift(String topic, String property, int declared, int actual) {
    }

    private final TopicAdmin admin;

    public TopicProvisioner(TopicAdmin admin) {
        this.admin = admin;
    }

    public Report provision(TopicCatalog catalog, String module) {
        List<TopicCatalog.Spec> specs = new ArrayList<>();
        for (TopicCatalog.Topic topic : catalog.ownedBy(module)) {
            specs.add(topic.spec());
            specs.add(topic.deadLetterSpec());
        }

        Set<String> names = new LinkedHashSet<>(specs.stream().map(TopicCatalog.Spec::name).toList());
        Map<String, TopicAdmin.TopicState> actual = admin.describe(names);

        List<String> created = new ArrayList<>();
        List<Drift> drifted = new ArrayList<>();
        List<String> retentionAligned = new ArrayList<>();

        for (TopicCatalog.Spec spec : specs) {
            TopicAdmin.TopicState state = actual.get(spec.name());
            if (state == null) {
                admin.create(spec);
                created.add(spec.name());
                continue;
            }
            if (state.partitions() != spec.partitions()) {
                drifted.add(new Drift(spec.name(), "partitions", spec.partitions(), state.partitions()));
            }
            if (state.replicas() != spec.replicas()) {
                drifted.add(new Drift(spec.name(), "replicas", spec.replicas(), state.replicas()));
            }
            // 값이 같아도 고정돼 있지 않으면 고정한다 — 상속은 "지금 우연히 같은 값"일 뿐이다.
            if (!state.retentionPinned() || state.retentionDays() != spec.retentionDays()) {
                admin.alterRetention(spec.name(), spec.retentionDays());
                retentionAligned.add(spec.name());
            }
        }
        return new Report(created, drifted, retentionAligned);
    }
}
