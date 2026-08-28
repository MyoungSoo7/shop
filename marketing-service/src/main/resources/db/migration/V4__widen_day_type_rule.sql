-- V4: attendance_campaigns.day_type_rule 폭 교정 (VARCHAR(8) → VARCHAR(16))
--
-- V1 은 이 컬럼을 레거시의 2글자 코드(ED/WD/WE) 기준으로 8자로 잡았는데, 실제로 저장되는 값은
-- DayTypeRule 열거형의 이름이다. 그중 EVERY_DAY 는 9자라 들어가지 않는다 —
-- "value too long for type character varying(8)".
--
-- 이 컬럼의 세 값 중 EVERY_DAY 는 기본값이자 가장 흔한 설정이므로, 사실상 <b>출석 캠페인을
-- 하나도 만들 수 없는</b> 상태였다. CHECK 제약은 EVERY_DAY 를 허용하는데 컬럼 폭이 그보다
-- 좁아서, 제약을 읽어서는 알 수 없고 실제로 INSERT 를 해 봐야만 드러난다.
--
-- V1 을 직접 고치지 않는 이유는 이미 V1 을 적용한 로컬 DB 가 있으면 체크섬이 어긋나기 때문이다.
-- 신규 설치는 V1 → V4 순으로 적용되어 결과가 같다.

ALTER TABLE attendance_campaigns
    ALTER COLUMN day_type_rule TYPE VARCHAR(16);
