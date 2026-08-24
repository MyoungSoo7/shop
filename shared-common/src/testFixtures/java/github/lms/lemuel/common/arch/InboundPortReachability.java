package github.lms.lemuel.common.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 인바운드 포트 <b>도달 가능성</b> 가드 — 서비스 공용 ArchUnit 조건.
 *
 * <p>해결하는 결함 유형: 유스케이스를 구현하고 단위 테스트까지 붙였는데 <b>아무 어댑터도 호출하지 않아</b>
 * 그 기능이 런타임에 존재하지 않는 상태. 컴파일러도 단위 테스트도 "부르는 사람이 없다"를 보지 못한다.
 * 실제로 loan 의 담보 재평가·강제집행(마진콜 140%·청산 120%)과 card 의 명세서 개시가 이 상태였다 —
 * 정책 상수도 서비스도 초록불 테스트도 있는데 트리거가 없었다.
 *
 * <p><b>왜 직접 참조로는 부족한가</b>: 정상적인 내부 오케스트레이션이 흔하다.
 * {@code 폴러(어댑터) → 아웃박스 서비스 → CreateLedgerEntryUseCase} 처럼 어댑터가 포트를 간접 호출하는
 * 구조를 직접 참조만으로 판정하면 멀쩡한 설계를 위반으로 잡는다(settlement·order 에서 실측 7건).
 * 그래서 <b>전이적</b> 도달을 본다.
 *
 * <p><b>왜 구현 클래스까지 따라가는가</b>: 스프링 DI 에서 어댑터는 인터페이스에만 의존하고 구현체는
 * 런타임에 주입된다. 바이트코드 의존만 따라가면 인터페이스에서 탐색이 끊겨, 구현체가 호출하는 다른
 * 포트를 놓친다. 그래서 도달한 타입의 <b>하위 타입(구현체)</b>도 함께 큐에 넣는다.
 */
public final class InboundPortReachability {

    private static final String INBOUND_ADAPTER_MARKER = ".adapter.in.";
    /**
     * 탐색 범위 — 프로젝트 패키지 밖(JDK·프레임워크)으로 나가지 않는다.
     * 나가면 {@code java.lang.Object} 에 도달하고, 그 하위 타입은 사실상 전 클래스라
     * 도달 집합이 전체가 되어 가드가 <b>조용히 무력화</b>된다(실제로 첫 구현이 그렇게 통과했다).
     */
    private static final String PROJECT_PACKAGE = "github.lms.lemuel";

    private InboundPortReachability() {
    }

    /**
     * 인바운드 어댑터에서 (직접 또는 간접으로) 도달 가능해야 한다는 조건.
     *
     * @param allClasses 도달 집합을 계산할 전체 클래스(보통 서비스 루트 패키지 임포트 결과)
     */
    public static ArchCondition<JavaClass> reachableFromInboundAdapter(JavaClasses allClasses) {
        Set<String> reachable = computeReachable(allClasses);
        return new ArchCondition<>("인바운드 어댑터에서 (직·간접으로) 호출되어야 한다") {
            @Override
            public void check(JavaClass port, ConditionEvents events) {
                if (!reachable.contains(port.getName())) {
                    events.add(SimpleConditionEvent.violated(port,
                            port.getName() + " 에 도달하는 인바운드 어댑터 경로가 없다 "
                                    + "— 구현돼 있어도 런타임에서 실행될 수 없다"));
                }
            }
        };
    }

    /** 인바운드 어댑터를 시작점으로 의존 간선 + 하위 타입을 따라간 도달 집합. */
    private static Set<String> computeReachable(JavaClasses allClasses) {
        Set<String> visited = new HashSet<>();
        Deque<JavaClass> queue = new ArrayDeque<>();
        for (JavaClass candidate : allClasses) {
            if (candidate.getPackageName().contains(INBOUND_ADAPTER_MARKER) && visited.add(candidate.getName())) {
                queue.add(candidate);
            }
        }
        while (!queue.isEmpty()) {
            JavaClass current = queue.poll();
            current.getDirectDependenciesFromSelf().forEach(dependency -> {
                enqueue(dependency.getTargetClass().getBaseComponentType(), visited, queue);
            });
            // DI 경계 넘기 — 인터페이스에 도달하면 그 구현체도 실행 경로에 포함된다.
            current.getAllSubclasses().forEach(subclass -> enqueue(subclass, visited, queue));
        }
        return visited;
    }

    private static void enqueue(JavaClass candidate, Set<String> visited, Deque<JavaClass> queue) {
        if (!candidate.getPackageName().startsWith(PROJECT_PACKAGE)) {
            return;
        }
        if (visited.add(candidate.getName())) {
            queue.add(candidate);
        }
    }
}
