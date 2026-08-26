-- ============================================================
-- V20260827120000 : 강사 명부와 과정 배정
--
-- dentis 의 tb_education_lecturer / tb_education_lecturer_field / tb_education_assign_lecturer
-- 셋을 옮긴다. 옮기면서 <b>공통코드 조회를 걷어낸다</b>.
--
-- dentis 는 경력·강사구분·전공을 전부 tb_common_code_detail 의 코드값으로 저장하고, 목록 쿼리마다
-- CONCAT(m_code.code, s_code.sub_code) 로 조인해 이름을 되찾아 왔다(mg_education_SQL.xml lecList).
-- 이 저장소에서 공통코드는 order-service 가 소유한다 — operation-service 가 그 테이블을 조인하면
-- 서비스 경계를 관통하는 조인이 되고, 조인이 불가능하니 코드값만 저장하면 화면에 'E03002' 가
-- 그대로 뜬다. 그래서 여기서는 <b>표시되는 문자열을 그대로</b> 저장한다. 코드 체계가 필요해지면
-- 그때 order-service 의 공통코드를 API 로 읽어 채우는 편이, 읽을 수 없는 코드를 미리 심는 것보다 낫다.
--
-- ★ 소프트 삭제인 이유: 강사 행이 사라지면 그 강사가 맡았던 과정의 배정 이력이 함께 끊긴다.
--   dentis 도 같은 이유로 delete_yn 을 썼다(lecUpdate mode='delete'). 지운 강사는 목록에서
--   빠지되 지난 배정 기록은 남는다.
--
-- ★ active(=dentis use_yn)와 deleted 는 다른 축이다. active=false 는 "지금은 강의를 맡기지
--   않는다"이고 deleted=true 는 "명부에서 뺐다"이다. 하나로 합치면 잠시 쉬는 강사를 되살릴 때
--   삭제 취소와 구분이 안 된다.
-- ============================================================

CREATE TABLE education.education_lecturers (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    english_name VARCHAR(100),
    graduate_school VARCHAR(200),
    office_name VARCHAR(200),
    career VARCHAR(200),
    lecturer_type VARCHAR(100),
    history_ko TEXT,
    history_en TEXT,
    etc_memo VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

COMMENT ON COLUMN education.education_lecturers.career IS 'dentis career_code 의 표시 문자열(코드값 아님).';
COMMENT ON COLUMN education.education_lecturers.lecturer_type IS 'dentis lecturer_type_code 의 표시 문자열(코드값 아님).';
COMMENT ON COLUMN education.education_lecturers.active IS '지금 강의를 맡기는가. deleted 와 다른 축이다.';

-- 목록의 기본 조회는 "지운 사람 빼고 이름으로 찾기" 다.
CREATE INDEX education_lecturers_active_idx
    ON education.education_lecturers (deleted, active, name);

-- ------------------------------------------------------------
-- 분야 — dentis tb_education_lecturer_field 의 field(전공) / lecture_field(강의 분야)
--
-- dentis 는 두 값을 한 행의 두 컬럼에 넣어 두 축의 개수가 다르면 행이 비뚤어졌다(전공 3개 ·
-- 강의분야 1개면 lecture_field 가 두 번 NULL 이다). 여기서는 축을 kind 로 세워 한 축씩 센다.
-- ------------------------------------------------------------
CREATE TABLE education.education_lecturer_fields (
    lecturer_id UUID NOT NULL REFERENCES education.education_lecturers(id) ON DELETE CASCADE,
    field_kind VARCHAR(20) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (lecturer_id, field_kind, field_name),
    CONSTRAINT education_lecturer_fields_kind_ck CHECK (field_kind IN ('MAJOR', 'LECTURE'))
);

-- ------------------------------------------------------------
-- 배정 — dentis tb_education_assign_lecturer
--
-- ★ (course_id, lecturer_id) 유니크: 같은 강사를 같은 과정에 두 번 배정하면 목록에 이름이 두 번
--   뜨고, 해제할 때 한 행만 지워져 "해제했는데 여전히 보인다"가 된다. dentis 는 seq 를
--   max(seq)+1 로 발급할 뿐 중복을 막지 않아 실제로 그럴 수 있다.
-- ------------------------------------------------------------
CREATE TABLE education.education_lecturer_assignments (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES education.education_courses(id),
    lecturer_id UUID NOT NULL REFERENCES education.education_lecturers(id),
    assigned_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    CONSTRAINT education_lecturer_assignments_uk UNIQUE (course_id, lecturer_id)
);

CREATE INDEX education_lecturer_assignments_lecturer_idx
    ON education.education_lecturer_assignments (lecturer_id, assigned_at);
