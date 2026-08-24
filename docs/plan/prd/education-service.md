# PRD — 교육 과정 관리 (education-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`board-service.md`](board-service.md)·[`order-organization-slice.md`](order-organization-slice.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                          |
> | --------- | --------------------------------------------------------------------------------------------- |
> | 대상 범위 | `education-service`(8116, mgmt 8117, DB `lemuel_education`, 스키마 `education`) 전체 — 과정·차시 관리 |
> | 역산 기준 | 2026-08-22 `develop` 브랜치 (HEAD `92d25c463`)                                               |
> | 근거      | 도메인 5개 클래스 + 예외 3종, 진입 어댑터 1종(관리 REST 12엔드포인트), Flyway V1 단일, 테스트 12개 클래스 |
> | 범위 밖   | 수강·진도·이수(미구현) · 콘텐츠 저장(참조만 보관) · 결제 연동 · 학습자 경로                  |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) · [`../../../CLAUDE.md`](../../../CLAUDE.md) · `../seeds/operation-service-education-course-authoring.seed.yaml` |

---

## 1. 배경과 문제

플랫폼에 붙은 셀러·FC·직원에게 **교육 콘텐츠를 내려보낼 경로**가 필요하다. 그런데 교육은 "만들자마자
공개"가 아니다 — 초안을 쓰고, 차시를 짜고, 순서를 잡고, 검토가 끝나야 공개한다. 그 사이 단계를
시스템이 갖고 있지 않으면 **작성 중인 과정이 그대로 노출된다.**

| 문제                     | 구체적 손상                                                                    |
| ------------------------ | -------------------------------------------------------------------------------- |
| **초안 노출**            | 상태 구분이 없으면 작성 중인 과정이 공개된 과정과 같은 목록에 뜬다             |
| **차시 순서 붕괴**       | 순서가 자유 필드면 중복·누락이 생기고, 학습자는 3차시 다음에 2차시를 본다      |
| **소속 위조**            | 차시가 독립 ID 를 가지면 `courseId` 를 아무거나 넣어도 남의 차시를 고칠 수 있다 |
| **누가 무엇을 바꿨는지** | 관리자 여럿이 편집하는데 이력이 없으면 되돌릴 근거가 없다                      |

education-service 는 **과정의 생애를 상태로 강제하고, 차시 순서를 제약으로 강제한다.** 핵심 설계
판단은 하나다 — **차시는 독립 식별자를 갖되 과정 애그리거트에 속한다.**

## 2. 목표 / 비목표

### 2.1 목표

| #  | 목표                                     | 성공 기준                                                          |
| -- | ---------------------------------------- | ------------------------------------------------------------------ |
| G1 | 작성 중인 과정이 공개되지 않는다         | `DRAFT` 는 공개 전이를 거쳐야만 `PUBLISHED` 가 된다                |
| G2 | 잘못된 상태 전이가 도메인에서 막힌다     | `Course.publish/hide/close` 가 허용 원천을 검사, 위반 시 400       |
| G3 | 차시 순서에 중복·누락이 없다             | `(course_id, sequence)` UNIQUE + 재정렬 요청 전수 대조             |
| G4 | 경로가 주장한 소속을 서버가 확인한다     | `Lesson.requireBelongsTo` — 불일치 시 404 `LESSON_NOT_IN_COURSE`   |
| G5 | 관리 조작이 이력으로 남는다              | 모든 쓰기 유스케이스가 `education_audit_logs` 기록                 |
| G6 | 오류 응답이 다른 서비스와 같은 모양이다  | 공통 `ErrorResponse`(`errorCode`) — 전용 advice + 공통 핸들러 배선 |

### 2.2 비목표 (의도적으로 하지 않는 것)

| #  | 비목표                | 이유                                                                     |
| -- | --------------------- | ------------------------------------------------------------------------ |
| N1 | 수강·진도·이수 관리   | 현재 범위는 **콘텐츠 관리**뿐이다. 학습자 경로는 미구현(→ G-2)           |
| N2 | 콘텐츠 파일 저장      | `content_ref` 로 참조만 보관한다 — 영상·문서 호스팅은 이 서비스 밖       |
| N3 | 과정 삭제             | 공개된 적 있는 과정은 지우지 않고 `CLOSED` 로 닫는다                     |
| N4 | 이벤트 소비           | 소비 0. 상류 이벤트에 반응할 일이 아직 없다                              |
| N5 | 역할 세분화           | `ADMIN` 단일 — 강사·검수자 역할은 없다                                   |

## 3. 사용자

| 사용자                | 무엇을 위해 쓰는가                                             |
| --------------------- | -------------------------------------------------------------- |
| **교육 운영자(ADMIN)** | 과정을 만들고 차시를 붙이고 순서를 잡아 공개·숨김·종료한다     |
| **관리자 콘솔(프론트)** | `/admin/system/education` 화면에서 위 조작을 수행한다          |
| **하류 서비스**        | `CoursePublished` 를 받아 알림·노출에 쓴다 — **현재 소비자 0**(SPEC §5 발행 전용 — 인정된 상태) |

## 4. 제품 범위 — 기능 맵

| 영역     | 기능                                                                |
| -------- | ------------------------------------------------------------------- |
| 과정     | 생성(DRAFT)·수정·조회·검색(상태/제목)·상태 전이 3종                |
| 차시     | 생성·수정·삭제·목록·재정렬                                          |
| 상태머신 | `DRAFT → PUBLISHED ⇄ HIDDEN → CLOSED`                              |
| 이벤트   | 과정 공개 시 `CoursePublished` Outbox 적재                         |
| 보안     | JWT + `/admin/education/**` `hasRole('ADMIN')`                     |
| 운영     | 관리 조작 감사 로그, 낙관적 락(`version`)                          |

## 5. 핵심 유스케이스

### UC-1. 과정을 초안으로 만들고 검토 후 공개한다

1. 운영자가 `POST /admin/education/courses` 로 과정을 만든다 → **항상 `DRAFT`** 다(`Course.draft`).
2. 차시를 붙이고 순서를 잡는다.
3. `POST /{id}/publish` 로 공개한다 — `Course.publish` 가 원천이 `DRAFT`·`HIDDEN` 인지 검사하고,
   `publishedAt` 을 찍는다.
4. 공개 전이일 때만 `CoursePublished` 가 Outbox 에 적재된다(수정·숨김·종료는 발행하지 않는다).
5. 되돌리려면 `hide` 로 숨긴다. 다시 `publish` 하면 공개 상태로 돌아온다.

### UC-2. 차시 순서를 바꾼다

1. `POST /{courseId}/lessons/reorder` 에 **그 과정의 차시 전부**를 원하는 순서로 보낸다.
2. `Lesson.validateReorder` 가 개수·중복·집합 일치를 전수 대조한다. 하나라도 빠지거나 남으면
   400 `LESSON_ORDER_INVALID` 로 거부한다 — 부분 재정렬은 없다.
3. 저장은 **2단**이다. `(course_id, sequence)` UNIQUE 때문에 두 차시를 맞바꾸면 중간 상태에서 값이
   겹친다. 그래서 먼저 전부 음수 구간(`-1..-n`)으로 밀고, 그다음 목표 순서(`1..n`)를 쓴다.

### UC-3. 경로가 주장한 소속을 서버가 확인한다

1. 차시 수정·삭제 URL 은 `/{courseId}/lessons/{lessonId}` 다.
2. 서비스가 차시를 적재한 뒤 `lesson.requireBelongsTo(courseId)` 로 대조한다.
3. 불일치면 404 `LESSON_NOT_IN_COURSE`. 대조를 **어댑터가 아니라 도메인에** 둔 이유는 진입점이
   늘어도 규칙이 한 곳에 남게 하기 위해서다.
4. 삭제는 예외적으로 **없는 차시면 조용히 통과**한다(멱등). 단 존재하는데 소속이 다르면 거부한다 —
   지우고 나서 "그 과정이 아니었다"를 알면 되돌릴 수 없기 때문이다.

## 6. 기능 요구사항

| FR   | 요구사항                                                     | 강제 지점                                        |
| ---- | ------------------------------------------------------------ | ------------------------------------------------ |
| FR-1 | 과정 제목은 필수다                                           | `Course` 생성자·`update` 검증                    |
| FR-2 | 새 과정은 반드시 `DRAFT` 로 시작한다                         | `Course.draft` 단일 팩토리                       |
| FR-3 | 공개는 `DRAFT`·`HIDDEN` 에서만 가능하다                      | `Course.publish` → `require(...)`                |
| FR-4 | 숨김은 `PUBLISHED` 에서만 가능하다                           | `Course.hide`                                    |
| FR-5 | 종료는 `PUBLISHED`·`HIDDEN` 에서만 가능하다                   | `Course.close`                                   |
| FR-6 | 같은 과정 안에서 차시 순서는 유일하다                        | `education_lessons_sequence_uk`                  |
| FR-7 | 재정렬 요청은 과정의 차시를 정확히 한 번씩 담아야 한다       | `Lesson.validateReorder`                         |
| FR-8 | 차시 수정·삭제는 경로의 과정 소속을 대조한다                 | `Lesson.requireBelongsTo`                        |
| FR-9 | 모든 쓰기 조작은 감사 로그를 남긴다                          | `EducationAuditPort.record`                      |
| FR-10 | 공개 전이에서만 이벤트를 적재한다                           | `CourseAdminService.transition` 의 `if PUBLISHED` |
| FR-11 | `/admin/education/**` 는 ADMIN 만 접근한다                  | `SecurityConfig` `hasRole("ADMIN")`              |
| FR-12 | 오류 응답은 공통 `ErrorResponse` 스키마다                   | `EducationExceptionHandler` + `GlobalExceptionHandler` |

## 7. 도메인 규칙 (BR)

| BR   | 규칙                                                                                             | 근거                                  |
| ---- | ------------------------------------------------------------------------------------------------ | ------------------------------------- |
| BR-1 | **상태머신은 도메인이 강제한다** — 서비스·어댑터는 전이를 판단하지 않고 애그리거트에 위임한다     | `Course.publish/hide/close`           |
| BR-2 | **차시는 독립 ID + 애그리거트 소속** — 재정렬·개별 수정 때문에 ID 를 주되 소속 대조는 필수다      | `Lesson.requireBelongsTo`             |
| BR-3 | **부분 재정렬은 없다** — 전체를 받아 전수 대조해야 중복·누락이 원천 차단된다                     | `Lesson.validateReorder`              |
| BR-4 | **음수 구간 경유** — 유니크 제약을 끄는 대신 충돌 불가 구간을 지나간다                            | `LessonAdminService.reorder`          |
| BR-5 | **삭제는 멱등, 소속 위반은 거부** — 없는 것을 지우는 건 무해하지만 남의 것을 지우는 건 복구 불가  | `LessonAdminService.delete`           |
| BR-6 | **닫힌 과정은 종단** — `CLOSED` 에서 나가는 전이가 없다                                           | `Course.require` 호출 목록에 CLOSED 없음 |
| BR-7 | **도메인은 스프링을 모른다** — 예외→HTTP 번역은 어댑터(advice) 몫이다                             | `EducationArchitectureTest`(ArchUnit) |
| BR-8 | **영속 엔티티는 매핑만** — 전이 규칙은 도메인에만 있다(과거 이중 모델을 정리한 결과)              | `Course`·`Lesson` 클래스 주석         |

## 8. 데이터 모델

| 테이블                    | 역할                    | 특기                                                                       |
| ------------------------- | ----------------------- | -------------------------------------------------------------------------- |
| `education_courses`       | 과정 애그리거트         | `status` CHECK 4값, `published_at`·`closed_at`, `version`(낙관적 락), 인덱스 `(status, updated_at DESC)` |
| `education_lessons`       | 차시                    | `course_id` FK, `(course_id, sequence)` UNIQUE, `content_type`·`content_ref`, `required`, `status` CHECK 2값 |
| `outbox_events`           | 이벤트 적재             | shared-common 공용 스키마, `event_id` UNIQUE                               |
| `education_audit_logs`    | 관리 조작 감사          | `(resource_type, resource_id, created_at DESC)` 인덱스, **파티셔닝 없음**  |

> 스키마는 `education` 이며 Flyway 가 `create-schemas: true` 로 만든다. 마이그레이션은 **V1 단일**이다.

## 9. 인터페이스

### 9.1 관리 REST (`/admin/education/courses`, JWT + ADMIN)

| 메서드 | 경로                                     | 설명                                  | 상태 |
| ------ | ---------------------------------------- | ------------------------------------- | ---- |
| GET    | `/`                                      | 목록(상태 필터·제목 검색·페이지)      | 200  |
| POST   | `/`                                      | 과정 생성 → `DRAFT`                   | 201  |
| GET    | `/{id}`                                  | 단건 조회                             | 200  |
| PUT    | `/{id}`                                  | 제목·설명 수정                        | 200  |
| POST   | `/{id}/publish`                          | 공개                                  | 200  |
| POST   | `/{id}/hide`                             | 숨김                                  | 200  |
| POST   | `/{id}/close`                            | 종료                                  | 200  |
| GET    | `/{courseId}/lessons`                    | 차시 목록(순서 오름차순)              | 200  |
| POST   | `/{courseId}/lessons`                    | 차시 생성                             | 201  |
| PUT    | `/{courseId}/lessons/{lessonId}`         | 차시 수정(소속 대조)                  | 200  |
| DELETE | `/{courseId}/lessons/{lessonId}`         | 차시 삭제(멱등, 소속 대조)            | 204  |
| POST   | `/{courseId}/lessons/reorder`            | 전체 재정렬 후 갱신된 목록 반환       | 200  |

**오류 계약**

| 코드                   | 상태 | 발생                                     |
| ---------------------- | ---- | ---------------------------------------- |
| `COURSE_NOT_FOUND`     | 404  | 없는 과정 조회·수정·전이                 |
| `COURSE_INVALID_STATE` | 400  | 허용되지 않는 상태 전이                  |
| `LESSON_ORDER_INVALID` | 400  | 재정렬 요청이 차시 목록과 불일치         |
| `LESSON_NOT_IN_COURSE` | 404  | 경로의 과정에 속하지 않는 차시           |

### 9.2 이벤트

**발행 1** — `lemuel.education.course_published`(카탈로그 등재, `aggregateType=Education`,
`eventType=CoursePublished`, orderingKey `courseId`).
페이로드: `courseId` · `title` · `publishedAt` · `publishedBy` · `version`.

**소비 0.**

> 2026-08-22 이전에는 이 발행이 **Outbox 테이블까지만** 갔다(폴러 미배선). 지금은 네 축이 모두
> 서 있어 브로커까지 닿는다 — G-1 참조. 소비처가 0 인 것은 SPEC §5 가 인정하는 상태다.

### 9.3 배선

| 지점            | 값                                                                   |
| --------------- | -------------------------------------------------------------------- |
| gateway         | `Path=/admin/education/**` → `EDUCATION_SERVICE_URI`(기본 8116)      |
| 프론트 라우트   | `/admin/system/education` (`AdminOnlyRoute`)                         |
| 메뉴            | `menuFallback.ts` id `-90` '교육 관리', area `SYSTEM`, roles `ADMIN` |
| compose         | `education-postgres` + `education-service`(호스트 127.0.0.1:8116)     |
| Dockerfile      | `MODULE=education-service`                                           |

> 화면 URL 이 `/admin/education/courses` 가 **아닌** 이유: 그 URL 은 이 서비스의 API 다. nginx SPA
> 폴백 접두사(`/admin/system/**`) 아래 두지 않으면 새로고침에서 API JSON 이 그대로 렌더된다.

## 10. 비기능 요구

| NFR   | 요구                        | 현재 상태                                                          |
| ----- | --------------------------- | ------------------------------------------------------------------ |
| NFR-1 | shared-common **제한 스캔** | `scanBasePackages="...education"` + 필요한 빈만 `@Import`(발행 측만 — 소비 측 배선은 의도적으로 제외) |
| NFR-2 | 커버리지 LINE ≥ 90%         | JaCoCo 게이트                                                      |
| NFR-3 | 헥사고날 의존 방향          | `EducationArchitectureTest`(ArchUnit)                              |
| NFR-4 | 오류 스키마 전 서비스 동형  | `EducationErrorContractTest` — 2026-08-20 18개 중 유일한 이탈을 해소 |
| NFR-5 | 동시 편집 충돌 감지         | `version` 낙관적 락(과정·차시 모두)                                |
| NFR-6 | 시각은 `TIMESTAMPTZ`        | 전 컬럼 timestamptz (outbox 공용 컬럼 제외)                        |

## 11. 배치

도메인 배치는 **없다**(스케줄러·배치 잡 0, 감사 로그 파티션 러너도 없다 → G-6).

인프라 주기 작업은 하나 있다 — **Outbox 폴러**(`app.outbox.polling-delay-ms`, 기본 2s)가
PENDING 행을 집어 발행한다. 테스트에서는 부모 `build.gradle.kts` 가 `app.outbox.polling.enabled=false`
로 끈다(컨텍스트 종료 시 Hikari 셧다운과 경합해 WARN 을 쏟기 때문).

## 12. 역산에서 드러난 격차

### G-1. ~~공개 이벤트가 Kafka 로 나가지 않는다~~ → ✅ 2026-08-22 해소

`OutboxBackedEducationEventPublisher` 는 `education.outbox_events` 에 `PENDING` 행을 넣었지만,
**그 행을 집어 Kafka 로 보낼 주체가 없었다** — `spring-kafka` 의존 없음 · `bootstrap-servers` 설정
없음 · 폴러 빈 없음 · compose 에 Kafka 환경변수 없음. 스캔이 `github.lms.lemuel.education` 로
한정돼 shared-common 의 `OutboxPublisherScheduler`(@Component)가 붙지 않았고 `PersistenceConfig` 도
이를 들이지 않았다. 결과적으로 `lemuel.education.course_published` 는 카탈로그에 소유 토픽으로
등재돼 있으면서 **한 번도 생산된 적이 없었다.**

**조치** — 네 축을 모두 세웠다.

| 축 | 조치 |
|---|---|
| 의존 | `build.gradle.kts` 에 `spring-boot-starter-kafka` · `spring-kafka` |
| 설정 | `spring.kafka.bootstrap-servers` + producer(멱등·acks all) · `app.kafka.enabled`(기본 false) · `app.outbox.polling-delay-ms` |
| 빈 | `config.OutboxPublishingConfig` — 발행 측만 명시 `@Import`(소비 측은 들이지 않는다. education 스키마에 `processed_events` 가 없다) |
| 주기 실행 | `EducationServiceApplication` 에 `@EnableScheduling` |

**네 번째 축이 함정이었다.** `@Scheduled` 는 `OutboxPollingTrigger` 에 붙어 있고, `@EnableScheduling`
이 없으면 그 빈은 **등록만 된 채 영영 돌지 않는다** — 기동 로그에도 API 응답에도 증상이 없다.
빈 존재만 검사하는 게이트는 이 상태를 GREEN 으로 읽는다. `outbox-poller-gate` 에 이 축을 추가했고,
`OutboxPublishingWiringTest` 가 스케줄 태스크 등록까지 단언한다(`@EnableScheduling` 을 떼면 그
테스트 하나만 RED 가 되는 것을 확인했다).

> 소비처는 여전히 0 이다 — 이는 SPEC §5 "발행 전용" 정책이 **인정하는 상태**이며(insurance 9·
> deposit 5·card 7종이 같은 처지), 계약 스키마 편입 트리거는 소비처 등장 시점이다.

### G-2. 학습자 경로가 없다

공개 API 가 0 이다. 과정을 "공개"해도 **공개된 것을 볼 수 있는 사람이 없다** — 조회 경로가 전부
`hasRole('ADMIN')` 이다. 수강 신청·진도·이수 판정도 없다. 즉 현재 `PUBLISHED` 는 관리 목록의 라벨일
뿐이고, 상태머신이 지키는 "초안 노출 금지"(G1)의 반대편 가치가 아직 실현되지 않았다.

### G-3. ~~로컬 실행 시 포트가 board-service 관리 포트와 겹친다~~ → ✅ 2026-08-23 해소

`education/application.yml` 의 `server.port` 기본값이 **8115** 였는데, `board-service` 의
`management.server.port` 도 **8115** 였다. compose 에서는 둘 다 컨테이너 내부 8080 을 쓰고 호스트만
board 8114 / education 8115 로 갈라 드러나지 않았지만, **로컬에서 두 서비스를 같이 띄우면 나중에 뜬 쪽이
기동 실패했다.** CLAUDE.md 모듈 트리에도 두 값이 그대로 적혀 있어 문서만 봐서는 충돌이 보이지 않았다.

**조치** — education 을 board 다음 짝(**8116/8117**)으로 옮기고, 포트가 적혀 있던 곳을 함께 고쳤다.

| 축 | 조치 |
|---|---|
| 앱 | `education/application.yml` — `server.port` 기본값 `8116`, `management.server.port` 기본값 `8117` 신설(board 와 같은 분리형). 선택 근거 주석도 함께 남겼다 |
| 배선 | gateway `EDUCATION_SERVICE_URI` 기본값 `localhost:8116` · compose 호스트 매핑 `127.0.0.1:8116:8080`(컨테이너 내부는 여전히 8080 이라 헬스체크 불변) |
| 문서 | CLAUDE·STRUCTURE·ARCHITECTURE·README·SPEC·이 PRD·gateway PRD·Seed 3종 |

8006~8105 는 Windows(Hyper-V) 예약 구간이라 되돌아갈 수 없고, 8110~8123 은 폴리글랏이 쓴다 —
board(8114/8115) 바로 다음인 8116/8117 이 규약상 유일하게 자연스러운 자리다.

### G-4. `LessonStatus.HIDDEN` 에 도달할 방법이 없다

`LessonStatus` 는 `ACTIVE`·`HIDDEN` 두 값이고 DB CHECK 도 두 값을 허용한다. 그런데 `Lesson.status` 는
`final` 이고 `create()` 는 항상 `ACTIVE` 를 넣으며, 상태를 바꾸는 메서드가 없다. `rehydrate` 로만
`HIDDEN` 이 들어올 수 있으니 **애플리케이션 경로로는 영원히 `ACTIVE`** 다. 차시를 개별로 감추는 요구가
있었는지, 아니면 과정 단위 `hide` 로 충분하다고 판단해 남은 잔재인지가 코드에 남아 있지 않다.

### G-5. 재정렬이 항상 2n 번의 UPDATE 다

차시 2개의 순서만 바꿔도 전체를 음수로 밀었다가 되쓴다. 차시가 수십 개인 과정에서는 한 번의 드래그가
수십 번의 UPDATE + 낙관적 락 버전 증가를 유발한다. 현재 규모에서는 문제가 아니지만, 비용이 차시 수에
비례한다는 사실이 인터페이스에는 드러나지 않는다(`reorder` 는 전체를 받으므로 호출측이 부분 변경임을
알릴 방법도 없다).

### G-6. 감사 로그가 단일 테이블이고 삭제 실패도 성공으로 기록된다

- `education_audit_logs` 는 파티셔닝이 없다. common-data·financial 등은 파티션 + 런웨이 러너를 갖는데
  여기만 없어 증가 대비가 되어 있지 않다.
- `delete()` 는 없는 차시여도 `LESSON_DELETED` 를 남긴다. 삭제 멱등(BR-5)의 대가로 **감사 로그가
  실제로 지워진 것과 지울 게 없던 것을 구분하지 못한다.**

### G-7. 공개 시각을 도메인이 직접 읽는다

`Course.publish` 가 `Instant.now()` 를 호출한다. 시계를 주입하지 않으므로 테스트에서 `publishedAt` 을
고정할 수 없고, 이벤트 페이로드의 `publishedAt` 도 저장 시각과 미세하게 어긋날 수 있다.

## 13. 추적 항목

| #   | 항목                                                     | 상태                          |
| --- | -------------------------------------------------------- | ----------------------------- |
| T-1 | Outbox 폴러·Kafka 배선                                   | ✅ 해소 2026-08-22 (G-1)      |
| T-2 | 학습자 공개 조회·수강 경로                               | 없음 (G-2)                    |
| T-3 | education/board 포트 충돌 정리                           | ✅ 해소 2026-08-23 (G-3)      |
| T-4 | `LessonStatus.HIDDEN` 도달 경로 또는 값 제거             | 미결정 (G-4)                  |
| T-5 | education Seed 결정화(`docs/plan/seeds/`)                | ✅ 완료 2026-08-22            |
| T-6 | 감사 로그 파티셔닝                                       | 없음 (G-6)                    |
