-- V20260825190000: "오늘 한눈에" 일별 집계 테이블
--
-- 왜 이 테이블이 필요한가
-- ────────────────────────
-- 관리자 대시보드는 지금까지 /orders/admin/all · /users/admin/all · 전체 상품 · 전체 쿠폰을
-- 브라우저로 통째로 내려받아 filter().reduce() 로 카드 숫자를 만들었다. 서비스마다 DB 가
-- 갈라져 있어 서버 조인이 불가능하자 그 조인이 클라이언트로 옮겨 간 것이다. 결합은 그대로고
-- 회원 이메일 전량이 관리자 브라우저로 내려오는 비용만 추가됐다. 게다가 그 숫자는 "오늘"이
-- 아니라 "전체 기간"이다.
--
-- 그래서 각 서비스가 이미 발행하는 도메인 이벤트를 운영 서비스가 구독해 이 테이블 한 장에
-- 누적하고, 화면은 이 테이블만 읽는다. 서비스 경계를 넘지 않으면서 서버가 계산한다.
--
-- ops_metric_bucket 과 다른 이유
-- ──────────────────────────────
-- 신호 버킷(ops_metric_bucket)은 5분 단위 *상대 지표*(실패율·z-score)용이라 중복 배달 몇 건에
-- 둔감하고, 그래서 멱등을 의도적으로 쓰지 않는다. 여기 값은 "오늘 매출 45만원" 처럼 사람이
-- 사실로 읽는 *절대값*이라 at-least-once 재전송 한 번에 그대로 부풀어 오른다. 이 테이블을
-- 채우는 컨슈머는 processed_events 멱등을 반드시 거친다(3단 방어 2단계).
--
-- 하루 4행(지표 수)만 쌓이므로 파티셔닝·리텐션 대상이 아니다.

CREATE TABLE ops_daily_metric (
    -- 캘린더 날짜는 KST 기준이다. UTC 로 두면 오전 9시 이전 매출이 "어제"로 잡혀
    -- 운영자가 보는 오늘과 표가 어긋난다. 변환은 애플리케이션이 하고 여기엔 결과만 적는다.
    metric_date          DATE          NOT NULL,
    metric_key           VARCHAR(40)   NOT NULL,   -- ORDER_CREATED / PAYMENT_CAPTURED / ...
    event_count          BIGINT        NOT NULL DEFAULT 0,
    -- 금액 없는 지표(가입 등)는 0 으로 남는다. NULL 을 쓰지 않는 것은 SUM 경로에서
    -- NULL 전파를 신경 쓰지 않기 위해서다 — "금액 없음"은 지표 정의로 이미 알 수 있다.
    amount_sum           NUMERIC(18,2) NOT NULL DEFAULT 0,
    -- 금액을 읽지 못한 이벤트 수. 환불 이벤트는 계약상 delta(refundAmount) 없이 누적액만
    -- 올 수 있는데, 누적액을 합계에 더하면 부분환불이 겹쳐 계산된다. 이전 상태 없이 delta 를
    -- 역산하는 것은 추측이므로, 그런 건은 건수만 세고 여기에 표시해 화면이 "일부 금액 미상"을
    -- 말할 수 있게 한다. 합계를 조용히 틀리게 만드는 대신 모른다고 말한다.
    amount_unknown_count BIGINT        NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    PRIMARY KEY (metric_date, metric_key)
);

-- 화면은 언제나 특정 하루를 통째로 읽는다(PK 선두 컬럼으로 커버되므로 별도 인덱스는 두지 않는다).
