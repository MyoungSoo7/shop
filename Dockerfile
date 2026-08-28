############################
# Stage 1: Build (parameterized)
#   docker build --build-arg MODULE=order-service .
#   docker build --build-arg MODULE=operation-service .
#   docker build --build-arg MODULE=gateway-service .
#   docker build --build-arg MODULE=marketing-service .
############################
FROM gradle:9.7.0-jdk25 AS builder
ARG MODULE
WORKDIR /workspace

# 의존성 캐싱: 변경 적은 파일 먼저
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# shared-common 은 독립 빌드(includeBuild) — 합성 설정에 자체 settings 가 필요
COPY shared-common/settings.gradle.kts shared-common/build.gradle.kts ./shared-common/
COPY order-service/build.gradle.kts ./order-service/
COPY operation-service/build.gradle.kts ./operation-service/
COPY gateway-service/build.gradle.kts ./gateway-service/
# settings.gradle.kts 가 include 한 모듈은 *전부* 여기 있어야 한다. 하나라도 빠지면 그 모듈만
# 못 만드는 게 아니라 설정 단계가 통째로 죽어 **모든 모듈의 이미지 빌드**가 같이 실패한다.
COPY marketing-service/build.gradle.kts ./marketing-service/

# 캐시 id 를 모듈별로 가른다(id=gradle-${MODULE}).
#
# 이유: id 를 생략하면 BuildKit 이 target 경로로 캐시 ID 를 만들어 모든 모듈이 같은
# /home/gradle/.gradle 를 sharing=shared 로 동시에 쓴다. Gradle user home 은 동시 접근에
# 안전하지 않아 `docker compose build`(= 전 모듈 동시 빌드) 가 journal-1.lock 타임아웃으로
# 무더기 실패한다. CI 는 모듈을 따로 빌드해서 이 결함이 드러나지 않았다 — 실측으로 잡았다.
# 아래 bootJar 스텝도 같은 이유로 동일하게 갈라야 한다(둘 중 하나만 고치면 여전히 깨진다).
RUN --mount=type=cache,id=gradle-${MODULE},target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:dependencies || true

# 전체 소스 복사
COPY shared-common ./shared-common
COPY order-service ./order-service
COPY operation-service ./operation-service
COPY gateway-service ./gateway-service
COPY marketing-service ./marketing-service

RUN --mount=type=cache,id=gradle-${MODULE},target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:bootJar -x test

# bootJar 결과를 고정 경로로 복사 (Spring Boot 가 만드는 *-plain.jar 는 제외)
RUN find /workspace/${MODULE}/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

############################
# Stage 2: Runtime
############################
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl tini ghostscript
RUN addgroup -S spring && adduser -S spring -G spring

# 쓰기 가능한 데이터 경로를 **이미지 안에 미리 만들고 소유권을 넘긴다**.
#
# 이유: 컨테이너는 비루트(spring)로 돌지만, named volume 의 마운트 지점이 이미지에 없으면
# Docker 가 그 디렉터리를 root:root 로 만들어 붙인다 → 첨부 업로드가 Permission denied 로 죽는다.
# 이미지에 있으면 Docker 가 그 소유권을 볼륨에 그대로 복사하므로 spring 이 쓸 수 있다.
# (로컬 bootRun 에서는 자기 계정으로 쓰기 때문에 절대 드러나지 않는 종류의 사고다 — 실측으로 잡았다.)
RUN mkdir -p /var/lib/lemuel/board-attachments \
    && chown -R spring:spring /var/lib/lemuel

USER spring:spring

WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
