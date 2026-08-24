package github.lms.lemuel.common.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 구성 등록 파일과 실제 클래스가 어긋나지 않는지 검증한다.
 *
 * <p>자동 구성은 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 에 <b>이름을 적어야만</b> 동작한다. 클래스에 {@code @AutoConfiguration} 을 붙여놓고 파일에 적는 걸
 * 잊으면 컴파일도 되고 테스트도 통과하는데 <b>런타임에 조용히 빠진다</b> — 이 저장소가
 * 프록시 자기호출(docs/inflearn/spring.md §2)에서 겪은 것과 같은 종류의 침묵하는 실패다.
 * 양방향으로 잠근다.
 */
class AutoConfigurationImportsTest {

    private static final Path IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    private static final Path AUTOCONFIG_DIR = Path.of(
            "src/main/java/github/lms/lemuel/common/autoconfigure");

    private static List<String> declaredNames() {
        try {
            return Files.readAllLines(IMPORTS, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("등록 파일이 존재하고 비어 있지 않다")
    void importsFileExists() {
        assertThat(IMPORTS).exists();
        assertThat(declaredNames()).isNotEmpty();
    }

    @Test
    @DisplayName("파일에 적힌 클래스는 모두 실재하고 @AutoConfiguration 이 붙어 있다")
    void declaredClassesAreRealAutoConfigurations() throws ClassNotFoundException {
        for (String name : declaredNames()) {
            Class<?> type = Class.forName(name);
            assertThat(type.isAnnotationPresent(AutoConfiguration.class))
                    .as("%s 는 @AutoConfiguration 이어야 한다", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("부트의 표준 탐색 경로(ImportCandidates)로 실제 발견된다 — 파일이 클래스패스에 실려야 한다")
    void discoverableThroughBootStandardLookup() {
        List<String> discovered = new ArrayList<>();
        ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(discovered::add);

        // 파일을 직접 읽는 위 테스트들과 달리, 이것은 부트가 런타임에 쓰는 것과 같은 경로다.
        // 리소스가 jar 에 안 실리거나 경로가 틀리면 여기서만 잡힌다.
        assertThat(discovered)
                .as("자동 구성이 클래스패스에서 발견되지 않는다 — 런타임에 조용히 빠진다")
                .containsAll(declaredNames());
    }

    @Test
    @DisplayName("autoconfigure 패키지의 @AutoConfiguration 클래스가 등록 파일에서 누락되지 않았다")
    void noAutoConfigurationIsForgottenInImports() throws Exception {
        List<String> declared = declaredNames();
        List<String> onDisk;
        try (Stream<Path> files = Files.list(AUTOCONFIG_DIR)) {
            onDisk = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> "github.lms.lemuel.common.autoconfigure."
                            + p.getFileName().toString().replace(".java", ""))
                    .toList();
        }

        for (String name : onDisk) {
            if (!Class.forName(name).isAnnotationPresent(AutoConfiguration.class)) {
                continue; // 자동 구성이 아닌 보조 클래스는 대상 아님
            }
            assertThat(declared)
                    .as("%s 에 @AutoConfiguration 이 붙어 있는데 등록 파일에 없다 — 런타임에 조용히 빠진다", name)
                    .contains(name);
        }
    }
}
