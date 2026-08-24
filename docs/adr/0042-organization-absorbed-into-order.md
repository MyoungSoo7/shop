# ADR 0042 — organization-service 를 order-service 의 슬라이스로 흡수

- 상태: Accepted (실행 완료)
- 일자: 2026-08-25
- 관련: ADR 0037(분해 기준 6축) ·
  ADR 0039(금융 그룹 통합) ·
  [ADR 0041](0041-notification-absorbed-into-operation.md)(notification 흡수) ·
  [ADR 0035](0035-kafka-topic-catalog.md)(토픽 카탈로그) · ADR 0020(DB 분리)

## 컨텍스트

같은 날 진행된 통합 사이클(0038~0041)에서 board·education·notification 이 operation-service 로 모였다.
이어서 **organization-service 도 operation 으로 보내자**는 제안이 나왔고, 근거는 *"게시판·조직·교육 모두
관리자 페이지에서 관리되니 관리 기능으로 묶는다"* 였다. 실제로 한 차례 operation 슬라이스로 이동까지 됐다.

이 근거를 검토한 결과 **기각**한다. 이유는 두 층이다.

### ① 판정 기준이 틀렸다 — 관리 화면은 UI 표면이지 컨텍스트 경계가 아니다

"관리자 화면에서 관리된다"를 병합 기준으로 삼으면 멈추는 지점이 없다. 같은 규칙으로 order 의
`menu`·`rbac`·`sellertier`·`auditconsole`, settlement 의 `/admin/settlements`·`/admin/payouts`,
finance 의 `/admin/deposits` 가 전부 "관리 기능"이 되어 operation 으로 가야 한다.

ADR 0037 은 반대 방향으로 이미 같은 말을 해뒀다 — *"데이터 원천이 다르다는 이유 하나만으로는 분리
사유로 삼지 않는다."* 대칭으로, **관리 화면이 같다는 이유 하나만으로는 병합 사유가 되지 않는다.**
경계 판정은 6축(정합성·규제·장애격리·배포주기·인지부하·데이터 오너십)으로 한다.

### ② organization 은 6축에서 board·education 과 등급이 다르다

ADR 0037 의 등급표가 이미 갈라 놓았다.

| 서비스 | 0037 등급 | 원문 |
|---|---|---|
| board / education | ④⑤ | "콘텐츠성 도메인 … 팀 경계 근거는 약함(**합쳐도 큰 무리는 없음**)" |
| organization | ⑤⑥ | "조직/멤버십 마스터, card 등 여러 금융 서비스가 참조하는 **진짜 오너**" |

board·education 의 operation 합류는 0037 이 명시적으로 허용한 방향이다. organization 만 다른 등급을
받았고, 그 근거가 여전히 유효하다:

- **⑥ 데이터 오너십** — `lemuel.organization.{created,member_joined,member_removed,member_role_changed}`
  4토픽을 발행하고 finance 의 card 슬라이스가 조직 프로젝션으로 소비한다. 흡수 후에는 **법인카드의
  권한 판정이 "운영관제"가 발행한 이벤트에 의존**하게 된다 — 토픽 이름은 `lemuel.organization.*` 인데
  소유 모듈은 operation 이 되는 의미 역전이다.
- **⑤ 인지 부하 / 보안 포스처** — operation 콘솔은 `/api/ops/**` 에 `anyRequest().hasRole("ADMIN")` 인
  ADMIN 전용 서비스다. organization 은 **셀러 본인이 쓰는 사용자 표면**이고 IDOR 소유권 대조가 핵심
  규칙이다. 한 서비스 안에 두 포스처가 섞이면 다음 매처 수정 때 새는 자리가 된다.
- **③ 장애 격리 반전** — operation 의 존재 이유는 *"운영신호가 비즈니스 경로와 절대 얽히면 안 됨 —
  fire-and-forget, 절대 throw 금지"* 다. organization 은 정반대로 불변식(활성 OWNER ≥ 1)을 **예외로
  지켜야 하는 쓰기 경로**다. 같은 프로세스에 둘 수는 있어도, operation 의 정체성 한 문장이 사라진다.

## 결정

`organization-service` 를 **order-service 의 `organization` 슬라이스로 흡수**한다(operation 이 아니라).

패키지는 원래대로 `github.lms.lemuel.organization..` 이다 — order 의 도메인 슬라이스 규약과 같고,
독립 서비스 시절 패키지와도 동일해 이력이 끊기지 않는다.

### 왜 order-service 인가

**정체성 축이 같다.** order 는 사람(`user`) 마스터를 소유한다. organization 은 조직(셀러/기업) 마스터다.
"누가 누구인가"를 다루는 두 마스터가 한 스키마(`opslab`)에 있는 것이 자연스럽다 — 실제로 멤버십의
`user_id` 는 order 의 user 를 가리키는 비즈니스 키였고, 서비스가 갈라져 있어 **검증할 수 없는 참조**로
남아 있었다(원본 마이그레이션 주석: *"user 존재 검증은 범위 밖"*). 합치면 그 검증이 가능해진다(후속 과제).

부수적으로 얻는 것:

- **Outbox 인프라 1벌 공유** — order 는 이미 프로듀서다. organization 의 발행은 aggregateType
  `Organization` 으로 order 의 기존 폴러에 그대로 얹힌다(토픽은 aggregateType+eventType 에서
  알고리즘으로 파생되므로 라우팅 설정 추가가 필요 없다).
- **감사·파티션 인프라 1벌 공유** — organization 이 따로 들고 있던 `audit_logs` 파티셔닝과
  `PartitionMaintenanceRunner` 는 order 의 `PartitionMaintenanceScheduler` 와 중복이라 **삭제**했다.
  (같은 DB 의 같은 함수를 두 빈이 호출하게 된다.)
- **DB 하나 감소** — `lemuel_organization` PG 컨테이너 제거.

### 경계는 코드로 유지한다

한 프로세스에 들어와도 슬라이스는 서로 import 하지 않는다. `OrganizationArchitectureTest` 가
`github.lms.lemuel.organization..` → `order..`/`settlement..`/`loan..`/`investment..`/`account..` 의존을
금지하고, order 의 `HexagonalArchitectureTest.슬라이스_사이에_순환_의존이_없다()` 가 모듈 전체 순환을
막는다(그래서 organization 쪽 중복 순환 규칙은 제거했다 — 같은 매처로 같은 모듈을 두 번 읽을 뿐이다).

## 결과

| 항목 | 이전 | 이후 |
|---|---|---|
| 모듈 | `organization-service` (8104) | order-service 의 `organization` 슬라이스 |
| DB | `lemuel_organization` | `opslab` 스키마 (`opslab.organizations`, `opslab.memberships`) |
| 게이트웨이 | 전용 라우트 `/api/organizations/**` → 8104 | order 라우트에 `/api/organizations/**` 합류 |
| 토픽 owner | `organization-service` | `order-service` (카탈로그 4건 갱신) |
| compose | `organization-service` + `organization-postgres` 컨테이너 | 둘 다 제거 |
| 외부 계약 | — | **불변** — 경로·페이로드·토픽명 모두 그대로 |

`external_ref`(sellerId/stockCode)와 `user_id` 는 여전히 **비검증 비즈니스 키**다. 같은 스키마에 왔다고
FK 를 거는 것은 이 ADR 의 범위가 아니다 — 참조 무결성을 켜는 것은 기존 데이터의 정합을 먼저 증명해야
하는 별도 작업이다.

## 대안 검토

- **operation-service 로 흡수** — 위 §컨텍스트 ②에서 기각. 실제로 한 번 옮겼다가 되돌렸다.
- **독립 유지** — 가능한 선택이었다. 6축에서 ⑥이 강하게 걸리므로 "합쳐야만 한다"는 압력은 없었다.
  그럼에도 order 로 보낸 것은 ⑤(인지 부하: main 39파일)가 독립 프로세스·독립 DB·독립 배포 파이프라인의
  운영 비용을 정당화하지 못하고, 정체성 축이 order 와 같기 때문이다.
- **finance 로 흡수** — card 슬라이스가 유일한 소비자라 후보였다. 기각 이유는 방향이 거꾸로이기
  때문이다. organization 은 finance 만의 것이 아니라 **여러 소비자를 가질 마스터**이고, 마스터를
  소비자 안에 넣으면 다음 소비자가 생길 때 다시 꺼내야 한다.
