package github.lms.lemuel.architecture;

import github.lms.lemuel.order.adapter.out.persistence.SpringDataOrderJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 빌드 게이트 — {@code @Query} 의 이름 파라미터({@code :name})마다 대응하는
 * {@code @Param("name")} 이 선언돼 있는지 강제한다.
 *
 * <p><b>왜:</b> 파라미터 바인딩이 누락된 채 배포된 폴링/조회 쿼리는 런타임에서만 터진다 —
 * PostgreSQL {@code 42P18}("indeterminate datatype"), 그리고 2초 주기 폴링 쿼리가
 * 매번 예외를 던져 ERROR 스택트레이스가 폭증 → Elasticsearch 색인 과부하로
 * 관측 인프라가 먼저 죽는 사고(ELK 폭주)로 이어진다. 단위 테스트는 통과하고 배포 후에
 * 터지는 계열이라, <b>빌드 단계에서 정적으로</b> 차단해야 재발하지 않는다.
 *
 * <p><b>이중 방어:</b> 1차는 root {@code build.gradle.kts} 의 {@code -parameters}
 * (Spring Data 가 파라미터명으로 이름을 추론). 2차가 이 게이트(@Param 명시 강제)다.
 *
 * <p><b>구현 노트:</b> ArchUnit 의 ClassFileImporter 대신 <b>JVM 리플렉션</b>으로 스캔한다.
 * order-service 에 핀된 ArchUnit 1.3.0 의 ASM 은 Java 25 바이트코드(class major 69)를
 * 읽지 못해 0개를 임포트하기 때문이다(이 사실 자체가 별도 이슈 — 1.3.0 핀 모듈들의
 * 아키텍처 테스트가 Java 25 에서 공허 통과 중). 리플렉션은 실행 JVM 의 클래스로더로
 * 로드하므로 버전에 무관하게 동작한다.
 */
class QueryParamBindingGateTest {

    /**
     * {@code :name} 을 추출한다. 단:
     * <ul>
     *   <li>{@code ::cast}(PostgreSQL 타입 캐스트) — 콜론 앞이 콜론/단어문자면 제외</li>
     *   <li>{@code :#{SpEL}} — {@code #} 는 식별자 시작이 아니므로 자연 제외</li>
     *   <li>시간/텍스트 리터럴 {@code '12:00'} — 식별자는 문자/underscore 로 시작해야 하므로 제외</li>
     * </ul>
     */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<![:\\w]):([a-zA-Z_][a-zA-Z0-9_]*)");

    @Test
    void everyNamedBindingInQueryHasMatchingParam() throws Exception {
        List<Class<?>> mainClasses = loadMainClasses();

        int scanned = 0;
        List<String> violations = new ArrayList<>();
        for (Class<?> type : mainClasses) {
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (Throwable t) {
                continue; // 링크 불가 클래스는 건너뜀
            }
            for (Method method : methods) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                Set<String> referenced = new LinkedHashSet<>();
                referenced.addAll(namedParams(query.value()));
                referenced.addAll(namedParams(query.countQuery()));
                if (referenced.isEmpty()) {
                    continue; // 이름 파라미터가 없는 쿼리는 대상 아님
                }
                scanned++;

                Set<String> declared = new LinkedHashSet<>();
                for (Parameter parameter : method.getParameters()) {
                    Param param = parameter.getAnnotation(Param.class);
                    if (param != null) {
                        declared.add(param.value());
                    }
                }
                Set<String> missing = new LinkedHashSet<>(referenced);
                missing.removeAll(declared);
                if (!missing.isEmpty()) {
                    violations.add(type.getName() + "#" + method.getName()
                            + " 의 @Query 는 " + missing + " 를 참조하지만 대응 @Param 이 없다 (선언된 @Param: "
                            + declared + ")");
                }
            }
        }

        assertTrue(scanned > 0,
                "이름 파라미터를 가진 @Query 를 하나도 스캔하지 못했다 — 앵커/클래스패스 문제 (스캔한 클래스 "
                        + mainClasses.size() + "개)");
        assertTrue(violations.isEmpty(),
                "다음 @Query 는 이름 파라미터에 대응하는 @Param 이 없다 — 42P18/ELK 폭주 계열 재발 위험:\n"
                        + String.join("\n", violations));
    }

    /** order-service 의 main 컴파일 출력 디렉터리를 앵커 클래스로부터 찾아 모든 클래스를 로드한다. */
    private static List<Class<?>> loadMainClasses() throws Exception {
        Path root = Paths.get(
                SpringDataOrderJpaRepository.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> classes = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return classes; // 디렉터리 출력(build/classes/java/main)만 대상 — jar 패키징 시엔 스킵
        }
        ClassLoader loader = QueryParamBindingGateTest.class.getClassLoader();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                String fqn = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                if (fqn.contains("$") || fqn.equals("module-info") || fqn.endsWith("package-info")) {
                    continue; // 내부/모듈/패키지 정보 클래스 제외 (@Query 는 top-level 리포지토리에 있음)
                }
                try {
                    classes.add(Class.forName(fqn, false, loader));
                } catch (Throwable ignore) {
                    // 로드/링크 실패 클래스는 조용히 스킵 (선택적 의존성 등)
                }
            }
        }
        return classes;
    }

    private static Set<String> namedParams(String query) {
        Set<String> names = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return names;
        }
        // '문자열 리터럴' 내부의 콜론(시간/텍스트) 오탐을 먼저 제거
        String stripped = query.replaceAll("'[^']*'", " ");
        Matcher matcher = NAMED_PARAM.matcher(stripped);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** 파서 회귀 — ::cast, :#{SpEL}, '리터럴' 오탐 없이 실제 :name 만 뽑는지 검증. */
    @Test
    void parserExtractsOnlyRealNamedParams() {
        assertEquals(Set.of("status"),
                namedParams("SELECT e FROM E e WHERE e.status = :status"));
        assertEquals(Set.of("id", "cat"),
                namedParams("SELECT e FROM E e WHERE e.id = :id AND (:cat IS NULL OR e.c = :cat)"));
        assertEquals(Set.of("key", "orderId"),
                namedParams("INSERT INTO t (k, o) VALUES (:key, :orderId)"));
        assertTrue(namedParams("SELECT x::text FROM t").isEmpty(),
                "PostgreSQL ::cast 는 파라미터가 아니다");
        assertTrue(namedParams("SELECT e FROM E e WHERE e.tenant = :#{tenantId}").isEmpty(),
                ":#{SpEL} 은 이름 파라미터가 아니다");
        assertTrue(namedParams("SELECT t FROM T t WHERE t.at > '12:00'").isEmpty(),
                "시간 리터럴 '12:00' 은 파라미터가 아니다");
    }
}
