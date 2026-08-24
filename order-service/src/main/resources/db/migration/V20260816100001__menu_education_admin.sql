INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type, sort_order, required_role, visible, active)
SELECT p.id, '교육 관리', '/admin/education/courses', '🎓', '교육 과정 · 강의 콘텐츠', 'SYSTEM', 'ITEM', 10, 'ADMIN', TRUE, TRUE
FROM menus p
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM menus m WHERE m.path = '/admin/education/courses');
