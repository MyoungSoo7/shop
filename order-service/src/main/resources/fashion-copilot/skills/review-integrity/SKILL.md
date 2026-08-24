---
name: review-integrity
description: 리뷰 무결성·어뷰징 방어 규칙 — 1인 1리뷰, rating 검증, 구매검증 공백, 어뷰징 패턴 카탈로그. 리뷰 기능을 작성하거나 어뷰징을 조사할 때 로드.
---

# Review Integrity (리뷰 신뢰성·어뷰징 방어)

패션 이커머스에서 리뷰(특히 사이즈 후기 — "정사이즈인가요?")는 구매 전환의 핵심이다.
뒷광고·조작 리뷰는 무신사가 실제로 겪은 신뢰 문제 — 코드 레벨 방어선과 데이터 공백을 정확히 알아야 한다.

## 현재 코드의 방어선

- **1인 1리뷰**: `reviews UNIQUE(user_id, product_id)` (`uq_review_user_product`) 하드 제약 +
  `existsByUserIdAndProductId` 소프트 체크. **하드 제약이 최종 방어선** — 소프트 체크만 믿는
  코드를 만들지 마라 (동시 요청은 UNIQUE 위반으로 잡힌다).
- **rating 검증**: `Review` 도메인이 1~5 범위를 강제. 컨트롤러에서 재검증을 생략해도 도메인이 막는다.
- **수정/삭제는 작성자 본인만** (`ReviewService`).

## ⚠️ 알려진 공백 — 구매 검증 부재

`reviews` 에는 주문/결제 FK 도 `verified_purchase` 플래그도 **없다**. 즉 비구매자도 리뷰를 쓸 수
있는 구조다. 이 공백을 전제로:

- 어뷰징 진단은 reviews ↔ orders 를 `(user_id, product_id)` 로 **교차조회**해야 한다
  (해당 상품을 실제 구매한 사용자의 리뷰인지).
- "구매 검증 리뷰" 요구가 오면 리뷰 작성 시점에 주문 이력 검증 + `verified_purchase` 컬럼
  신설(설계서 §8 Phase 2)을 제안하라. 리뷰 테이블에 order_id 를 덧붙이는 김에 UNIQUE 를
  (user_id, product_id) → (user_id, order_item_id) 로 바꾸자는 제안은 **정책 변경**(재구매 리뷰 허용)이므로
  별도 결정으로 분리하라.

## 어뷰징 패턴 카탈로그 (조사 시 확인 순서)

| 패턴 | 시그널 | 조회 축 |
|---|---|---|
| 비구매 리뷰 공세 | 구매 이력 없는 user 의 리뷰 비율 급증 | reviews ⋈ orders (user_id, product_id) |
| 집중 작성 | 특정 상품에 짧은 시간창 내 리뷰 폭증 | reviews.created_at 히스토그램 |
| rating 편중 | 신생 계정들의 5점(또는 경쟁사 1점) 쏠림 | user 가입일 × rating 분포 |
| 복붙 본문 | 동일/유사 content 반복 | content 유사도 (수동 확인) |

## 작성 규칙 (리뷰 기능 수정 시)

- 리뷰 본문에 개인정보(연락처·주소)가 들어올 수 있다 — 노출 API 에서 마스킹을 고려하라.
- 리뷰 삭제는 물리 삭제 유지(현재 구조). 어뷰징 "숨김" 요구가 오면 soft-hide 플래그 신설을 제안하고
  통계(평점 평균)에서 제외되는지까지 설계하라.
- company-service(기업 뉴스 감성분석)와 혼동 금지 — 상품 리뷰는 order-service `review` 패키지 소관.
