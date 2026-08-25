package github.lms.lemuel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이 술어 의미 확인 — <b>근사 매처가 기대는 성질이 실제로 참인가</b>.
 *
 * <h2>이 테스트가 하지 않는 일</h2>
 * "모든 컨트롤러가 라우팅되는가"는 여기서 판정하지 않는다. 그 판정의 정본은
 * {@code scripts/harness/test/gateway-route-gate.test.mjs} 이고, harness-guard 워크플로가 main 으로의
 * push·PR 마다 돌린다. 분류 목록({@code NOT_ROUTED_BY_DESIGN} · {@code UNROUTED_DEBT} ·
 * {@code NEVER_ROUTED_PREFIXES})과 부채 예산도 전부 그쪽에 있다.
 *
 * <p>같은 판정을 여기서 한 번 더 하면 <b>예외 목록이 두 벌</b>이 된다. 한쪽에서 컨트롤러를 예외
 * 처리해 조용히 시켜도 다른 쪽은 계속 빨갛고, 둘이 어긋나는 날 어느 쪽이 옳은지 판정할 사람이
 * 없다 — 드리프트를 막으라고 만든 게이트 안에서 드리프트가 난다.
 *
 * <h2>이 테스트가 하는 일</h2>
 * mjs 매처({@code gateway-routes.mjs} 의 {@code matchesPattern})는 스스로 밝히듯 스프링
 * {@code PathPattern} 의 <b>근사</b>다. 근사가 기대는 성질 중 라우트 표 전체가 올라타 있는 것이
 * 하나 있다 — {@code /a/**} 가 {@code /a} 자신도 매칭한다는 것. 이 성질이 거짓이면 컬렉션 루트
 * ({@code GET /admin/refunds}, {@code GET /admin/order-queues})가 전부 404 가 되는데, mjs 게이트는
 * 자기 정규식을 근거로 초록불을 유지한다. 자기 가정을 자기가 검증하기 때문이다.
 *
 * <p>그래서 여기서는 {@link RouteLocator} 에서 <b>실제 라우트</b>를 꺼내 그 술어에 요청을 흘려
 * 확인한다. 이 모듈만 그렇게 물어볼 수 있다 — mjs 는 스프링을 띄우지 못하고, 띄우지 못하니
 * 자기 근사가 맞는지도 스스로는 알 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRouteCoverageTest {

    /** {@code {id}} · {@code {id:[0-9]+}} 형태의 경로 변수. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^{}]*}");

    private static final Pattern PATH_PREDICATE = Pattern.compile("^\\s*-\\s*Path=(.+)$", Pattern.MULTILINE);

    @Autowired
    RouteLocator routeLocator;

    /**
     * {@code /a/**} 가 {@code /a} 를 먹는다 — 라우트 표가 통째로 이 성질에 기대고 있다.
     *
     * <p>패턴 하나만 찍어 보지 않고 <b>전부</b> 도는 이유: 새 라우트가 이 규칙이 안 통하는 형태로
     * 들어올 수 있다. 실제로 {@code /api/notifications/stream} 처럼 와일드카드 없이 정확히 한 경로만
     * 여는 것이 이미 있고, 그런 것은 이 성질과 무관하므로 대상에서 빠져야 한다.
     */
    @Test
    @DisplayName("꼬리 /** 는 0개 세그먼트도 매칭한다 — 컬렉션 루트가 여기에 올라타 있다")
    void trailingWildcardCoversItsOwnPrefix() {
        List<String> broken = new ArrayList<>();
        for (String pattern : wildcardPatterns()) {
            String prefix = pattern.substring(0, pattern.length() - "/**".length());
            if (!isRouted(prefix)) {
                broken.add(prefix + "  (" + pattern + ")");
            }
        }

        assertThat(broken)
                .as("꼬리 /** 가 자기 접두사를 못 먹는다. 스프링 PathPattern 의 의미가 바뀌었다면"
                        + " scripts/harness/lib/gateway-routes.mjs 의 matchesPattern 도 함께 틀린 것이다.%n%s",
                        String.join("\n", broken))
                .isEmpty();
    }

    /** 반대편 — 접두사보다 깊은 경로도 당연히 먹어야 한다. 한쪽만 보면 참인 이유를 오해한다. */
    @Test
    @DisplayName("꼬리 /** 는 하위 경로도 매칭한다")
    void trailingWildcardCoversDeeperPaths() {
        for (String pattern : wildcardPatterns()) {
            String prefix = pattern.substring(0, pattern.length() - "/**".length());
            assertThat(isRouted(prefix + "/deeper/still"))
                    .as("하위 경로를 못 먹는다: %s", pattern)
                    .isTrue();
        }
    }

    /**
     * 이 테스트가 <b>실제로 무언가를 판정하는지</b> 확인한다. 술어 평가가 어떤 이유로든 늘 참을
     * 돌려주면 위 두 테스트는 초록인 채로 아무것도 지키지 않는다.
     */
    @Test
    @DisplayName("어디에도 없는 경로는 걸리지 않는다")
    void unknownPathIsNotRouted() {
        assertThat(isRouted("/no-such-prefix/whatever")).isFalse();
    }

    // ── 내부 ──────────────────────────────────────────────────

    /**
     * 실제 라우트 술어에 GET 요청을 흘려 본다.
     *
     * <p>경로 변수가 섞여 있으면 {@link MockServerHttpRequest#get} 이 URI 템플릿으로 읽어 값이
     * 없다고 죽으므로, 실제 요청이 그렇듯 구체적인 한 세그먼트로 바꿔 흘린다.
     */
    private boolean isRouted(String path) {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull().isNotEmpty();

        String concrete = PATH_VARIABLE.matcher(path).replaceAll("1");
        return routes.stream().anyMatch(route -> {
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(MockServerHttpRequest.get(concrete).build());
            return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block());
        });
    }

    /**
     * 꼬리 {@code /**} 로 끝나는 선언 패턴 — 위 두 테스트의 대상.
     *
     * <p>여기서 <b>비어 있지 않음</b>을 못박는 자리이기도 하다. 추출이 어떤 이유로든 실패하면
     * (yml 구조 변경, 정규식 오타) 호출부 루프가 0 회 돌면서 두 테스트가 나란히 초록으로 뜬다 —
     * 지키는 것 없이 지키는 척하는 상태. 두 테스트가 같은 헬퍼를 쓰는 이유도 그것이다.
     */
    private static List<String> wildcardPatterns() {
        List<String> patterns = declaredPatterns().stream().filter(p -> p.endsWith("/**")).toList();
        assertThat(patterns)
                .as("게이트웨이 yml 에서 Path 술어를 못 읽었다 — 이 테스트가 무력해졌다")
                .hasSizeGreaterThan(30);
        return patterns;
    }

    /**
     * yml 에 적힌 Path 술어 문자열.
     *
     * <p>매칭 <b>판정</b>은 실제 라우트에 맡기고, yml 은 "무엇을 물어볼지" 목록으로만 읽는다.
     * {@link RouteLocator} 는 조립된 술어만 주고 원본 패턴 문자열은 돌려주지 않기 때문이다.
     */
    private static List<String> declaredPatterns() {
        Path yml = repoRoot().resolve("gateway-service/src/main/resources/application.yml");
        List<String> patterns = new ArrayList<>();

        Matcher matcher = PATH_PREDICATE.matcher(read(yml));
        while (matcher.find()) {
            for (String raw : matcher.group(1).split(",")) {
                String pattern = raw.trim();
                if (pattern.startsWith("/")) {
                    patterns.add(pattern);
                }
            }
        }
        return patterns;
    }

    /** 테스트 작업 디렉터리(모듈 디렉터리)에서 위로 올라가 settings.gradle.kts 가 있는 곳을 찾는다. */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("settings.gradle.kts 를 못 찾았다 — 리포 루트를 정할 수 없다").isNotNull();
        return dir;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
