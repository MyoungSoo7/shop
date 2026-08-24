-- V20260813220000: 표준 옵션 축·값 시드
--
-- 축이 비어 있으면 첫 SKU 를 만드는 사람이 표시명에서 축을 만들어내고, 그때부터 판매자마다
-- 제각각인 축이 생긴다("색상"/"컬러"/"color"). 흔한 축을 미리 깔아 두면 그 갈라짐을 줄인다.
--
-- ★ 코드는 반드시 표시 이름과 같게 둔다.
--   백필과 SKU 생성 경로는 OptionCode.fromDisplayName() 으로 코드를 만든다 — 한글 표시명 "색상" 은
--   코드도 "색상" 이 된다. 여기서 코드를 'COLOR' 로 두면, "색상:빨강" 라벨이 들어오는 순간
--   코드 "색상" 인 축이 새로 생겨 같은 개념이 두 축으로 쪼개진다. 시드의 목적이 정반대로 뒤집힌다.
--
-- input_type 은 SELECT 로 둔다. SWATCH 로 두면 그 축의 모든 값이 표시색을 가져야 하는데,
-- 백필·생성이 만드는 새 색상 값에는 표시색이 없어 축의 기대를 어기게 된다.
-- 색상 값에는 표시색을 채워 두므로, 나중에 축을 SWATCH 로 올릴 때 그대로 쓸 수 있다.
--
-- 멱등: 이미 있으면 건너뛴다(축은 code UNIQUE, 값은 (axis_id, code) UNIQUE).

INSERT INTO opslab.option_axes (code, name, input_type, is_active)
VALUES ('색상',     '색상',     'SELECT', TRUE),
       ('사이즈',   '사이즈',   'SELECT', TRUE),
       ('저장용량', '저장용량', 'SELECT', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO opslab.option_axis_values (axis_id, code, name, swatch_hex, sort_order, is_active)
SELECT a.id, v.code, v.code, v.swatch_hex, v.sort_order, TRUE
FROM opslab.option_axes a
         JOIN (VALUES
                   ('색상', '블랙',           '#000000', 0),
                   ('색상', '화이트',         '#FFFFFF', 1),
                   ('색상', '실버',           '#C0C0C0', 2),
                   ('색상', '스페이스그레이', '#4A4A4A', 3),
                   ('색상', '골드',           '#D4AF37', 4),
                   ('색상', '네이비',         '#1F2A44', 5),
                   ('색상', '빨강',           '#E03131', 6),
                   ('색상', '파랑',           '#1C7ED6', 7),
                   ('사이즈', 'XS',  NULL, 0),
                   ('사이즈', 'S',   NULL, 1),
                   ('사이즈', 'M',   NULL, 2),
                   ('사이즈', 'L',   NULL, 3),
                   ('사이즈', 'XL',  NULL, 4),
                   ('사이즈', '2XL', NULL, 5),
                   ('사이즈', '3XL', NULL, 6),
                   ('저장용량', '128GB', NULL, 0),
                   ('저장용량', '256GB', NULL, 1),
                   ('저장용량', '512GB', NULL, 2),
                   ('저장용량', '1TB',   NULL, 3),
                   ('저장용량', '2TB',   NULL, 4)
    ) AS v(axis_code, code, swatch_hex, sort_order) ON v.axis_code = a.code
ON CONFLICT (axis_id, code) DO NOTHING;

COMMENT ON TABLE opslab.option_axes IS
    '표준 옵션 축 카탈로그(색상·사이즈 등). 상품 간 재사용되며 파셋 검색의 축이 된다. '
    '코드는 표시 이름과 같게 유지한다 — OptionCode 규칙이 표시명에서 코드를 만들기 때문이다.';
