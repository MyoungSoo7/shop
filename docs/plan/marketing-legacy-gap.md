# marketing-service — 레거시 대비 미이관 목록

ADR 0045 로 옮긴 범위와 원본 `ssgb2e-front_20250721`(Spring MVC + JSP + MyBatis + Oracle,
패키지 `kr.co.dodoom`) 을 **파일 단위로 대조한 결과**다. "다 옮겼다" 를 주장하려면 무엇을
안 옮겼는지가 적혀 있어야 한다. 안 적으면 남은 것이 부채가 아니라 **없던 일**이 된다.

측정 기준 커밋: 이관본 `ccc20a6`(main) / 레거시 `ssgb2e-front_20250721` 기본 브랜치.
레거시 저장소는 읽기 전용으로만 열었다.

---

## 0. 먼저: ADR 0045 의 사실 하나가 틀렸다

ADR 0045 는 레거시 결함 ① 을 *"수량 확인 코드가 없다. `// 아이템 수량 확인` 이라는 주석
아래가 비어 있다"* 라고 적었다. **주석 아래는 비어 있지 않다.**

`EventServiceImpl.java:482` 아래에는 `event.selectLuckyboxItem` 조회와 `removeIf` 필터가
실제로 있다(총 수량 소진·일일 수량 소진·확률 0 인 아이템 제거). 비어 있는 주석은 다른
자리들이다:

| 줄 | 주석 | 아래 |
|---|---|---|
| `EventServiceImpl.java:478` | `//참여횟수 확인(금액구분/주문상태/주문금액)` | 비어 있음 |
| `EventServiceImpl.java:480` | `//참여횟수 제한` | 비어 있음 |
| `EventServiceImpl.java:537` | `//당첨아이템 등록` | 비어 있음 |
| `EventServiceImpl.java:538` | `//마일리지인 경우 지급일 확인 후 지급` | 비어 있음 |
| `EventServiceImpl.java:539` | `//마일리지 유효기간 있는경우 설정` | 비어 있음 |

그래서 실제 결함은 ADR 이 적은 것보다 **크다**. 레거시 럭키박스는 추첨을 하고 나서

- 당첨을 **어디에도 저장하지 않고**,
- 마일리지를 **지급하지 않으며**,
- 응답으로 `String.format("itemtype=%s, mileage=%s, text=%s", ...)` 라는 **디버그 문자열**을
  돌려준다 (`EventServiceImpl.java:543`, 바로 위 542 줄에는 주석 처리된
  `// resultVO.setResult_msg("경품당첨!!!!");` 가 남아 있다).

수량 확인이 무력한 것도 코드가 없어서가 아니라 **세는 대상이 영원히 비어 있어서**다. 필터는
`TBL_LUCKYBOX_APPLY` 를 `COUNT` 해서 소진을 판정하는데(`EventDao.xml:480-481`), 저장소 전체
매퍼에 그 테이블로 들어가는 `INSERT`·`UPDATE` 가 **한 문장도 없다**. `EventDao.xml` 의 쓰기
구문은 `insertAttendanceApply`·`insertAttendanceSuccess` 둘뿐이고 둘 다 출석용이다. 같은
이유로 럭키박스 참여내역 화면(`selectLuckyboxApplyList`)은 **항상 빈 목록**이다.

정리하면 ADR 의 **결론("몇 명에게 나갔는지 확인할 방법이 없었다")은 맞고, 근거로 든 사실은
틀렸다.** 근거가 틀린 채로 남으면 다음 사람이 레거시를 열었을 때 주석 아래에 있는 코드를 보고
문서 전체를 의심하게 된다. ADR 0045 를 이 커밋에서 고친다.

출석체크는 이런 구멍이 없다 — 참여 등록·성공 등록·일일 마일리지·성공 마일리지가 전부
`mileageService.insertPayMileage` 까지 실제로 이어져 있다(`EventServiceImpl.java:307-378`).
**"럭키박스는 껍데기, 출석은 동작"** 이 레거시의 실제 상태다.

---

## 1. 옮긴 것 — 화면·기능 대조표

| 레거시 | 이관본 | 판정 |
|---|---|---|
| `POST /mypage/attendance` (달력) | `GET /api/promotions/attendance` → `AttendanceBoardView` | 이관 |
| `POST /mypage/attendanceProcess` | `POST /api/promotions/attendance/check-in` | 이관 |
| `POST /mypage/luckybox` (경품·참여내역) | `GET /api/promotions/luckybox` → `LuckyboxBoardView(prizes, myDraws)` | 이관 (+ 참여내역이 실제로 채워짐) |
| `POST /mypage/luckyboxProcess` | `POST /api/promotions/luckybox/draw` | 이관 (+ 당첨 기록·포인트 지급이 실제로 일어남) |
| 누적(`N`)·연속(`Y`)·매일(`C`) 출석 | `StreakRule` · `AttendanceStreak` | 이관 |
| `ED`/`WD`/`WE` 요일 규칙 | `DayTypeRule` | 이관 |
| `event_message1/2/3` | `AttendanceMessages` | 이관 |
| 일일/성공 마일리지 2단 지급 | `dailyRewardPoints` / `goalRewardPoints` | 이관 |
| 마일리지 유효기간(`eveuse_stdate`/`enddate`) | `rewardExpiresFrom` / `rewardExpiresOn` → `RewardGrant.expiresOn` | 이관 |
| 확률 누적합 추첨(`SecureRandom`) | `PrizeDraw` + `SecureRandomRollSource` | 이관 |
| 경품 수량 제한(총/일일) | `tryReserve` 조건부 UPDATE | 이관 (+ 경합 제거) |
| 캠페인 등록·수정(운영) | `/admin/promotions/**` 13 엔드포인트 + `/admin/system/promotions` | 이관 (레거시엔 운영 화면이 이 저장소에 없었음) |

---

## 2. 안 옮긴 것

### ① 나의 이벤트 목록 — 종료분 이력과 페이징 (기능 축소)

레거시 `GET /mypage/eventList` 는 출석·럭키박스를 `UNION` 해서 **진행중(2) + 종료(3)** 를
**최근 6개월** 범위로, **상태 필터 + 페이징**과 함께 보여 준다(`EventDao.xml:selectEventList`,
`MyPageCustomerController.java:1007-1037`).

이관본 `GET /api/promotions` 는 `PromotionCatalogService.runningOn(today)` 하나뿐이다 —
**진행 중인 것만, 페이징 없이, 종료 이력 없이** 돌려준다. 사용자가 "지난달에 참여했던
이벤트"를 다시 찾아볼 길이 없다.

> 규모가 작을 때는 문제가 아니지만, 이건 *성능*이 아니라 *기능*이 빠진 것이다. 나중에
> 페이징을 붙이는 것과, 종료분을 아예 안 보여 주기로 정하는 것은 다른 결정이다.

### ② `POST /mypage/promotion` — 진행중 이벤트 바로가기 라우팅

레거시는 "이벤트" 메뉴를 누르면 서버가 **출석 > 럭키박스 > 목록** 순으로 판단해 화면을
골라 준다(`MyPageCustomerController.java:1057-1075`). 진행 중인 게 없으면 목록으로 보내며
"진행중인 이벤트가 없습니다." 를 띄운다.

이관본에는 이 분기가 없다. `/promotions` 는 항상 목록이다. 클라이언트가 `GET /api/promotions`
결과로 같은 판단을 할 수는 있지만 **지금 그렇게 하고 있지 않다.**

### ③ 휴대폰번호 등록 회원 제한 (참여 조건)

레거시는 출석·럭키박스 **양쪽 모두** 진입 시점에
`event.selectMemberHp` 로 회원의 휴대폰번호를 조회하고, 비어 있으면
*"휴대폰번호가 등록된 회원만 본 이벤트에 참여하실 수 있습니다."* 로 차단한다
(출석 `EventServiceImpl.java:256-260` / 럭키박스 `:438` 이하 같은 형태).

이관본에는 이 조건이 **없다**. 회원 속성이 marketing 의 소유가 아니라는 것이 이유가 될 수는
있지만, ADR 에도 이 문서에도 적혀 있지 않았다. **경품 배송용 연락처 확보 장치**였을
가능성이 높다.

### ④ 가입일·주문금액·배송상태 조건 — 저장은 되는데 아무도 안 봤다 ★ (해결됨 — (b) 제거)

`LuckyboxCampaign` 은 `memberJoinedFrom`(가입일 조건), `amountBasis`+`minOrderAmount`(주문금액
조건), `shippingStatusRequired`(배송상태 조건) 을 **필드로 들고, 컬럼으로 저장하고, 운영
API 로 입력받았다.** 그런데 `assertDrawAllowed` 는 이 셋을 **보지 않았다** — 상태와 기간만
본다.

**이 문서의 초판은 여기서 "화면에는 그렇게 설정되고" 라고 썼는데, 그건 틀렸다.** 실제로
확인해 보니 운영 화면(`PromotionAdminPage.tsx`)에는 이 셋을 넣는 입력칸이 처음부터 없었고,
`emptyLuckybox()` 가 전부 `null` 로 하드코딩해 보내고 있었다. 즉 값이 들어올 수 있는 경로는
API 를 직접 호출하는 것뿐이었다. 그래도 결론은 같다 — API 로 넣으면 저장은 되고 판정은 안
된다. 조건 자체가 없는 것보다 나쁘다, 있는 줄 알기 때문이다.

레거시도 이 조건을 강제하지 않았다(위 §0 의 `:478` `:480` 빈 주석이 정확히 그 자리다).
**같은 구멍을 필드까지 만들어 옮겨 온 셈**이다. 이관 결함 아님 — 이관된 결함이다.

**선택은 (b) 제거였다.** (a) 강제로 가지 않은 이유는 취향이 아니라 **데이터가 없어서**다.
서비스 간 연계는 Kafka 이벤트뿐인데(ADR 0024/0035), `shared-common/.../topic-catalog.json`
22개 토픽을 전수로 보면:

- 배송 단계를 알리는 토픽이 **하나도 없다.** 배송상태 조건은 만들 소스 자체가 없다.
- `lemuel.order.created`·`lemuel.payment.captured` 는 있지만, 실결제금액과 주문총액을
  배송 단계별로 구분해 인정하려면 **회원별 구매금액 읽기 모델**을 새로 만들어야 한다.
- `lemuel.user.registered` 는 있지만 **컨슈머 가동 이후분만 쌓인다.** 백필 없이 가입일
  게이트를 켜면 기존 회원이 전부 "가입일 미달" 로 차단된다 — 지금보다 나쁘다.

셋 다 order-service 를 함께 바꿔야 하는 별도 작업이라 이 변경에 섞지 않았다.

지운 자리:

| 층 | 내용 |
|---|---|
| 도메인 | `LuckyboxCampaign` 필드·생성자·접근자, `AmountBasis`/`ShippingStatusRequirement` 삭제 |
| 유스케이스 | `CreateLuckyboxCampaignCommand` 4개 컴포넌트 |
| 어댑터 | `LuckyboxCampaignJpaEntity` 컬럼 4개, `LuckyboxCampaignRequest` 4개 필드 |
| 스키마 | `V5__drop_unenforced_luckybox_entry_conditions.sql` (CHECK 2개 + 컬럼 4개) |
| 프론트 | `LuckyboxCampaignRequest` 4개 필드 |

`LuckyboxCampaignResponse` 는 원래부터 이 셋을 담지 않았다 — **저장한 값을 되읽을 수조차
없었다**는 뜻이고, 아무도 안 쓴다는 방증이기도 하다.

**되살리려면 순서가 있다.** ① 해당 데이터를 실어 나르는 토픽부터 만들고(배송) 또는 백필
경로를 정하고(가입일·구매금액), ② marketing 안에 읽기 모델을 세우고, ③ 그 다음에 컬럼과
필드를 되돌린다. ③ 만 먼저 하면 오늘과 똑같은 "저장되는데 안 먹는" 상태로 돌아간다.
되돌리기 자체는 `ALTER TABLE ... ADD COLUMN` 한 줄이다(보존할 값이 없다).

`LuckyboxCampaignTest.참여_자격은_상태와_기간뿐이다` 가 이 결정을 고정한다.

**곁가지로 나온 진짜 고장:** 운영 화면의 참여 조건 드롭다운이 `ALL_MEMBERS`/`NEW_MEMBER`/
`PURCHASER` 를 보내고 있었는데, 이 셋은 `EntryCondition`(`PER_DAY`/`PER_PERIOD`) 상수가
아니다. 즉 **그 화면에서는 럭키박스 캠페인을 아예 등록할 수 없었다** — 잭슨이 역직렬화에
실패한다. 프론트 테스트가 목(mock) API 를 상대로 그 잘못된 값을 확인하고 있어서 초록이었다.
드롭다운을 실제 값으로 고치고(라벨도 "참여 조건" → **참여 횟수** — 이 값은 대상이 아니라
빈도다), 선택지 값이 어긋나면 깨지는 테스트를 추가했다.

### ⑤ 기획전·브랜드 프로모션 (범위 밖 — 명시)

`ProductController` 에 `/promoExhibitList` · `/promoExhibitDetailList` · `/promoBrandList` ·
`/promoBrandDetailList` 4개, 그리고 `PromotionDao`·`PromotionPrdDao`·`PromotionMainVO`·
`PromotionPrdVO` 와 `views/design/promoBrandList.jsp`·`promoExhibitList.jsp` 가 있다.

이건 **상품 진열(기획전/브랜드관)** 이지 이벤트 프로모션이 아니다. ADR 0045 의 범위가 아니고
옮길 계획도 없다. 다만 이름이 `promo*` 라서 "프로모션은 다 옮겼다" 는 문장과 충돌한다 —
그래서 여기 적어 둔다. 옮긴다면 갈 곳은 marketing 이 아니라 **상품 카탈로그** 쪽이다.

### ⑥ 마일리지 원장 자체

`MileageService.insertPayMileage`·`PointDao`·`PointProcessDao`·`CouponDao` 는 옮기지 않았다.
이건 **의도된 결정**이고 ADR 0045 에 이미 적혀 있다(포인트 원장은 order 소유, marketing 은
요청만 한다). 누락이 아니라 경계다.

---

## 3. 우선순위 제안

| | 항목 | 성격 | 크기 |
|---|---|---|---|
| ~~1~~ | ~~④ 무시되는 참여 조건~~ → **(b) 제거로 종료** (§2 ④) | **거짓 설정** | 완료 |
| 1 | ③ 휴대폰번호 조건 — 필요 여부를 운영 주체에게 확인 | 정책 미확인 | 확인 먼저 |
| 2 | ① 이벤트 목록 종료분 + 페이징 | 기능 축소 | 중간 |
| 3 | ② 바로가기 라우팅 | UX | 작음 (프론트) |
| — | ⑤ 기획전/브랜드 | 범위 밖 | — |

④ 를 맨 위에 뒀던 이유는 크기가 아니라 **성격**이었다. 나머지는 "없다" 고 알 수 있는
것이고, ④ 는 **있다고 보이는데 없는 것**이었다. 그래서 먼저 걷어냈다.
