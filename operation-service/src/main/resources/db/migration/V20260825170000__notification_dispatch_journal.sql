-- V20260825170000: 알림 발송 저널 — **내구 멱등(L2) 겸 발송 이력**
--
-- 왜 지금 생겼나:
--   이 슬라이스는 지금까지 DB 에 아무것도 쓰지 않았다. 결과는 `log.info` 한 줄뿐이라
--   "그 사람한테 알림이 갔나?" 를 조회로 답할 수 없었고, 파드가 재시작하면 흔적이 사라졌다.
--   멱등도 InMemoryTtlDedupeStore 하나라 **레플리카가 2개면 같은 알림이 2번 나간다**
--   (그 클래스 javadoc 이 스스로 그렇게 적어 뒀다).
--
-- 두 문제를 한 번의 쓰기로 푼다:
--   `notification_dispatches.event_id` 의 UNIQUE 인덱스에 INSERT … ON CONFLICT DO NOTHING 을 던지고
--   **행이 생겼는지 여부가 곧 멱등 판정**이다. 통과한 그 행이 그대로 이력이 된다.
--   settlement 의 L1(인메모리 TTL) → L2(DB UNIQUE) 계층형 멱등과 같은 모양이며, 여기서 이 파일은 L2 다.
--
-- 팽창 우려에 대한 답 (NotificationDomainEventListener javadoc 의 "고volume 이벤트마다 멱등 행을
--   쌓으면 테이블이 무한 팽창한다" 는 지적):
--   아래 prune_notification_dispatches(INTERVAL) 로 보존기간 초과분을 지운다. 시그니처·반환값은
--   기존 prune_outbox_published / prune_processed_events 와 동일 규약(INTERVAL → BIGINT)이다.
--   **자동 호출하지 않는다** — 파기는 운영 판단이라는 이 저장소 규칙(V20260715155000)을 따른다.
--
-- ※ operation 기본 스키마는 opslab — 미한정 DDL 은 opslab 에 생성된다(V1~V4 동일 관례).

-- 발송 1건 = 1행. event_id 가 있는 발송은 이 테이블의 UNIQUE 인덱스가 유일한 진입 관문이 된다.
CREATE TABLE IF NOT EXISTS notification_dispatches (
    id                 BIGSERIAL    PRIMARY KEY,
    -- 멱등 키. Outbox 는 UUID 를 주지만 비-Outbox 프로듀서(Go 웹훅)·수기 발송은 임의 문자열을 준다.
    -- UUID 타입으로 좁히면 그 경로가 통째로 못 들어오므로 VARCHAR 로 넉넉히 잡는다.
    event_id           VARCHAR(200) NOT NULL,
    type               VARCHAR(64)  NOT NULL,
    -- 이메일 주소 최대 길이(RFC 5321 의 forward-path 상한)에 맞춘다.
    recipient          VARCHAR(320) NOT NULL,
    subject            VARCHAR(500) NOT NULL,
    -- 재발송 시 원문을 그대로 다시 보내야 하므로 본문도 남긴다.
    body               TEXT,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    channels_total     INTEGER      NOT NULL DEFAULT 0,
    channels_succeeded INTEGER      NOT NULL DEFAULT 0,
    -- 재발송으로 파생된 행이면 원본 행을 가리킨다(원본이 파기되면 NULL 로 남는다).
    resent_from_id     BIGINT,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMP,

    -- PENDING     : 팬아웃 시작만 기록됨(프로세스가 죽으면 이 상태로 남는다 — 그 자체가 신호다)
    -- DELIVERED   : 활성 채널 전건 성공
    -- PARTIAL     : 일부 채널만 성공 (도달은 했다 — 실패로 취급하지 않는다)
    -- FAILED      : 활성 채널 전건 실패 = 아무에게도 닿지 않음
    -- NO_CHANNEL  : 활성 채널이 0개 — 메시지 문제가 아니라 배포 설정 오류
    CONSTRAINT chk_notification_dispatch_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'PARTIAL', 'FAILED', 'NO_CHANNEL')),
    CONSTRAINT fk_notification_dispatch_resent_from
        FOREIGN KEY (resent_from_id) REFERENCES notification_dispatches (id) ON DELETE SET NULL
);

-- ★ 이 인덱스가 내구 멱등 그 자체다. 지우면 레플리카 중복 발송이 조용히 되살아난다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_dispatch_event_id
    ON notification_dispatches (event_id);

-- 운영 콘솔 기본 조회(최신순) + 리텐션 정리 스캔 공용.
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_created
    ON notification_dispatches (created_at DESC);

-- "이 사람한테 갔나?" — 수신자 기준 조회.
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_recipient
    ON notification_dispatches (recipient, created_at DESC);

-- 실패만 골라 보기. 부분 인덱스로 두면 대부분을 차지하는 DELIVERED 행이 인덱스에 안 들어간다.
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_unhealthy
    ON notification_dispatches (created_at DESC)
    WHERE status IN ('PENDING', 'PARTIAL', 'FAILED', 'NO_CHANNEL');

-- 채널별 결과. 부모 1행에 채널 수만큼.
CREATE TABLE IF NOT EXISTS notification_dispatch_channels (
    id          BIGSERIAL   PRIMARY KEY,
    dispatch_id BIGINT      NOT NULL REFERENCES notification_dispatches (id) ON DELETE CASCADE,
    channel     VARCHAR(40) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    attempts    INTEGER     NOT NULL,
    error       TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    -- 한 발송에 같은 채널이 두 번 기록될 수 없다 — complete() 재실행을 멱등하게 만든다.
    CONSTRAINT uq_notification_dispatch_channel UNIQUE (dispatch_id, channel),
    CONSTRAINT chk_notification_dispatch_channel_status
        CHECK (status IN ('SUCCESS', 'FAILURE'))
);

-- 상세 화면의 자식 조회. UNIQUE(dispatch_id, channel) 이 선두 컬럼을 이미 커버하지만,
-- 그 제약은 이름이 바뀌거나 재정의될 수 있어 조회 경로를 제약에 의존시키지 않는다.
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_channel_parent
    ON notification_dispatch_channels (dispatch_id);

-- 보존기간(p_retention) 초과 발송 이력 삭제. 반환=삭제된 부모 행 수(자식은 ON DELETE CASCADE).
-- ⚠ 삭제 기준은 created_at 이다 — PENDING 으로 남은 행(프로세스 사망)도 결국 정리된다.
--   보존기간은 **멱등 창보다 넉넉히** 잡아야 한다. 지운 event_id 는 다시 처음 보는 것이 되어
--   그 시점 이후 재전달되면 중복 발송된다.
-- ⚠ search_path 를 함수에 고정한다. DDL 은 Flyway 가 opslab 을 잡아 준 채로 돌지만 **함수 본문은
--   호출 시점에** 이름을 푼다 — 고정하지 않으면 opslab 이 search_path 에 없는 커넥션에서
--   "relation notification_dispatches does not exist" 로 죽는다(운영 배치가 그런 커넥션이다).
--   기존 prune_audit_logs(V20260715130000) 와 같은 형식이며, search_path 하이재킹도 함께 막는다.
CREATE OR REPLACE FUNCTION prune_notification_dispatches(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention < INTERVAL '0' THEN
        RAISE EXCEPTION 'p_retention must be >= 0 (got %)', p_retention;
    END IF;
    DELETE FROM notification_dispatches
    WHERE created_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;
