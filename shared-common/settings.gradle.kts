// shared-common 을 독립 Gradle 빌드로 분리(MSA: 버전드 내부 라이브러리).
// 루트(lemuel) 는 이 빌드를 includeBuild 로 합성(composite build)해 로컬 개발 시 자동 치환하고,
// 배포/폴리레포 시에는 publish 된 아티팩트(github.lms.lemuel:shared-common:<version>)로 소비한다.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "shared-common"
