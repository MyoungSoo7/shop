# board-service 설계 — 메타 주도 게시판 플랫폼

> 대상: 신규 마이크로서비스 `board-service` (8114/mgmt 8115, `lemuel_board`).
> 상태: **Phase 4 구현 완료**(정의 CRUD + 글·댓글 + 스킨 4종 + sanitize + 첨부/썸네일 + 고아 청소).
> 정본: 이 문서 = 설계 근거, 강제 규칙 = `board-domain-rules` 스킬, 기능 명세 = `SPEC.md` §3.17.

---

## 1. 문제 정의

"시스템이 있으면 보통 메뉴·코드·권한·카테고리를 만들 수 있고, CRUD 게시판·이미지 게시판도 만들 수 있다."

이 저장소의 실측 현황은 다음과 같다.

| 기능 | 상태 | 위치 |
| --- | --- | --- |
| 메뉴 | ✅ | `order-service` `menu/`, `menus` 테이블, `/admin/system/menus` |
| 공통코드 | ✅ | `order-service` `commoncode/`, `/admin/system/codes` |
| 권한(RBAC) | ✅ | `order-service` `rbac/`, `/admin/system/rbac` |
| 카테고리 | △ | `order-service` `category/` — 커머스 상품 분류 전용, 범용 아님 |
| 게시판 / 이미지게시판 | ❌ | 저장소 전체 0건 |
| 범용 첨부파일 | ❌ | `LocalFileSystemImageStorageAdapter` 는 product 전용 |

즉 만들 것은 **게시판 하나**다. 그리고 "관리자 페이지에서 게시판을 만들면 화면이 생긴다"가 요구의 핵심이다.

---

## 2. 근본 설계 결정 — 코드 생성이 아니라 메타 주도

### ❌ 스캐폴딩형

게시판마다 테이블·컨트롤러·페이지를 생성한다. 게시판 하나 추가에 마이그레이션 + 배포가 필요하고,
`menu-route-gate.test.mjs` 는 라우트가 소스에 존재해야 통과하므로 런타임 생성과 원천적으로 충돌한다.

### ✅ 메타 주도 런타임

`board_definitions` 1행 = 게시판 1개. 게시글은 공용 테이블에 `board_id` 로 분리하고, 프론트는
`/boards/:boardKey` **단일 라우트**가 정의를 읽어 스킨을 바꿔 그린다.

- 배포 없이 게시판을 무한히 만들 수 있다.
- 라우트가 1개로 고정이라 메뉴↔라우트 정합 게이트가 깨지지 않는다.
- **"CRUD 게시판"과 "이미지 게시판"은 별개 도메인이 아니라 같은 도메인의 두 스킨**이다.
  이미지 게시판 = `skin=GALLERY` + `attachmentsEnabled` + `대표 이미지 1장 필수` 라는 정책 조합일 뿐이다.

스킨은 enum 으로 봉인한다(`MenuArea` 와 같은 이유 — 스킨을 늘리는 일은 데이터 입력이 아니라
프론트 컴포넌트를 새로 만드는 일이다).

| 스킨 | 렌더 | 대표 용도 |
| --- | --- | --- |
| `LIST` | 제목 목록 + 페이징 | 공지사항, 자료실 |
| `GALLERY` | 썸네일 그리드 | 이미지 게시판, 포토 갤러리 |
| `FAQ` | 아코디언 | FAQ |
| `QNA` | 질문 + 답변 1:N, 비밀글 | 문의 |

---

## 3. 경계 결정 — 왜 별도 서비스인가

첫 초안은 `order-service` 안의 `board/` 패키지였다. 근거는 "게시판별 권한을 `permissions` 코드로
관리하면 `role_permissions` 조인이 필요하고, 그 테이블은 opslab 에 있다"였다. **이 근거는
권한 모델을 바꾸면 사라진다.** 실측:

- JWT 클레임은 `role`(단일 문자열) + `uid` 뿐이다 (`JwtUtil.generateToken`).
- 메뉴 필터링만 order DB 의 rbac 테이블을 읽는다 (`RolePermissionCodesAdapter`).

그래서 아래 4개 결정으로 **order 의존을 0 으로 만들고** 별도 서비스로 분리한다.

| # | 결정 | 없애는 의존 |
| --- | --- | --- |
| 1 | 정의·글·댓글·첨부를 **모두** board-service 에 둔다 | 도메인 불변식(정의가 글의 규칙을 강제)이 경계를 넘지 않는다. 쪼개면 분산 불변식이 되어 최악 |
| 2 | 게시판별 접근 제어 = **역할 allowlist**(`read_roles='ADMIN,MANAGER,USER'`) | RBAC 프로젝션 불필요 — JWT `role` 만으로 판정 |
| 3 | 작성자 표시명을 **작성 시점 스냅샷**(`author_id` + `author_name`) | user 프로젝션 불필요. 게시판은 "그때 그 이름"이 오히려 올바른 의미론 |
| 4 | 메뉴 행은 **관리자가 기존 `POST /admin/menus` 로 연결**(§6) | cross-DB write 0, Kafka 토픽 0 |

결과: **발행 0 · 소비 0 · 타 서비스 의존 0** 의 완전 독립 서비스. `financial`/`economics`/`market`
같은 공개 위성과 달리 쓰기가 있으므로 shared-common JWT 는 쓰되, Outbox 는 쓰지 않는다.

### 분리를 정당화하는 근거

1. **게시판은 커머스가 아니다.** order-service 는 이미 15개 도메인을 안고 있고, board 는 order 의
   어떤 애그리거트도 참조하지 않는다.
2. **부하 특성이 다르다.** 첨부 바이너리 I/O + 공지 브로드캐스트성 읽기 폭주는 주문·결제와 다른 축이다.
3. 이 저장소의 기준(DB-per-service, 코드·DB 직접 의존 0)을 그대로 충족한다.

### 분리의 실비용 (정직하게)

- 게시판 비활성화 ↔ 메뉴 행 정합을 **FK 하나로 못 막는다.** 단일 서비스였다면
  `ON DELETE CASCADE` 로 끝날 일이 cross-DB 라 런타임 대조(§6)로 내려간다.
  → **수용하기로 한 비용이다**(PRD G-5). 빌드 시점 게이트는 애초에 불가능하고 — 게시판도 메뉴도
  런타임 데이터라 CI 가 대조할 대상이 소스에 없다 — 자동 검증을 하려면 order→board HTTP 결합을
  새로 만들어야 하는데, 그건 이 서비스의 가장 큰 자산(의존 0)을 파는 거래다.
  대신 관리 화면이 양쪽을 대조해 **행 배지 + 상단 요약 배너**로 알린다.
- 신규 서비스 배선 1식(DB·Flyway·gateway·nginx·Dockerfile·compose·CI·JaCoCo 90%·문서 로스터).

---

## 4. 도메인 모델

```
board/domain/
├── BoardDefinition          # 게시판 그 자체 (Phase 1)
├── BoardSkin                # LIST | GALLERY | FAQ | QNA
├── BoardContentFormat       # TEXT | MARKDOWN | HTML
├── BoardAccessPolicy        # 역할 allowlist 4종 (read/write/comment/manage) — VO
├── BoardAttachmentPolicy    # 허용 여부·최대 개수·최대 크기·확장자 — VO
├── BoardPost                # Phase 2
├── BoardComment             # Phase 2
├── BoardAttachment          # Phase 3
└── exception/
    ├── BoardInvariantViolationException
    ├── BoardNotFoundException
    └── DuplicateBoardKeyException
```

### 핵심: 정의가 게시글의 규칙을 소유한다

```java
// 애플리케이션 서비스가 if 로 검사하는 게 아니라, 도메인이 조립 시점에 막는다 (Phase 2)
BoardPost.create(definition, authorId, authorName, title, content, attachments);
//  ├─ definition 이 첨부를 불허하는데 첨부가 있음        → 예외
//  ├─ attachments.size() > definition 최대 개수          → 예외
//  ├─ skin == GALLERY 인데 대표 이미지 없음              → 예외
//  └─ secret 요청인데 definition 이 비밀글 불허          → 예외
```

이렇게 하면 "이미지 없는 이미지게시판 글" 같은 깨진 상태가 애초에 만들어지지 않는다.
`BoardDefinition` 을 인자로 받는 것이 이 설계의 전부다 — 정의와 글이 같은 서비스에 있어야 하는 이유이기도 하다(§3-1).

### 분류(카테고리)는 새 테이블을 만들지 않는다

`BoardDefinition.categoryGroupCode` 가 **order-service 의 공통코드 그룹 코드 문자열**을 들고 있다
(예: `BOARD_CAT_NOTICE`). FK 가 아니라 **약결합 문자열 참조**다 — cross-DB FK 는 불가능하고,
필요하지도 않다. 게시판별 분류 관리 화면을 따로 만들지 않고 이미 있는 공통코드 화면을 재사용한다.

> 대가: 공통코드 그룹이 지워져도 board 는 모른다. 분류는 표시용 라벨이지 회계 값이 아니므로
> 라벨이 코드값으로 떨어지는 정도의 열화만 발생한다 — 감수한다.

### 왜 Phase 1 이 `BoardDefinition` 단독인가

`BoardDefinition` 은 게시글의 **모든 규칙을 담는 그릇**이다. 글을 먼저 만들면 규칙 없는 글이
쌓이고, 나중에 정의를 붙일 때 기존 데이터가 새 불변식을 위반한다. 그릇을 먼저 봉인한다.

---

## 5. 스키마

```sql
board_definitions(
  id BIGSERIAL PK,
  board_key VARCHAR(40) UNIQUE,              -- URL 세그먼트. 소문자·숫자·하이픈만
  name VARCHAR(100), description VARCHAR(300),
  skin VARCHAR(10),                          -- LIST|GALLERY|FAQ|QNA
  content_format VARCHAR(10),                -- TEXT|MARKDOWN|HTML
  category_group_code VARCHAR(40) NULL,      -- order 공통코드 그룹(약결합)
  comments_enabled BOOLEAN, secret_enabled BOOLEAN,
  attachments_enabled BOOLEAN,
  max_attachment_count INT, max_attachment_size_kb INT,
  allowed_extensions VARCHAR(200),           -- 'jpg,png,webp' — 비면 정책 기본값
  read_roles VARCHAR(100),                   -- NULL = 공개(비로그인 포함)
  write_roles VARCHAR(100), comment_roles VARCHAR(100), manage_roles VARCHAR(100),
  active BOOLEAN, created_at, updated_at TIMESTAMPTZ)

-- Phase 2~3
board_posts(id, board_id FK, category_code, title, content, content_format,
            author_id, author_name, pinned, secret, status, view_count, ...)
board_comments(id, post_id FK, parent_id, author_id, author_name, content, status, ...)
board_attachments(id, post_id FK, kind, original_name, storage_path,
                  content_type, size_bytes, width, height, thumbnail_path, sort_order)
```

핵심 인덱스(Phase 2): `board_posts(board_id, status, pinned DESC, created_at DESC)` — 목록 조회가
항상 이 순서라 정렬까지 인덱스로 흡수된다.

시간 컬럼은 `TIMESTAMPTZ` + 도메인 `OffsetDateTime`(UTC) 로 통일한다(DataStandard N1).

---

## 6. 메뉴 연결 — 이벤트가 아니라 화면에서

`POST /admin/menus` 는 **이미 존재한다**(`AdminMenuController`, path·area·requiredRole 지정 가능).
따라서 "수동 연결"에 필요한 백엔드 코드는 0줄이다.

| | 자동(이벤트) | 수동(채택) |
| --- | --- | --- |
| 신규 코드 | Outbox + 토픽 카탈로그 + 계약 스키마 + 양방향 계약 테스트 + order 컨슈머 + `menus.source` 컬럼 | 0 |
| board-service 성격 | 발행 서비스 | **완전 독립** |
| 일관성 | Eventual (실패 시 무기한) | 관리자 조작 즉시 |
| 게시판 삭제 시 | 메뉴 자동 제거 | 고아 메뉴 발생 가능 → 대조 배지로 대응 |
| 빌드 시점 정합 게이트 | **cross-DB 라 불가** | 동일 |

자동을 버린 이유는 세 가지다.

1. **자동화해도 실제 노동이 안 준다.** 메뉴 붙이기의 진짜 일은 *어느 그룹 밑에, 몇 번째로, 어떤
   아이콘으로, 어떤 역할에게* 인데 board-service 는 이걸 알 수 없다. 자동 등록은 항상 기본 위치에
   떨어뜨리고 관리자는 결국 메뉴 화면에서 옮긴다.
2. **빈도가 손익분기에 못 미친다.** 게시판은 시스템 수명 전체에서 5~20개 수준이다.
3. **자동 등록은 위험하다.** 게시판을 만드는 순간 전사 네비게이션이 바뀐다 — 테스트로 만든
   게시판, 오타 난 이름이 즉시 모두에게 노출된다. 수동이면 "만들고 → 채우고 → 올린다"는
   스테이징이 자연스럽게 생기고, `SYSTEM_BOARD_MANAGE` 와 `SYSTEM_MENU_MANAGE` 를 다른 사람에게
   줄 수 있다.

### 채택안: 하이브리드 (자동화를 백엔드가 아니라 화면에서)

```
BoardAdminPage 에서 게시판 생성
  → "메뉴에 추가" 액션 (부모 그룹·정렬·아이콘·역할 선택, path 는 /boards/{key} 로 자동)
  → 프론트가 기존 POST /admin/menus 를 한 번 더 호출
```

고아 메뉴 대응: 관리 화면이 게시판 목록과 `/admin/menus/flat` 을 **각각 호출해 프론트에서 대조**해
"메뉴 없는 게시판 / 링크 끊긴 메뉴"를 배지로 표시한다(cross-DB 조인이 아니라 화면단 대조).

이 결정은 되돌리기 쉽다 — 나중에 생성이 잦아지면 이벤트로 승격하면 되고, 반대 방향(토픽·계약·
컨슈머 회수)이 훨씬 비싸다.

---

## 7. 인가 모델

게시판별 권한은 **역할 allowlist 문자열**이다. permission 코드가 아니다(§3-2).

```java
definition.canRead(role)     // read_roles == null 이면 비로그인 포함 공개
definition.canWrite(role)
definition.canComment(role)
definition.canManage(role)   // 게시판 단위 운영(고정·숨김) — 게시판 자체 CRUD 와 다름
```

- 게시판 **정의 CRUD**(`/admin/boards/**`)는 `SYSTEM_BOARD_MANAGE` 가 아니라 **ADMIN 역할**로 막는다.
  permission 코드 판정은 order DB 를 읽어야 하므로 의존이 되살아난다. RBAC 시드에
  `SYSTEM_BOARD_MANAGE` 코드를 넣는 것은 메뉴 노출 필터링용이며, 실제 인가는 역할이 한다.
- 글 수정·삭제 권한은 Phase 2 에서 **JWT 주체(`uid`)와 `author_id` 대조**로 판정한다(IDOR 가드레일).
  요청 파라미터의 작성자 식별자는 절대 신뢰하지 않는다.

---

## 8. 첨부·이미지 (Phase 3 선설계)

`product` 의 `LocalFileSystemImageStorageAdapter` 를 **재사용하지 않는다** — 도메인 간 어댑터 공유는
헥사고날 위반이다. `board/application/port/out/StoreAttachmentPort` 를 새로 두고 자체 어댑터를
구현한다(추후 S3 전환은 포트 교체로 끝난다).

보안 필수 항목:
- 확장자 화이트리스트 + **매직바이트 검증**(확장자만 믿으면 안 된다)
- SVG 업로드 차단 또는 sanitize(스크립트 실행 벡터)
- 저장 파일명은 서버 생성 UUID — 경로 traversal 원천 차단
- ~~`content_format=HTML` 게시판은 **서버측 sanitize 필수**~~ → **Phase 2 에서 선행 구현**(§13)

---

## 9. Phase 로드맵

| Phase | 범위 | DoD |
| --- | --- | --- |
| **1** ✅ | 서비스 골격 + `BoardDefinition` 도메인·CRUD·관리 화면. 게시판을 만들 수는 있으나 글은 없다 | `:board-service:test` + JaCoCo LINE 90%, 직접·gateway 200 실측, `harness-audit` 통과 |
| **2** ✅ | `BoardPost`/`BoardComment` + LIST 스킨 + 인가 정책 | IDOR 테스트(소유권 대조가 도메인 안에 있음), 비밀글·숨김 가시성 테스트, 목록 조건 번역 테스트 |
| **3** ✅ | 첨부·이미지 + GALLERY 스킨 | 매직바이트·확장자 위장·SVG 차단·개수/크기·첨부 IDOR 테스트 (XSS 는 Phase 2 로 앞당겨 완료) |
| **4** ✅ | FAQ/QNA 스킨 + 서버 썸네일 + 고아 파일 청소 배치 | 세 항목 모두 완료 |

---

## 10. 열어둔 것

- **정의 변경의 소급 효과**: 첨부 허용 게시판을 나중에 불허로 바꾸면 기존 글의 첨부는 어떻게 되는가.
  결론 — **기존 데이터는 건드리지 않고 신규 작성만 막는다**(정책은 미래를 향한다). 화면도 여기에
  맞춘다(§16): 이미 붙은 파일은 그대로 보이고 '첨부 추가' 버튼만 사라진다. 스킨을
  `LIST → GALLERY` 로 바꾸는 것은 기존 글에 대표 이미지가 없을 수 있으므로 목록이 자리표시를 그린다(§14).

---

## 12. Phase 2 에서 굳힌 결정 (구현하며 드러난 것)

- **작성자 표시명은 마스킹 스냅샷**(`ad***`). JWT 에 닉네임이 없어 이메일뿐인데, 원문 이메일을
  board DB 에 적으면 PII 가 서비스 하나 더 넓게 퍼진다. 게시판이 필요한 것은 "사람이 알아볼 라벨"이지
  연락처가 아니다. **소유권 대조는 표시명이 아니라 `author_id`** 로 하므로 인가 정확도는 그대로다.
- **인가를 도메인 안에 둔다.** `BoardPost.edit(actor, ...)` 처럼 애그리거트가 주체를 받아 스스로
  판정한다. 컨트롤러에 두면 어댑터를 하나 더 만들 때(관리 콘솔·배치·내부 API) 조용히 빠지고,
  그게 IDOR 이 된다.
- **가시성은 질의 조건으로 번역한다.** 페이지를 읽어 온 뒤 자바에서 걸러 내면 총건수와 페이지
  크기가 어긋난다(20건 요청 → 비밀글 3건 제외 → 17건, 총건수는 여전히 전체). 판정 기준은
  도메인과 같고, 번역만 응용 계층이 한다(`PostSearchCriteria`).
- **동적 조건은 Specification 으로.** `:param IS NULL OR col = :param` JPQL 은 PostgreSQL 에서
  타입 추론이 실패해 `bytea` 비교 오류가 난 전력이 있다. Criteria 는 조건을 붙이거나 안 붙이므로
  null 파라미터가 애초에 바인딩되지 않는다.
- **삭제는 상태 전이.** 글도 댓글도 물리 삭제하지 않는다. 댓글이 달린 글을 지우면 대화의 앞말이
  사라지고, 신고·감사 대응에서 "무엇이 지워졌는지"를 답할 수 없다. 삭제된 댓글은 원문 대신
  자리표시(`삭제된 댓글입니다.`)만 응답 경로로 나간다.
- **숨김(HIDDEN)과 삭제(DELETED)를 가른다.** 숨김은 운영자가 되돌릴 수 있는 조치, 삭제는 작성자의
  의사표시다. 하나로 합치면 운영자가 내린 글을 작성자가 되살릴 수 있게 된다.
- **답글은 1단까지.** 무한 중첩은 화면에서 읽을 수 없게 되고 조회가 재귀로 번진다. 제한은 데이터가
  쌓이기 전에 걸어야 한다.
- ~~스킨 렌더는 Phase 3 까지 LIST 로 떨어뜨린다~~ → **Phase 4 에서 4종 전부 구현**. GALLERY 는 그리드,
  FAQ 는 아코디언(펼칠 때 본문 지연 로드), QNA 는 답변완료/대기 배지. 배지는 목록 응답의 `commentCount`
  를 쓰고 그 값은 페이지 전체를 한 번의 질의로 센다.

---

## 13. sanitize 를 Phase 3 에서 앞당긴 이유

Phase 2 종료 시점의 실측: 저장소에 `dangerouslySetInnerHTML` 이 한 곳도 없고 본문은 React 텍스트
자식으로 렌더돼 이스케이프된다 — **당장의 실행 위험은 0** 이었다. 그런데도 앞당긴 이유는 긴급성이
아니라 순서 비용이다.

1. **위험은 실행이 아니라 축적에 있다.** 오늘 안전한 건 데이터가 깨끗해서가 아니라 렌더러가
   이스케이프해서다. 그 사이 `<img src=x onerror=...>` 는 `board_posts.content` 에 원문 그대로
   쌓이고, 나중에 HTML 렌더를 켜는 **한 줄**이 그동안 쌓인 모든 행을 동시에 발화시킨다.
2. **미루면 백필이 되는데, 그건 되돌릴 수 없다.** 쓰기 시점 정화는 경계 하나면 끝이지만, 쌓인 뒤에
   하려면 사용자가 쓴 글을 서버가 임의로 고쳐야 하고 무엇이 공격이고 무엇이 정당한 마크업인지
   사후에는 판정할 수 없다. `POSTED` 전표를 수정하지 않고 역분개만 하는 것과 같은 이유다.
3. **훅만 있고 구현이 없는 상태가 가장 위험하다.** 도메인에 `requiresSanitize()` 가 이미 있어서
   다음 사람은 "저장된 건 정화됐겠지"라고 더 강하게 가정한다.
4. **Phase 3 과 결이 다르다.** 첨부는 바이너리 경로(매직바이트·traversal·썸네일), sanitize 는 텍스트
   경로다. 묶으면 리뷰 초점이 흩어지고 보통 더 지루한 쪽이 대충 처리된다.

구현: `SanitizeHtmlPort`(응용 포트) ← `JsoupHtmlSanitizerAdapter`(jsoup `Safelist.relaxed()`,
화이트리스트). **판단은 도메인**(`BoardContentPolicy.requiresSanitize()`), **수행은 어댑터**,
둘을 잇는 곳은 `BoardContentSanitizer` 하나뿐이고 작성·수정 두 경로가 모두 지난다 —
한쪽만 막으면 "수정으로 심는" 우회가 남는다.

**MARKDOWN 은 정화하지 않는다.** 마크다운 원문을 HTML 정화기에 넣으면 코드 블록 안의 정당한 예시
태그까지 사라진다. 대신 마크다운 렌더러가 raw HTML 을 끄는 것이 계약이다(프론트 책임). 댓글도
HTML 렌더 경로가 없는 평문이라 정화 대상이 아니다.

프론트는 `contentFormat === 'HTML'` 일 때만 마크업으로 렌더한다. 이 분기를 다른 형식으로 넓히면
정화를 거치지 않은 원문이 그대로 실행된다.

---

## 14. Phase 3 에서 바꾼 결정 — GALLERY 대표 이미지 불변식의 이동

§4 는 `BoardPost.create` 가 "GALLERY 스킨인데 대표 이미지가 없으면 예외"를 강제한다고 적었다.
**구현하며 이 불변식을 옮겼다** — 업로드가 글 생성 <b>이후</b>의 별도 요청이라 생성 시점에는
이미지가 있는지 알 수 없기 때문이다. 유지하려면 글을 DRAFT 로 만들고 첨부 후 발행하는 2단계가
필요한데, 그 복잡도는 얻는 것에 비해 크다.

대신 이렇게 나눴다:

- **게시판 정의 시점**: GALLERY ⇒ 첨부 필수(이미 §2 부터 강제 중). "이미지를 올릴 수단이 있는가"는 보장된다.
- **목록 렌더**: 이미지 없는 글은 자리표시로 그린다. 목록에서 빼지 않는다 — 글은 존재하는데 안 보이는 편이 더 나쁘다.

## 15. 첨부 보안 — 무엇을 믿지 않는가

첨부 사고는 전부 "선언과 실제가 다르다"에서 온다. 그래서 요청이 주는 값 중 **믿는 것이 하나도 없다**.

| 요청이 주장하는 것 | 우리가 하는 일 |
| --- | --- |
| 파일명 | 표시용으로만 쓴다. 경로 구분자·제어문자·따옴표를 걷어내고(Content-Disposition 분리 방지) 저장 경로에는 한 글자도 쓰지 않는다 |
| Content-Type | 버린다. 매직바이트로 다시 판정하고, 그 판정값을 DB 에 저장해 다운로드 응답에 쓴다 |
| 확장자 | 판정 결과와 대조한다. 다르면 거절(`shell.jpg` 우회 차단). 단 jpg/jpeg, docx/zip 처럼 같은 형식의 다른 이름은 별칭으로 통과 |
| 크기 | 서블릿 상한(20MB) → 게시판 정책(KB) 두 단계로 자른다 |

추가로:

- **SVG·HTML·XML 은 정책이 허용해도 받지 않는다.** 이미지처럼 보이지만 스크립트를 담을 수 있는 문서다.
- **저장 파일명은 서버가 만든 UUID**. 경로 조작을 막는 가장 확실한 방법은 입력을 정화하는 게 아니라 입력을 쓰지 않는 것이다.
- **다운로드 헤더 3종**: 판정된 Content-Type + 이미지만 `inline`(나머지는 `attachment`) + `X-Content-Type-Options: nosniff`.
  셋 중 하나라도 빠지면 브라우저의 추측이 우리 판정을 이긴다.
- **첨부는 글의 일부다.** 볼 수 없는 글의 첨부는 404 이고, 첨부 삭제 권한은 글 수정 권한을 그대로 따른다
  — 따로 두면 "글은 못 고치는데 첨부는 지울 수 있는" 상태가 생긴다.
- **순서가 곧 안전**: 판정 → 검증 → 저장 → 행 기록. 저장 뒤에 거절하면 거절당한 파일이 디스크에 남는다.
  DB 기록이 실패하면 방금 쓴 파일을 손으로 되돌린다(파일시스템은 트랜잭션 밖이다).

### 운영 메모 — 첨부 볼륨 소유권 (실측으로 잡은 사고)

컨테이너는 비루트(`spring`)로 도는데, named volume 의 **마운트 지점이 이미지에 없으면 Docker 가
그 디렉터리를 `root:root` 로 만들어 붙인다.** 그러면 첨부 업로드가 `Permission denied` 로 죽는다.
로컬 `bootRun` 은 자기 계정으로 쓰기 때문에 **절대 드러나지 않는** 종류의 사고다 — 컨테이너를
실제로 띄워 보고서야 나왔다.

그래서 루트 `Dockerfile` 이 런타임 스테이지에서 경로를 미리 만들고 소유권을 넘긴다:

```dockerfile
RUN mkdir -p /var/lib/lemuel/board-attachments \
    && chown -R spring:spring /var/lib/lemuel
USER spring:spring
```

마운트 경로를 바꾸려면 **이미지의 이 경로도 같이 바꿔야 한다**. 하위 디렉터리에만 볼륨을 붙이면
Docker 가 그 하위 경로를 다시 root 로 만들기 때문에 같은 문제가 재발한다.

### 남은 것 (Phase 4)

- ~~서버 썸네일 생성 없음~~ → **해소(Phase 4)**. 업로드 시 긴 변 400px PNG 축소본을 함께 저장하고
  목록은 `/attachments/{id}/thumbnail` 을 가리킨다. **못 만드는 경우가 정상 경로**다 — JDK ImageIO 는
  WEBP 리더가 없고 손상 이미지도 들어온다. 그때 축소본 없이 저장하고 조회 시 원본으로 떨어뜨린다
  (썸네일이 첨부 업로드를 죽이면 안 된다).
- ~~텍스트·CSV 첨부 불가~~ → **해소**. 매직바이트가 없는 형식은 <b>내용 스니핑</b>으로 가린다
  (NUL 없음 · UTF-8 디코딩 · 제어문자 비율). "확장자가 .txt 면 통과"로 갔다면 검사 자체가
  사라졌을 것이다 — 아무 바이너리나 이름만 바꿔 올릴 수 있게 된다.
  **안전은 판정이 아니라 서빙에서 온다**: 텍스트는 언제나 `attachment` + `nosniff` 로 나가므로
  HTML 을 `.txt` 로 올려도 브라우저가 문서로 열지 못한다(실측 확인).
- ~~고아 파일 청소 배치 없음~~ → **해소(Phase 4)**. 매일 04:10(KST) DB 가 참조하지 않는 파일을 지운다.
  **유예 기간(기본 24시간)이 안전장치**다 — 업로드는 '파일 저장 → DB 기록' 순서라 그 사이 정상 파일도
  잠깐 고아처럼 보인다. 경계(정확히 24시간)는 남긴다: 남은 파일은 다음 바퀴가 있지만 잘못 지운 파일은
  다음이 없다. ShedLock 은 두지 않았다 — 삭제가 멱등이라 두 인스턴스가 같은 고아를 지워도 결과가 같다.

---

## 16. 정책을 끄면 무슨 일이 일어나는가 (G-6 해소)

첨부 허용을 끄면 **세 층이 서로 다르게 반응하고 있었다**. 데이터·화면·서버가 어긋나면 운영자는
정반대 두 질문을 동시에 하게 된다 — "껐는데 왜 파일이 사라졌지?"와 "껐는데 왜 아직 받아지지?"

| 층 | 고치기 전 | 지금 |
| --- | --- | --- |
| 업로드 | 400 (차단) | 400 — 그대로 |
| 상세 화면 | 첨부 섹션이 통째로 사라져 **기존 파일이 안 보임** | **그대로 보이고 '추가' 버튼만 사라짐** |
| 서버 조회·다운로드 | 200 (직링크 살아 있음) | 200 — 그대로 |

고친 방식은 **화면을 데이터에 맞추는 것**이다(서버를 막는 쪽이 아니라). 서버를 막으면 이미 배포된
링크가 죽는데, 그건 정책 변경이 의도한 바가 아니다 — 정책이 막는 것은 <b>새로 올리는 것</b>뿐이다.

구현은 두 줄로 요약된다:

- **상세 응답이 첨부를 함께 싣는다.** 화면이 정의의 플래그가 아니라 <b>실제 데이터</b>로 렌더를
  정하게 되고, 덤으로 왕복이 하나 줄었다(정의·글·댓글·첨부 4회 → 3회).
- **관리 화면이 이 동작을 말해 준다.** 첨부를 끈 채 저장하려 하면 "새 업로드만 막히고 기존 파일은
  남는다"는 안내가 뜬다. 놀람의 절반은 동작이 아니라 <b>말해 주지 않은 것</b>에서 온다.
