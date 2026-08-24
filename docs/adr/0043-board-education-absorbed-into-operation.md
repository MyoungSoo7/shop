# ADR 0043 — board·education 을 operation-service 의 슬라이스로 흡수 (operation 의 성격 재정의)

- 상태: Accepted (실행 완료)
- 일자: 2026-08-25
- 관련: ADR 0037(분해 기준 6축) ·
  [ADR 0041](0041-notification-absorbed-into-operation.md)(notification 흡수) ·
  [ADR 0042](0042-organization-absorbed-into-order.md)(organization 은 order 로 — operation 행 기각) ·
  [ADR 0035](0035-kafka-topic-catalog.md)(토픽 카탈로그)

## 컨텍스트

ADR 0037 은 board·education 을 B그룹(④⑤ — 콘텐츠성 도메인)으로 분류하며 이미 이렇게 적어 뒀다:
**"팀 경계 근거는 약함(합쳐도 큰 무리는 없음)"**. 0038~0042 통합 사이클에서 같은 잣대로 재채점하면:

| 축 | board (main 91파일) | education (main 34파일) |
|---|---|---|
| ① 정합성 경계 | 없음 — 자기 스키마 안에서 닫힘, 분산 트랜잭션 0 | 상동 |
| ② 규제/라이선스 | 없음 | 없음 |
| ③ 장애 격리 | 없음 — 게시판이 죽어도 거래·정산은 무관 | 상동 |
| ④ 변경/배포 주기 | 약함 — "배포 주기가 다르다"는 주장이었으나 실측 커밋 이력에서 코어와 구분되는 주기가 없다 | 상동 |
| ⑤ 팀 인지 부하 | 없음 — 한 팀의 일부에도 못 미치는 크기 | 상동 |
| ⑥ 데이터 오너십 | 있음 — 그러나 별도 **프로세스**가 아니라 별도 **스키마**로 충분하다(아래 결정 §스키마) | 상동 |

**"최소 2축 이상 강하게 걸려야 별도 서비스"** 기준 미달이다. 이벤트 간선도 board 는 발행 0·소비 0,
education 은 발행 1(`lemuel.education.course_published`, 소비처 미배선)뿐이라 결합도 쪽에도 경계를
유지할 근거가 없다.

## 결정

`board-service`(8114)와 `education-service`(8116)를 **operation-service 의 `board`·`education`
슬라이스로 흡수**한다. REST 경로(`/api/boards/**`·`/admin/boards/**`·`/admin/education/**`)와
이벤트 계약(`course_published` 페이로드·토픽명)은 외부 계약이라 불변이다.

### ★ 이 결정은 operation-service 의 성격을 바꾼다 — 정면으로 적는다

ADR 0037 이 정의한 operation 은 **"운영 관제"**(인시던트·신호·이상탐지, 축③ — 관측이 비즈니스
경로와 절대 얽히면 안 됨)였다. 0041 의 notification 까지는 그 성격 안이었다 — 알림은 관제와 같은
"관측/통지" 성질이다. **board·education 은 아니다.** 게시판과 교육 콘텐츠는 관제가 아니라
사용자향 기능이다.

그래서 이 흡수 이후 operation 의 정확한 이름은 "운영 관제 서비스"가 아니라
**"플랫폼 운영 서비스"** 다: **사업 도메인(거래 order · 정산 settlement · 금융 finance)에도,
외부 데이터 위성(external-data · company)에도 속하지 않는 지원 기능들의 거처.** 관제는 이제
그 안의 한 슬라이스(incident·signal·anomaly)다. 모듈명·포트·메트릭 접두는 바꾸지 않는다 —
이름 변경은 compose·게이트웨이·CI·컨슈머 그룹·대시보드 전반의 배선 비용만 낳고 얻는 것이 없다.
바뀐 것은 이름이 아니라 **이름의 정의**이고, 그 정의는 이 ADR 이 정본이다.

### 흡수 기준은 "관리 화면"이 아니다 — ADR 0042 와의 정합

같은 날 ADR 0042 는 *"관리자 화면에서 관리되니 관리 기능으로 묶는다"* 는 병합 근거를 기각했다
(그 규칙엔 멈추는 지점이 없다). board·education 이 operation 으로 오는 근거는 그것이 아니다:

1. **6축 미달** — 별도 프로세스일 근거가 없다(위 표. 0037 스스로 예고했다).
2. **소거법으로 남는 자리가 operation 뿐이다** — order 는 이미 인지 부하 한계 초과(0041 §기각 근거,
   0042 로 organization 까지 합류), settlement 는 자금 코어라 무관 도메인의 blast radius 를 실을 수
   없고(0040 의 pgvector 대가가 이미 한계선), finance 는 금융 규제 경계(②)가 성질이 다르며,
   external-data·company 는 쓰기 오너십이 외부에 있는 read-only 위성이다.
3. **organization 과의 차이** — organization 은 ⑥(여러 소비자가 참조하는 조직 마스터)이 강하게 걸리고
   order 의 user 마스터와 정체성 축이 같아 **갈 곳이 있었다**. board·education 은 강한 축도, 정체성이
   같은 코어도 없다. 갈 곳 없는 지원 도메인을 위해 새 서비스를 만드는 것(backoffice-service 신설)은
   서비스를 줄이는 사이클에서 프로세스를 늘리는 모순이라 기각했다.

### 스키마 분리는 유지한다 (⑥의 착지)

`lemuel_board`·`lemuel_education` DB 는 사라지지만, 테이블은 `lemuel_operation` 안의 **`board`·
`education` 스키마**로 간다(엔티티가 `@Table(schema = …)` 로 명시 매핑, deposit·ai 흡수와 같은 방식).
축⑥이 요구하는 것은 프로세스가 아니라 데이터 경계이고, 그 경계는 스키마 + 슬라이스 ArchUnit
(`BoardArchitectureTest`·`EducationArchitectureTest` — 슬라이스 간 import 금지)로 남는다.
데이터 이관은 구 DB 의 각 스키마를 새 DB 로 1:1 복사(append/copy, 원본 불변)로 성립한다.

### Outbox 는 operation 의 한 벌로 합친다

education 이 자기 스키마에 들고 있던 `education.outbox_events` 는 **이관하지 않는다** —
operation 은 `opslab.outbox_events` 단일 Outbox 를 이미 소유하고, shared-common 폴러의 네이티브
쿼리가 그 스키마를 하드코딩한다. `CoursePublished` 발행은 같은 테이블로 들어가고, 토픽 카탈로그의
`lemuel.education.course_published` owner 는 `education-service` → `operation-service` 로 갱신했다
(operation yml 에 `app.kafka.topic.owner` 신설 — operation 이 처음으로 토픽 발행 모듈이 됐다).

## 정직한 대가

- **축③ 전제가 프로세스 수준에서 약해진다.** "관제는 비즈니스 경로와 얽히면 안 된다"던 서비스가
  이제 첨부 업로드(최대 20MB)·교육 콘텐츠 API 와 리소스를 공유한다. 관제 수집 경로의 규율
  (webhook 항상 200 · opssignal 절대 throw 금지 · fire-and-forget)은 코드 수준에서 그대로지만,
  게시판 트래픽 폭주가 관제 프로세스의 CPU·커넥션 풀을 잠식할 수 있다는 사실은 남는다.
  관제의 성역이 필요해지는 규모가 오면 incident·signal 슬라이스를 다시 꺼내는 것이 되돌리는 길이다
  (슬라이스 경계가 살아 있어 가역적).
- **DB blast radius** — `lemuel_operation` 이 죽으면 관제·알림·게시판·교육이 동반 정지한다.
  단, 관제가 죽는 순간 게시판이 사는 것의 가치는 크지 않다(넷 다 매출 경로가 아니다).
- **테스트 배선 함정 3건을 실측으로 밟았다**(재발 방지 기록): ① 슬라이스의 좁은
  `@EntityScan`/`@EnableJpaRepositories` 가 루트 자동 스캔을 꺼서 기존 도메인 리포지토리가 통째로
  미등록된다 — 흡수 시 좁은 JPA 설정은 삭제가 정답. ② `@ConfigurationPropertiesScan` 은
  `@WebMvcTest` 슬라이스에 적용되지 않는다 — 웹 슬라이스가 물어 오는 `@Component` Filter 의존은
  테스트에서 목으로 채운다. ③ 공용 ObjectMapper 는 primitive null 을 거부해(`FAIL_ON_NULL_FOR_PRIMITIVES`)
  독립 시절 400 이던 본문 오류가 500 이 된다 — `HttpMessageNotReadableException` → 400 매핑을 명시했다.

## 결과

| 항목 | 이전 | 이후 |
|---|---|---|
| 모듈 | `board-service`(8114/8115) · `education-service`(8116/8117) | operation-service 의 `board`·`education` 슬라이스 (8092) |
| DB | `lemuel_board` · `lemuel_education` | `lemuel_operation` 의 `board`·`education` 스키마 |
| 게이트웨이 | 전용 라우트 2건 → 8114/8116 | operation 라우트에 경로 합류 (`/api/boards/**`·`/admin/boards/**`·`/admin/education/**`) |
| 토픽 owner | `education-service` | `operation-service` (카탈로그 1건 갱신) |
| compose | 앱 2 + DB 2 컨테이너 | 전부 제거, 첨부 볼륨(`board-attachment-data`)은 operation 이 승계 |
| 외부 계약 | — | **불변** — 경로·페이로드·토픽명·`APP_BOARD_ATTACHMENT_BASE_DIR` 설정명 모두 그대로 |

0042(organization → order)와 이 ADR 로 **서비스 인벤토리는 11 → 8** 이 됐다
(자바 6: order·settlement·finance·external-data·company·operation + gateway + 폴리글랏 부속 1묶음).

## 대안 (기각)

- **현상 유지** — 0037 기준 미달을 알고도 두는 것은 0041 이 기각한 것과 같은 이유로 기각.
- **order/settlement/finance 흡수** — 위 §소거법. 코어의 인지 부하·blast radius·규제 경계를 해친다.
- **backoffice-service 신설** — 줄이는 사이클에서 프로세스를 늘리는 모순. operation 이 이미 그 자리다.
- **완전 삭제** — 게시판·교육은 프론트 라우트·메뉴가 실제로 쓰는 기능이다. 삭제할 이유가 없다.
