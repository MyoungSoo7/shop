plugins {
    java
    // 4.0.4 → 4.0.7: CVE-2026-40976(CRITICAL, 기본 웹 시큐리티 무력화)를 비롯해 Boot BOM 이 관리하는
    // spring-*, spring-security, spring-kafka, spring-data, tomcat-embed, netty, jackson, micrometer 의
    // HIGH/CRITICAL 이 이 한 줄로 함께 올라간다. 근거·잔여 목록은 .trivyignore.yaml.
    //
    // 4.0.6 이 아니라 4.0.7 인 이유(SBOM 실측): 4.0.6 이 물고 오는 관리 버전은 수정판보다 딱 한 패치
    // 아래라(tomcat 11.0.21 vs 11.0.22, netty 4.2.12 vs 4.2.13+, jackson 2.21.2 vs 2.21.4) 부채가
    // 38건 남는다. 같은 4.0.x 라인의 4.0.7 은 8건까지 줄인다 — 48 → 38(4.0.6) → 8(4.0.7).
    // shared-common/build.gradle.kts 의 spring-boot-dependencies 좌표와 반드시 같은 값이어야 한다
    // (composite build 로 로컬 치환되므로 버전이 갈리면 서비스마다 다른 BOM 이 섞인다).
    id("org.springframework.boot") version "4.0.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    jacoco
    // 5.1.0.4882 는 Gradle 9 에서 제거된 Convention API 를 호출해 sonar 태스크가
    // NoSuchMethodError 로 즉사했다(2026-08-10 run 31432558450, "BUILD FAILED in 13s").
    // continue-on-error 가 이 실패를 삼켜서, SonarCloud 분석은 한 번도 올라간 적이 없다.
    id("org.sonarqube") version "7.4.0.8496"
    // SCA(의존성 취약점) 게이트용 SBOM 생성. Trivy 가 이 BOM 을 스캔한다(ci.yml backend-ci).
    id("org.cyclonedx.bom") version "3.4.1"
}

allprojects {
    group = "github.lms.lemuel"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// 커버리지 게이트가 "공전"하는 것을 실행 시점에 잡는 스모크.
//
// classDirectories 는 설정 시점에 즉시 평가되므로, 클린 빌드(`clean :module:build`)처럼
// build/classes 가 아직 없는 상태에서 스냅샷되면 측정 대상이 빈 집합으로 굳는다. 그러면
// 리포트는 클래스 0개로 나오고 LINE 90% 검증은 "위반 없음"으로 통과한다 — 게이트가 켜져
// 있는데 아무것도 재지 않는다. 2026-08-19 에 deposit·board·education 이 정확히 이 상태였고,
// 빌드는 초록이었다(d87c43fc). 대상이 0개인지는 반드시 *실행 시점*(doFirst)에 확인해야 한다.
fun requireNonEmptyCoverageScope(taskName: String, classDirectories: FileCollection) {
    val measured = classDirectories.asFileTree.matching { include("**/*.class") }.files.size
    require(measured > 0) {
        "$taskName 의 커버리지 측정 대상이 0개다 — 게이트가 공전한다. " +
            "모듈 build.gradle.kts 가 classDirectories 를 다시 얹지 않았는지, " +
            "제외 패턴이 모듈 전체를 삼키지 않았는지 확인할 것."
    }
}

// 측정 대상 0개가 *정상*인 모듈. 잴 코드가 없는 것이지 게이트가 공전하는 것이 아니다.
// gateway-service 는 Spring Cloud Gateway 라우팅 설정만 있는 모듈로 자바 소스가
// GatewayServiceApplication 하나뿐이고, 그 하나는 아래 검증 제외 목록에 이미 들어 있다.
// 여기에 모듈을 추가할 때는 "왜 잴 코드가 없는지"를 반드시 같이 적을 것.
val modulesWithoutMeasurableCode = setOf("gateway-service")

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    // -parameters: 메서드 파라미터 이름을 바이트코드에 보존한다.
    // Spring Data 가 @Param 없이도 @Query 의 이름 파라미터(:name)를 파라미터명으로 추론 →
    // 파라미터 바인딩 누락으로 인한 런타임 실패(PostgreSQL 42P18 "indeterminate datatype",
    // 폴링 쿼리 ERROR 폭주로 인한 ELK 색인 과부하) 계열의 1차 방어선.
    // (2차 방어선: 각 서비스 아키텍처 테스트의 @Query↔@Param 매칭 ArchUnit 룰)
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.0")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Testcontainers(Redpanda/ES/PG) + 다수 @SpringBootTest 컨텍스트가 하나의 test JVM 에
        // 누적되어 기본 힙(러너 RAM 25%)을 초과, OutOfMemoryError→스레드 사망→hang→CI 90분
        // 타임아웃을 유발했다. 힙 상한을 명시하고, JVM 을 주기적으로 재활용해 누적된 스프링
        // 컨텍스트 캐시·컨테이너 자원을 방출한다. (OOM 시 원인 규명을 위해 힙덤프 남김)
        maxHeapSize = "3g"
        setForkEvery(50)
        // 프로덕션은 KST Clock 을 주입하는데(TimeConfig) 다수 정산 테스트가 bare LocalDate.now()/
        // LocalDateTime.now() 로 기준일을 단정한다. CI 러너(UTC)에서 KST 자정~오전9시 창에 날짜가
        // 하루 어긋나 off-by-one 단정 실패(SettlementDate 등)를 유발했다. 테스트 JVM 을 프로덕션과
        // 동일한 KST 로 고정해 이 클래스의 타임존 플레이키를 결정적으로 제거한다.
        systemProperty("user.timezone", "Asia/Seoul")
        // Outbox 폴러(2초 주기)는 테스트에서 얻는 게 없고 잃는 게 있다. 컨텍스트가 내려갈 때
        // Hikari 셧다운과 경합해 "Failed to validate connection ... This connection has been closed"
        // 류의 WARN/ERROR 를 매 실행 수십 줄 남긴다(실측: run 31676479927 의 card-service 구간 42줄).
        // 빌드는 초록인데 로그만 붉어서, 진짜 DB 문제가 났을 때 이 노이즈에 묻힌다.
        // 발행기 빈(OutboxPublisherScheduler)은 그대로 남으므로, 폴링에 기대지 않고 수동 호출로
        // 발행을 검증하는 order-service KafkaOutboxIntegrationTest 는 영향받지 않는다.
        systemProperty("app.outbox.polling.enabled", "false")
        jvmArgs("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=build/test-heapdump.hprof")
        finalizedBy(tasks.named("jacocoTestReport"))
        // JWT 서명키는 운영에서 env(JWT_SECRET)로만 주입한다(yaml 기본값 없음 = 미설정 시 기동 실패).
        // 테스트도 동일하게 env 로 공급해, 풀 컨텍스트(@SpringBootTest) 부팅 시 ${JWT_SECRET} 미해결
        // 실패를 막는다. 운영 배포는 반드시 강한 JWT_SECRET 을 주입할 것.
        environment("JWT_SECRET", "test-only-jwt-secret-not-for-production-0123456789")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        // jacocoTestReport 는 main 소스셋 출력(build/classes/java/main + build/resources/main)을
        // classDirectories/sourceDirectories 로 읽는다. -x test 로 test 를 건너뛰면
        // test→classes 경로가 끊겨 Gradle 9 의 implicit-dependency 검증에 걸리므로,
        // compileJava·processResources 를 aggregate 하는 classes 를 명시적으로 선언한다.
        dependsOn(tasks.named("classes"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
        // QueryDSL 생성 클래스(Q*)는 자동 생성 코드 → 커버리지 측정에서 제외 (전 모듈 공통)
        classDirectories.setFrom(classDirectories.files.map { dir ->
            fileTree(dir) { exclude("**/Q*.class") }
        })
        if (project.name !in modulesWithoutMeasurableCode) {
            doFirst { requireNonEmptyCoverageScope("jacocoTestReport", classDirectories) }
        }
    }

    // 커버리지 임계값. CI 에서 회귀 시 즉시 빌드 실패 → PR 차단.
    // LINE 90% 목표 (persistence adapter 는 TestContainers IT 에서 별도 검증).
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("jacocoTestReport"))

        // persistence adapter, config, mapper 는 통합 테스트 대상 → 단위 테스트 커버리지에서 제외
        classDirectories.setFrom(classDirectories.files.map { dir ->
            fileTree(dir) {
                exclude(
                    "**/adapter/out/persistence/**",
                    "**/adapter/out/readmodel/**",
                    "**/adapter/out/search/**",
                    "**/adapter/out/event/**",
                    "**/adapter/out/pdf/**",
                    "**/adapter/out/external/**",
                    "**/adapter/out/notification/**",
                    "**/adapter/out/mail/**",
                    "**/adapter/out/security/**",
                    "**/adapter/out/monitoring/**",
                    "**/adapter/out/user/**",
                    "**/adapter/out/pg/**",
                    "**/adapter/out/llm/**",
                    "**/adapter/in/web/**",
                    "**/adapter/in/kafka/**",
                    "**/adapter/in/batch/**",
                    "**/adapter/in/api/**",
                    "**/adapter/in/dto/**",
                    "**/config/**",
                    "**/util/**",
                    // 부트 진입점(@SpringBootApplication main)은 측정 대상이 아니다.
                    // 이 저장소에 실재하는 세 개만 적는다 — settlement 시절의 13개를 그대로 두면
                    // "이 서비스들도 여기 있다"는 인상만 남기고 아무것도 제외하지 않는다.
                    // 새 서비스를 추가하면 여기에도 한 줄 추가할 것.
                    "**/LemuelApplication*",            // order-service
                    "**/GatewayServiceApplication*",    // gateway-service
                    "**/OperationServiceApplication*",  // operation-service
                )
            }
        })

        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.90".toBigDecimal()
                }
            }
            // 핵심 도메인은 더 엄격하게
            rule {
                element = "PACKAGE"
                includes = listOf(
                    "github.lms.lemuel.payment.domain.*",
                    "github.lms.lemuel.point.domain.*",
                    "github.lms.lemuel.order.domain.*",
                    "github.lms.lemuel.product.domain.*",
                    "github.lms.lemuel.cart.domain.*",
                    "github.lms.lemuel.shipping.domain.*",
                    // common.outbox.domain 게이트는 shared-common 독립 빌드로 이관됨
                )
                limit {
                    counter = "INSTRUCTION"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }

        if (project.name !in modulesWithoutMeasurableCode) {
            doFirst { requireNonEmptyCoverageScope("jacocoTestCoverageVerification", classDirectories) }
        }
    }

    // check 가 호출되면 커버리지 검증도 같이 — CI 의 ./gradlew check 가 자동 강제
    tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }

    // JaCoCo XML 을 Sonar 에 넘긴다 — 이게 없어서 SonarCloud 커버리지가 0.0% 로 잡혀 있었다
    // (2026-08-12 실측: coverage 0.0% · new_coverage 0.0%, 저장소 자체 게이트는 LINE 90% 인데도).
    // 멀티모듈은 모듈별로 경로를 줘야 한다 — 루트에 한 번 적으면 서브모듈 리포트를 못 찾는다.
    // 파일은 ci.yml 의 `clean build`(=jacocoTestReport 포함)가 만들고, 그다음 `./gradlew sonar`
    // 호출이 읽는다.
    //
    // ⚠ "리포트가 없는 모듈은 Sonar 가 그냥 넘어간다"는 오해였다 (2026-08-15 반증).
    //   ci.yml 의 backend-test 매트릭스는 **변경된 모듈만** 돌린다. 안 돌린 모듈은 JaCoCo XML 이
    //   없는데, 프로젝트에 커버리지 데이터가 하나라도 있으면 Sonar 는 데이터 없는 파일을
    //   "미커버(0%)"로 집계한다 — 넘어가지 않는다. 실측(develop 분석 2026-08-15T07:52):
    //     order 0.0%(8,032줄) · investment 0.0% · deposit 0.0% · operation 0.0% · ai 0.0% ·
    //     commondata 0.0% · financial 0.0% · market 0.0% · economics 0.0% · organization 0.0%
    //   17개 중 11개가 이렇게 0% 로 들어가 전체 coverage 가 57.7% 로 찍혔고, new_coverage 57.8%
    //   < 80% 로 품질 게이트가 ERROR 였다. 테스트가 없어서가 아니라 이번 실행에서 안 돌아서다.
    //
    //   그래서 이번 실행에 리포트가 없는 모듈은 **커버리지 집계에서만** 뺀다(sonar.coverage.exclusions).
    //   issues·중복 분석은 그대로 돈다 — 빠지는 건 분모뿐이다. 0% 로 거짓 보고하느니
    //   "이번 실행에서는 측정 안 함"이 정직하다.
    //   exists() 는 설정 시점 판정이라 안전하다: XML 은 Gradle 이 뜨기 전에 ci.yml 의
    //   `Restore report paths` 스텝이 이미 복원해 두고, sonar 태스크 그래프에는 test 가 없다.
    sonar {
        properties {
            val jacocoXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
            property("sonar.coverage.jacoco.xmlReportPaths", jacocoXml.path)
            if (!jacocoXml.exists()) {
                property("sonar.coverage.exclusions", "**/*")
                logger.lifecycle("[sonar] ${project.name}: JaCoCo XML 없음 → 커버리지 집계 제외(0% 오보고 방지)")
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "MyoungSoo7_shop")
        property("sonar.organization", "myoungsoo7")
        property("sonar.host.url", "https://sonarcloud.io")
        // 일부러 false 다 — "분석이 안 돌았다"는 막고, "품질 게이트 내용"은 아직 막지 않는다.
        //
        // ci.yml 의 continue-on-error 는 제거된 상태이므로 플러그인 크래시·토큰 만료처럼
        // *분석 자체가 실패*하면 backend-ci 가 깨진다(원래 결함은 닫힌 상태). 여기서 wait 까지
        // true 로 두면 6개월치 누적 부채가 그대로 차단으로 들어온다 — 실측(2026-08-12,
        // sonarcloud api/qualitygates/project_status, 마지막 분석 2026-02-26 기준):
        //   status=ERROR · new_reliability_rating 3(A 요구) · new_coverage 0.0%(80% 요구)
        // 커버리지 0% 는 테스트가 없어서가 아니라 JaCoCo XML 이 Sonar 로 전달된 적이 없어서다
        // (이 블록에 sonar.coverage.jacoco.xmlReportPaths 가 없다. 저장소 자체 게이트는 LINE 90%).
        //
        // true 로 조이는 순서: ① JaCoCo XML 을 Sonar 에 연결해 커버리지 0% 해소
        //   → ② bugs 3건 정리(new_reliability_rating A) → ③ 그때 이 값을 true 로.
        property("sonar.qualitygate.wait", false)
        property("sonar.java.source", "25")

        // ── 규칙 제외 2건 (코드/워크플로 수정으로는 만족시킬 수 없는 것만) ─────────────────
        // 나머지 신규 코드 지적은 전부 코드에서 처리했다: 실결함은 수정, "안전한지 확인하라" 류는
        // 근거를 적은 @SuppressWarnings 로 호출부에 남겼다. 여기 두 건은 성격이 다르다.
        //
        // e1) githubactions:S8544 — polyglot-ci 의 `pip install -r requirements*.txt`.
        //     세 파이썬 서비스의 의존성은 requirements.txt 에 `==` 로 이미 고정돼 있고 dependabot 이
        //     갱신한다. 이 규칙이 추가로 요구하는 건 `--require-hashes`(전이 의존까지 해시 고정)인데,
        //     그건 세 서비스의 의존 그래프를 플랫폼별 해시로 잠그고 유지하는 별도 과제다.
        //     (같은 스텝의 sdist 실행 경로 차단(S8541)은 `--only-binary :all:` 로 실제 적용했다.)
        // e2) text:S8569 — Gradle 의존성 잠금파일(gradle.lockfile / verification-metadata.xml) 부재.
        //     17개 모듈 전체에 락을 쓰고 유지하는 결정이 필요한 사안이라 정적분석 경고로 처리하지 않는다.
        property("sonar.issue.ignore.multicriteria", "e1,e2")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "githubactions:S8544")
        property("sonar.issue.ignore.multicriteria.e1.resourceKey", ".github/workflows/polyglot-ci.yml")
        property("sonar.issue.ignore.multicriteria.e2.ruleKey", "text:S8569")
        property("sonar.issue.ignore.multicriteria.e2.resourceKey", "**/build.gradle.kts")

        // ── 브랜치·신규 코드 기준선 (서버 측 설정 — 이 파일에서 바꾸는 값이 아니다) ──────────
        // 해소됨: 스캐너의 "Could not find ref 'master'" 는 주 브랜치가 master 로 등록돼 있어서였고,
        // 지금은 서버 주 브랜치가 `develop` 이다(2026-08-15 api/project_branches/list 실측:
        // develop isMain=true LONG / main 은 mergeBranch=develop 인 SHORT — 분석 이력 없음).
        //
        // 여기서 sonar.branch.name 을 GITHUB_REF_NAME 으로 주는 우회는 여전히 하지 않는다:
        // develop 이 신규 "브랜치"로 갈라져 등록되면 신규 코드 기준선이 이력 없는 상태에서 다시 잡힌다.
        //
        // ⚠ 신규 코드 정의는 2026-08-15 에 previous_version → **30일(sonar.leak.period=30)** 로 바꿨다
        //   (SonarCloud api/settings/set, component 스코프 — 인스턴스/조직 값은 건드리지 않았다).
        //   바꾸기 전 실측: mode=previous_version · 기준선 date=2026-02-24 · parameter="not provided".
        //   프로젝트 버전이 계속 0.0.1-SNAPSHOT 이라 기준선이 갱신되지 않았고, 그 결과
        //   new_lines_to_cover 36,820 / lines_to_cover 37,624 = **코드베이스의 98% 가 "신규 코드"**였다.
        //   그래서 new_coverage(57.8%) 가 사실상 전체 coverage(57.7%) 와 같아졌고 80% 게이트에서
        //   항상 ERROR 였다 — PR diff 와는 무관한 실패다.
        //   PR #263 처럼 head 가 develop 인 릴리스 PR 은 Sonar 에 PR 분석이 잡히지 않고
        //   (api/project_pull_requests/list = []) develop **브랜치 분석**의 게이트가 체크로 붙는다.
    }
}

// 분석 전에 전 모듈이 컴파일돼 있게 강제한다.
//
// ci.yml 은 변경 모듈만 빌드하고(`:<module>:build`) 그다음 별도 Gradle 호출로 `sonar` 를 돌린다.
// 그래서 안 건드린 모듈에는 .class 가 없고, 스캐너가 "Binary paths (sonar.java.binaries) are empty"
// 를 남긴다(2026-08-12 run 31611508158 실측). 바이너리가 없으면 타입 해석이 반쪽이 되어
// ("Unresolved imports/types have been detected") 자바 규칙 상당수가 조용히 비활성화된다 —
// 분석이 "돌았다"와 "제대로 봤다"는 다르다. 컴파일 시간을 조금 더 쓰고 분석의 신뢰도를 산다.
// `classes` 가 아니라 `compileJava` 다. classes 를 걸면 processResources 가 그래프에 들어오고,
// 플러그인의 sonarResolver 가 그 산출 디렉터리를 선언 없이 읽어 Gradle 이 암묵 의존성 위반으로
// 빌드를 깬다(실측: "Declare task ':account-service:processResources' as an input of
// ':account-service:sonarResolver'"). 스캐너에 필요한 건 .class 뿐이므로 컴파일만 요구한다.
tasks.named("sonar") {
    dependsOn(subprojects.map { "${it.path}:compileJava" })
}
