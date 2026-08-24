-- V4: operation-service 신호 시계열 버킷 (Phase 2)
--
-- 운영 신호(비즈니스 이벤트·인프라 메트릭)를 5분 단위 버킷에 집계 적재한다.
-- Phase 3 이상 탐지가 이 버킷의 요일·시간대 베이스라인과 z-score 로 "평소 대비 N% 증가"를 판정한다.
-- 수치 계산은 전부 SQL/Java(결정론적), AI 는 계산된 결과의 설명만 담당한다.
--
-- 두 종류의 신호를 한 테이블로 통합:
--   · 카운터형 (Kafka 도메인 이벤트)  → count_total(분모=시도), count_signal(분자=실패)
--       예) payment: captured 는 count_total+1, (Phase 2b) payment.failed 는 count_total+1 & count_signal+1
--           failure_rate = count_signal / count_total (Phase 3 에서 계산)
--   · 게이지형 (Prometheus 폴링)       → value_sum/value_max/sample_count (평균·피크 산출)
--       예) kafka.lag.max, redis.up, db.deadlock.rate, http.error.ratio
-- 설계: docs/design/operation-service-phase1.md (Phase 2 채널 A/B)

CREATE TABLE ops_metric_bucket (
    metric_key    VARCHAR(100)     NOT NULL,   -- 'payment' / 'order' / 'settlement' / 'kafka.lag.max' ...
    bucket_start  TIMESTAMPTZ      NOT NULL,   -- 5분 버킷 시작 시각 (UTC, 300초 정렬)
    count_total   BIGINT           NOT NULL DEFAULT 0,  -- 카운터: 시도(분모)
    count_signal  BIGINT           NOT NULL DEFAULT 0,  -- 카운터: 관심 신호(분자=실패)
    value_sum     DOUBLE PRECISION NOT NULL DEFAULT 0,  -- 게이지: 합 (평균 산출용)
    value_max     DOUBLE PRECISION,                     -- 게이지: 피크
    sample_count  BIGINT           NOT NULL DEFAULT 0,  -- 게이지: 표본 수 (평균 분모)
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

    PRIMARY KEY (metric_key, bucket_start)
);

-- 최근 버킷 스캔 (베이스라인/최근값 조회) — metric_key 별 시간 역순
CREATE INDEX idx_metric_bucket_recent
    ON ops_metric_bucket (metric_key, bucket_start DESC);

-- 오래된 버킷 정리(리텐션) 스캔용 — 시간 축 단독
CREATE INDEX idx_metric_bucket_time
    ON ops_metric_bucket (bucket_start);
