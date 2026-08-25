package github.lms.lemuel.operation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 토픽을 <b>같은 컨슈머 그룹</b>으로 두 리스너가 구독하지 않는다.
 *
 * <p>이건 취향 문제가 아니라 조용한 데이터 유실이다. 카프카는 같은 그룹의 컨슈머들에게 파티션을
 * <b>나눠</b> 준다. {@code lemuel.order.created} 는 파티션이 1개라, 같은 그룹으로 리스너를 둘
 * 붙이면 한쪽이 파티션을 잡고 나머지 한쪽은 <b>영원히 아무 레코드도 받지 못한다</b>.
 * 예외도, 경고 로그도, 지연 지표의 변화도 없다 — 그냥 한쪽 기능이 안 돌 뿐이다.
 *
 * <p>이 저장소는 실제로 그 문턱까지 갔다. 신호 컨슈머가 {@code lemuel-operation} 으로 주문·결제
 * 이벤트를 이미 구독하는 상태에서 대시보드 집계 컨슈머가 같은 토픽을 필요로 했다. 그때
 * "옆에 있는 그룹 이름을 그대로 쓰는" 것이 가장 자연스러운 선택이었고, 그 선택이 침묵하는
 * 장애가 됐을 것이다. 사람이 매번 기억하는 대신 여기서 막는다.
 *
 * <p>토픽 이름이 {@code ${prop:default}} 꼴이면 기본값으로 판정한다. 배포에서 프로퍼티를 바꿔
 * 갈라놓을 수는 있지만, <b>기본값끼리 겹치는 코드</b>는 그 자체로 함정이다.
 */
class KafkaConsumerGroupIsolationTest {

    @Test
    @DisplayName("같은 토픽을 같은 컨슈머 그룹으로 구독하는 리스너가 둘 이상 있으면 안 된다")
    void noTopicIsSharedWithinAConsumerGroup() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel");

        Map<String, List<String>> byTopicAndGroup = new LinkedHashMap<>();

        for (JavaMethod method : classes.stream()
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(KafkaListener.class))
                .toList()) {

            KafkaListener listener = method.getAnnotationOfType(KafkaListener.class);
            String group = resolve(listener.groupId());
            if (group.isBlank()) {
                // groupId 를 안 적으면 프로퍼티(spring.kafka.consumer.group-id)를 따른다.
                // 여기서는 판정할 수 없으므로 넘긴다 — 이 저장소의 리스너는 모두 명시한다.
                continue;
            }
            for (String topic : listener.topics()) {
                byTopicAndGroup
                        .computeIfAbsent(resolve(topic) + " @ " + group, key -> new ArrayList<>())
                        .add(method.getOwner().getSimpleName() + "#" + method.getName());
            }
        }

        // 스캔이 비면 이 테스트는 아무것도 검사하지 않으면서 초록불이 된다.
        assertThat(byTopicAndGroup).as("@KafkaListener 를 하나도 못 찾았다 — 스캐너가 죽은 것이다")
                .isNotEmpty();

        assertThat(byTopicAndGroup)
                .as("같은 토픽·같은 그룹을 두 리스너가 나눠 갖는다 — 파티션이 갈려 한쪽이 굶는다")
                .allSatisfy((topicAndGroup, listeners) ->
                        assertThat(listeners).as(topicAndGroup).hasSize(1));
    }

    /** {@code ${some.prop:기본값}} → {@code 기본값}. 그 외에는 그대로. */
    private static String resolve(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String inner = value.substring(2, value.length() - 1);
        int colon = inner.indexOf(':');
        return colon < 0 ? value : inner.substring(colon + 1);
    }
}
