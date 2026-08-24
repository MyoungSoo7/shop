import React, { useEffect, useMemo, useState } from 'react';
import { menuApi, rbacApi, MenuNode, MenuCreateRequest, Role, MENU_AREAS, MENU_TYPES } from '@/api/system';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';
import { useMenus } from '@/contexts/useMenus';

const emptyForm: MenuCreateRequest & { active: boolean } = {
  name: '', shortName: '', path: '', icon: '', description: '',
  area: 'BACKOFFICE', menuType: 'ITEM', parentId: null, sortOrder: 0,
  requiredRole: '', requiredPermission: '', visible: true, active: true,
};

const ADMIN_ONLY_PATHS = new Set([
  '/admin/settlement/payouts',
  '/admin/settlement/chargebacks',
  '/admin/settlement/monthly-closing',
  '/admin/settlement/commission-rates',
  '/admin/settlement/dlq',
]);

const isAdminOnlyMenu = (form: Pick<MenuCreateRequest, 'area' | 'path'>) =>
  form.area === 'SYSTEM'
  || form.path?.trim().startsWith('/admin/system/') === true
  || ADMIN_ONLY_PATHS.has(form.path?.trim() ?? '');

const MenuManagementPage: React.FC = () => {
  const [tree, setTree] = useState<MenuNode[]>([]);
  const [flat, setFlat] = useState<MenuNode[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState<MenuNode | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [moving, setMoving] = useState(false);
  const { refresh: refreshShellMenus } = useMenus();

  const load = async () => {
    try {
      const [t, f] = await Promise.all([menuApi.getTree(), menuApi.getFlat()]);
      setTree(t);
      setFlat(f);
    } catch {
      setError('메뉴를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // 역할 목록은 RBAC 관리와 동기화 — 실패해도 메뉴 관리는 동작
    rbacApi.getRoles().then(setRoles).catch(() => setRoles([]));
  }, []);

  const resetForm = () => { setEditing(null); setForm(emptyForm); };

  const startEdit = (m: MenuNode) => {
    setEditing(m);
    setForm({
      name: m.name, shortName: m.shortName ?? '', path: m.path ?? '', icon: m.icon ?? '',
      description: m.description ?? '', area: m.area ?? 'BACKOFFICE', menuType: m.menuType ?? 'ITEM',
      parentId: m.parentId, sortOrder: m.sortOrder, requiredRole: m.requiredRole ?? '',
      requiredPermission: m.requiredPermission ?? '', visible: m.visible, active: m.active,
    });
  };

  /** 수정 중인 메뉴의 자기 자신 + 모든 자손 ID — 부모로 선택하면 순환 참조라 제외 */
  const excludedParentIds = useMemo(() => {
    if (!editing) return new Set<number>();
    const childrenByParent = new Map<number, number[]>();
    flat.forEach((m) => {
      if (m.parentId != null) {
        if (!childrenByParent.has(m.parentId)) childrenByParent.set(m.parentId, []);
        childrenByParent.get(m.parentId)!.push(m.id);
      }
    });
    const excluded = new Set<number>([editing.id]);
    const queue = [editing.id];
    while (queue.length) {
      const cur = queue.shift()!;
      (childrenByParent.get(cur) ?? []).forEach((childId) => {
        if (!excluded.has(childId)) {
          excluded.add(childId);
          queue.push(childId);
        }
      });
    }
    return excluded;
  }, [editing, flat]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    const adminOnly = isAdminOnlyMenu(form);
    const body: MenuCreateRequest = {
      name: form.name.trim(),
      shortName: form.shortName?.trim() || undefined,
      path: form.path?.trim() || undefined,
      icon: form.icon?.trim() || undefined,
      description: form.description?.trim() || undefined,
      area: form.area,
      menuType: form.menuType,
      parentId: form.parentId ? Number(form.parentId) : null,
      sortOrder: Number(form.sortOrder) || 0,
      requiredRole: adminOnly ? 'ADMIN' : form.requiredRole?.trim() || undefined,
      requiredPermission: form.requiredPermission?.trim() || undefined,
      visible: form.visible,
    };
    try {
      if (editing) {
        await menuApi.update(editing.id, { ...body, active: form.active });
      } else {
        await menuApi.create(body);
      }
      resetForm();
      await load();
      // 상단 네비·사이드바가 이 트리로 그려지므로 저장 즉시 셸을 갱신한다.
      await refreshShellMenus();
    } catch (err) {
      alert(apiErrorMessage(err, '저장 실패'));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (m: MenuNode) => {
    if (!window.confirm(`메뉴 "${m.name}" 을 삭제하시겠습니까?`)) return;
    try {
      await menuApi.remove(m.id);
      if (editing?.id === m.id) resetForm();
      await load();
      await refreshShellMenus();
    } catch (err) {
      alert(apiErrorMessage(err, '하위 메뉴가 있어 삭제할 수 없습니다.'));
    }
  };

  /** 형제 목록 안에서 위/아래 이동 — 형제 전체의 sortOrder 를 0..n 으로 재부여해 배치 저장 */
  const handleMove = async (m: MenuNode, siblings: MenuNode[], direction: -1 | 1) => {
    const index = siblings.findIndex((s) => s.id === m.id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= siblings.length) return;
    const next = [...siblings];
    [next[index], next[target]] = [next[target], next[index]];
    setMoving(true);
    try {
      await menuApi.reorder(next.map((s, idx) => ({
        id: s.id, parentId: m.parentId, sortOrder: idx,
      })));
      await load();
      await refreshShellMenus();
    } catch (err) {
      alert(apiErrorMessage(err, '순서 변경 실패'));
    } finally {
      setMoving(false);
    }
  };

  const renderNode = (m: MenuNode, depth: number, siblings: MenuNode[]): React.ReactNode => {
    const index = siblings.findIndex((s) => s.id === m.id);
    return (
      <React.Fragment key={m.id}>
        <tr className={`hover:bg-gray-50 ${editing?.id === m.id ? 'bg-blue-50' : ''}`}>
          <td className="px-4 py-2.5">
            <span style={{ paddingLeft: depth * 20 }} className="flex items-center gap-1.5">
              {depth > 0 && <span className="text-gray-300">└</span>}
              <span>{m.icon || '•'}</span>
              <span className="font-medium text-gray-800">{m.name}</span>
            </span>
          </td>
          <td className="px-4 py-2.5 font-mono text-xs text-gray-500">{m.path || '-'}</td>
          <td className="px-4 py-2.5">
            <span className="inline-flex items-center gap-1">
              <button
                onClick={() => handleMove(m, siblings, -1)}
                disabled={moving || index <= 0}
                title="위로"
                className="w-6 h-6 text-xs rounded border border-gray-200 text-gray-500 hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
              >
                ▲
              </button>
              <button
                onClick={() => handleMove(m, siblings, 1)}
                disabled={moving || index < 0 || index >= siblings.length - 1}
                title="아래로"
                className="w-6 h-6 text-xs rounded border border-gray-200 text-gray-500 hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
              >
                ▼
              </button>
              <span className="text-gray-400 text-xs ml-1">{m.sortOrder}</span>
            </span>
          </td>
          <td className="px-4 py-2.5">
            {m.requiredRole
              ? <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">{m.requiredRole}</span>
              : <span className="text-gray-300 text-xs">-</span>}
          </td>
          <td className="px-4 py-2.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${m.visible ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-500'}`}>
              {m.visible ? '노출' : '숨김'}
            </span>
          </td>
          <td className="px-4 py-2.5 space-x-2">
            <button onClick={() => startEdit(m)} className="text-xs text-blue-600 hover:text-blue-800">수정</button>
            <button onClick={() => handleDelete(m)} className="text-xs text-red-500 hover:text-red-700">삭제</button>
          </td>
        </tr>
        {m.children?.map((c) => renderNode(c, depth + 1, m.children))}
      </React.Fragment>
    );
  };

  if (loading) return <div className="py-20 flex justify-center"><Spinner size="lg" message="메뉴 로드 중..." /></div>;
  if (error)   return <p className="text-red-600 py-10 text-center">{error}</p>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">메뉴 관리</h1>
        <p className="text-sm text-gray-500 mt-1">
          네비게이션 메뉴 트리를 관리합니다. ▲▼ 로 형제 간 순서를 바꾸고, 부모 지정으로 트리를 재구성합니다.
          자기 자신·하위 메뉴는 부모로 선택할 수 없습니다(순환 참조 방지).
        </p>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* 트리 */}
        <div className="xl:col-span-2 bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-900">메뉴 트리</h3>
            <button onClick={resetForm} className="text-xs font-semibold text-blue-600 hover:text-blue-800">+ 새 메뉴</button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['이름', '경로', '정렬', '권한', '노출', ''].map((h) => (
                    <th key={h} className="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {tree.map((m) => renderNode(m, 0, tree))}
                {tree.length === 0 && <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">메뉴가 없습니다.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>

        {/* 폼 */}
        <div className="bg-white rounded-xl border border-gray-200 p-5 h-fit xl:sticky xl:top-8">
          <h3 className="font-bold text-gray-900 mb-4">{editing ? `메뉴 수정 #${editing.id}` : '새 메뉴 추가'}</h3>
          <form onSubmit={handleSubmit} className="space-y-3">
            {/* 구조(경로·영역·부모)는 마이그레이션이 정본이다. 여기서 바꾸면 코드의 라우트와
                어긋날 수 있고, CI 정합 게이트는 시드 SQL 만 검사하므로 잡아 주지 못한다. */}
            <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
              경로 · 영역 · 부모는 <b>마이그레이션으로 바꾸는 것</b>이 원칙입니다. 여기서 바꾼 구조는
              코드의 라우트와 어긋나도 CI 가 잡지 못합니다. 이름 · 순서 · 노출 · 아이콘은 자유롭게 조정하세요.
            </p>
            <Field label="이름 *">
              <input required value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                className="input" />
            </Field>
            <Field label="짧은 이름 (상단 네비용)">
              <input value={form.shortName}
                onChange={(e) => setForm((f) => ({ ...f, shortName: e.target.value }))}
                placeholder="비우면 이름을 그대로 사용" className="input" />
            </Field>
            <Field label="경로 (path)">
              <input value={form.path}
                onChange={(e) => setForm((f) => ({ ...f, path: e.target.value }))}
                placeholder="/admin/system/..." className="input font-mono" />
            </Field>
            <Field label="부제 (사이드바 한 줄 설명)">
              <input value={form.description}
                onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                placeholder="정산 생성 · 확정" className="input" />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="영역 *">
                <select required value={form.area}
                  onChange={(e) => setForm((f) => ({ ...f, area: e.target.value as typeof f.area }))}
                  className="input">
                  {MENU_AREAS.map((a) => <option key={a} value={a}>{a}</option>)}
                </select>
              </Field>
              <Field label="종류">
                <select value={form.menuType}
                  onChange={(e) => setForm((f) => ({ ...f, menuType: e.target.value as typeof f.menuType }))}
                  className="input">
                  {MENU_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </Field>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field label="아이콘(이모지)">
                <input value={form.icon}
                  onChange={(e) => setForm((f) => ({ ...f, icon: e.target.value }))}
                  placeholder="🗂️" className="input" />
              </Field>
              <Field label="정렬순서">
                <input type="number" value={form.sortOrder}
                  onChange={(e) => setForm((f) => ({ ...f, sortOrder: Number(e.target.value) }))}
                  className="input" />
              </Field>
            </div>
            <Field label="부모 메뉴">
              <select value={form.parentId ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, parentId: e.target.value ? Number(e.target.value) : null }))}
                className="input">
                <option value="">(최상위)</option>
                {flat.filter((m) => !excludedParentIds.has(m.id)).map((m) => (
                  <option key={m.id} value={m.id}>{m.name}</option>
                ))}
              </select>
            </Field>
            {/* 역할은 단일 값이 아니라 allowlist 다(예: 'ADMIN,MANAGER'). 셀렉트 하나로 두면
                기존 다중 값이 화면에서 사라졌다가 저장 때 통째로 날아간다. */}
            <Field label="접근 허용 역할 (비우면 제한 없음)">
              {isAdminOnlyMenu(form) && (
                <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 mb-2">
                  시스템·민감 운영 메뉴는 ADMIN 전용입니다. 백엔드에서도 MANAGER 노출을 거부합니다.
                </p>
              )}
              <div className="flex flex-wrap gap-3 pt-1">
                {(roles.length ? roles.map((r) => r.code) : ['ADMIN', 'MANAGER', 'USER']).map((code) => {
                  const selected = isAdminOnlyMenu(form)
                    ? ['ADMIN']
                    : (form.requiredRole ?? '').split(',').map((s) => s.trim()).filter(Boolean);
                  const checked = selected.includes(code);
                  return (
                    <label key={code} className="flex items-center gap-1.5 text-sm text-gray-700">
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={isAdminOnlyMenu(form) && code !== 'ADMIN'}
                        onChange={(e) => setForm((f) => {
                          const current = (f.requiredRole ?? '').split(',').map((s) => s.trim()).filter(Boolean);
                          const next = e.target.checked
                            ? [...current, code]
                            : current.filter((c) => c !== code);
                          return { ...f, requiredRole: next.join(',') };
                        })}
                      />
                      {code}
                    </label>
                  );
                })}
              </div>
            </Field>
            <Field label="필요 권한 코드 (permission)">
              <input value={form.requiredPermission}
                onChange={(e) => setForm((f) => ({ ...f, requiredPermission: e.target.value }))}
                placeholder="SYSTEM_MENU_MANAGE" className="input font-mono" />
            </Field>
            <div className="flex items-center gap-4 pt-1">
              <label className="flex items-center gap-2 text-sm text-gray-700">
                <input type="checkbox" checked={form.visible}
                  onChange={(e) => setForm((f) => ({ ...f, visible: e.target.checked }))} />
                노출
              </label>
              {editing && (
                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input type="checkbox" checked={form.active}
                    onChange={(e) => setForm((f) => ({ ...f, active: e.target.checked }))} />
                  활성
                </label>
              )}
            </div>
            <div className="flex gap-2 pt-2">
              <button type="submit" disabled={saving}
                className="flex-1 py-2.5 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
                {saving ? '저장 중...' : editing ? '수정 저장' : '메뉴 추가'}
              </button>
              {editing && (
                <button type="button" onClick={resetForm}
                  className="px-4 py-2.5 bg-gray-100 text-gray-700 text-sm font-semibold rounded-lg hover:bg-gray-200">
                  취소
                </button>
              )}
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};

const Field: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div>
    <label className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
    {children}
  </div>
);

export default MenuManagementPage;
