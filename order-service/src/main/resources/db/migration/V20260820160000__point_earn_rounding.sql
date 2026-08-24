-- V20260820160000: 적립 단위·라운딩 방식을 정책 데이터로 끌어올린다
--
-- [문제]
--   적립률(earn_rate)은 기간을 가진 데이터인데, 라운딩은 도메인 코드에 상수로 박혀 있었다
--   (원 단위 · 버림 고정). "10 원 단위 적립", "100 원 단위 절사" 같은 흔한 판촉 정책을 반영하려면
--   배포가 필요하고, 그 사이 실제 판촉비는 정책과 어긋난다. 요율만 데이터로 두고 단위를 코드에
--   두는 것은 절반만 데이터화한 상태다.
--
-- [조치] 단위와 방식을 같은 행에 둔다.
--   rounding_unit  1 / 10 / 100 / 1000 …  (적립 단위)
--   rounding_mode  DOWN(버림) / HALF_UP(반올림) / UP(올림)
--
--   방식은 java.math.RoundingMode 전체가 아니라 세 값만 허용한다 — UNNECESSARY 같은 값이
--   금액 정책 컬럼에 들어오면 적립 시점에 예외로 터진다.
--
-- [기존 행] DEFAULT 1 · 'DOWN' 이라 지금까지의 적립액이 그대로 재현된다(하위 호환).
--
-- 참고: 레거시 커머스는 "1 원 단위로 반올림한 뒤 단위로 버림"이라 단위가 1 보다 크면 반올림·올림
--   설정이 사실상 무의미했다. 여기서는 방식을 단위 경계에 적용해 설정이 실제로 금액을 바꾼다.

ALTER TABLE point_earn_policy
    ADD COLUMN IF NOT EXISTS rounding_unit INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS rounding_mode VARCHAR(10) NOT NULL DEFAULT 'DOWN';

ALTER TABLE point_earn_policy
    DROP CONSTRAINT IF EXISTS chk_pep_rounding_unit;
ALTER TABLE point_earn_policy
    ADD CONSTRAINT chk_pep_rounding_unit CHECK (rounding_unit > 0);

ALTER TABLE point_earn_policy
    DROP CONSTRAINT IF EXISTS chk_pep_rounding_mode;
ALTER TABLE point_earn_policy
    ADD CONSTRAINT chk_pep_rounding_mode CHECK (rounding_mode IN ('DOWN', 'HALF_UP', 'UP'));

COMMENT ON COLUMN point_earn_policy.rounding_unit IS
    '적립 단위(원) — 1/10/100/1000. 이 단위 경계에서 rounding_mode 가 방향을 정한다';
COMMENT ON COLUMN point_earn_policy.rounding_mode IS
    '적립 라운딩 — DOWN(버림, 기본) / HALF_UP(반올림) / UP(올림)';
