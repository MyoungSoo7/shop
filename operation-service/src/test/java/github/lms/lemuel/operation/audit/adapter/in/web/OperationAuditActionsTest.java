package github.lms.lemuel.operation.audit.adapter.in.web;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터 드롭다운이 코드보다 뒤처지지 않게 지킨다.
 *
 * <p>{@link OperationAuditLogController#OPERATION_ACTIONS} 는 손으로 적은 목록이다. 새 조작에
 * {@code @Auditable} 을 붙이고 이 목록에 넣는 것을 잊으면, 그 액션은 <b>쌓이기는 하는데 필터로는
 * 고를 수 없는</b> 상태가 된다 — 감사에서 검색되지 않는 기록은 없는 것과 거의 같고, 기능은
 * 멀쩡히 돌아가므로 아무도 눈치채지 못한다.
 *
 * <p>그래서 목록을 손으로 검사하는 대신 <b>코드에 실제로 붙어 있는 애노테이션 전수</b>와 대조한다.
 * 반대 방향(목록에만 있고 코드에는 없는 유령 액션)도 같이 걸린다.
 */
class OperationAuditActionsTest {

    private static final String BASE_PACKAGE = "github.lms.lemuel.operation";

    @Test
    @DisplayName("드롭다운 목록 = 코드에 붙어 있는 @Auditable 액션 전수")
    void dropdownMatchesAnnotatedActions() throws Exception {
        Set<AuditAction> annotated = scanAnnotatedActions();

        // 스캔이 0건이면 대조가 의미 없이 통과한다 — 패키지 이름이 바뀐 경우가 그렇다.
        assertThat(annotated).as("@Auditable 이 하나도 스캔되지 않았다 — 스캔 대상 패키지를 확인하라").isNotEmpty();

        assertThat(new TreeSet<>(OperationAuditLogController.OPERATION_ACTIONS))
                .as("OPERATION_ACTIONS 가 코드와 어긋난다. 새 조작을 감사에 태웠다면 이 목록에도 넣어야 "
                        + "운영자가 필터로 고를 수 있다")
                .isEqualTo(new TreeSet<>(annotated));
    }

    private static Set<AuditAction> scanAnnotatedActions() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // 스테레오타입으로 좁히지 않는다. 감사는 서비스에만 붙는다는 보장이 없고, 좁힌 필터는
        // 새로 생긴 자리를 조용히 놓친다 — 이 테스트가 막으려는 실패 방식과 똑같다.
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        Set<AuditAction> actions = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                Auditable auditable = method.getAnnotation(Auditable.class);
                if (auditable != null) {
                    actions.add(auditable.action());
                    // failureAction 은 enum 이 아니라 문자열이다. 오타가 나면 애스펙트가 valueOf 에서
                    // 터진 뒤 로그만 남기고 action 으로 되돌아가므로, 실패 감사가 성공 액션으로
                    // 둔갑한 채 조용히 굴러간다. 여기서 이름을 실제 enum 에 부딪혀 본다.
                    if (!auditable.failureAction().isBlank()) {
                        actions.add(AuditAction.valueOf(auditable.failureAction()));
                    }
                }
            }
        }
        return actions;
    }
}
