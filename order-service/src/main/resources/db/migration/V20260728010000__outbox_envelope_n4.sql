-- V20260728010000: 이벤트 봉투(Event Envelope) 표준 필드 — DATA-STANDARD N4
--
-- occurred_at   : 사건이 *실제로* 일어난 시각(UTC). 기존 created_at 은 '행 생성 시각'이라
--                 재처리·백필로 다시 기록되면 원래 사건 시각을 잃는다. 순서 판정·지연(lag)
--                 측정·늦게 도착한 이벤트 판별은 이 컬럼을 봐야 한다. N1 에 따라 timestamptz.
-- event_version : 봉투/페이로드 스키마 버전. 소비측 버전 분기의 근거. 신규는 1.
-- producer      : 발행 서비스 이름(spring.application.name). 다서비스가 같은 토픽에 실을 때
--                 출처 추적용.
--
-- 기존 행 백필: occurred_at 은 created_at 을 UTC 로 간주해 채운다. created_at 은
-- timestamp(무 timezone)에 애플리케이션 LocalDateTime.now() 가 그대로 들어간 값이라
-- 엄밀한 시각대를 복원할 수 없다 — 자바측 폴백(OutboxEventPersistenceAdapter#toDomain)도
-- 같은 가정(createdAt.atOffset(UTC))을 쓰므로 양측 해석이 일치한다.
-- NOT NULL 은 백필 후에 건다 — 컬럼 추가와 동시에 NOT NULL 을 걸면 기존 행에서 실패한다.

ALTER TABLE opslab.outbox_events
    ADD COLUMN IF NOT EXISTS occurred_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS event_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS producer      VARCHAR(64);

UPDATE opslab.outbox_events
SET occurred_at = created_at AT TIME ZONE 'UTC'
WHERE occurred_at IS NULL;

ALTER TABLE opslab.outbox_events
    ALTER COLUMN occurred_at SET NOT NULL;

-- 사건 시각 기준 조회(지연 측정·기간별 재처리)를 위한 인덱스.
CREATE INDEX IF NOT EXISTS idx_outbox_occurred_at ON opslab.outbox_events (occurred_at);
