-- ============================================================
-- V20260827100000 : 수강 신청(정원 · 대기 · 취소)
--
-- dentis 의 tb_education_order_detail / tb_education_order_cancel 에서 옮겨 온다. 옮기지 않는 것이
-- 하나 있다 — <b>결제</b>다. dentis 는 신청과 결제(pay_state · payment_type · refund_cmpl_date)를
-- 한 테이블에 붙여 놨지만, 이 저장소에서 돈은 order-service 가 소유한다. 여기서 pay_state 를
-- 흉내 내면 같은 결제 상태가 두 서비스에 각자 적히고 둘이 어긋나는 순간 어느 쪽이 맞는지
-- 판정할 근거가 없어진다. 그래서 이 테이블은 "누가 이 과정에 자리를 잡았는가"만 기록한다.
--
-- 상태는 셋이다. WAITING(신청 접수) → CONFIRMED(자리 확정) → CANCELLED(취소).
-- dentis 의 '대기'/'신청완료'/'취소' 와 같은 뜻이다.
--
-- ★ 부분 유니크 인덱스인 이유: 같은 사람이 같은 과정에 살아 있는 신청을 둘 가지면 정원이
--   한 사람에게 두 자리를 내주게 된다. 반면 취소한 뒤 다시 신청하는 것은 정상 경로이므로,
--   CANCELLED 는 제약에서 빼야 한다. 전체 유니크로 걸면 재신청이 영구히 막힌다.
--
-- ★ capacity 를 courses 에 두는 이유: 정원은 신청 한 건의 성질이 아니라 과정의 성질이다.
--   NULL 은 "정원 없음"이며 0 과 다르다 — 0 은 아무도 못 받는다는 뜻이다.
-- ============================================================

ALTER TABLE education.education_courses
    ADD COLUMN capacity INTEGER;

COMMENT ON COLUMN education.education_courses.capacity IS '정원. NULL 이면 정원 없음(0 은 마감과 같다).';

CREATE TABLE education.education_enrollments (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES education.education_courses(id),
    applicant_id VARCHAR(100) NOT NULL,
    applicant_name VARCHAR(100) NOT NULL,
    applicant_organization VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    admin_memo VARCHAR(500),
    cancel_reason VARCHAR(500),
    applied_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT education_enrollments_status_ck CHECK (status IN ('WAITING', 'CONFIRMED', 'CANCELLED'))
);

-- 살아 있는 신청만 유일해야 한다 — 취소분은 재신청을 막지 않는다.
CREATE UNIQUE INDEX education_enrollments_active_uk
    ON education.education_enrollments (course_id, applicant_id)
    WHERE status <> 'CANCELLED';

-- 콘솔의 기본 조회는 "이 과정의 이 상태를 신청 순서대로" 다.
CREATE INDEX education_enrollments_course_idx
    ON education.education_enrollments (course_id, status, applied_at);
