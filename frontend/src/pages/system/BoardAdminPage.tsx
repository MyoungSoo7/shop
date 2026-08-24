import React, { useEffect, useMemo, useState } from 'react';
import {
  boardAdminApi, BoardDefinition, BoardSkin, BoardContentFormat,
  BOARD_SKINS, BOARD_CONTENT_FORMATS, BOARD_SKIN_LABEL,
  skinRequiresAttachments, skinRequiresComments,
} from '@/api/board';
import { menuApi, MenuNode, MenuArea, MENU_AREAS } from '@/api/system';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 게시판 관리 콘솔.
 *
 * <p>여기서 게시판을 만들면 서버에 정의 1행이 생기고, 그 즉시 `/boards/{key}` 로 접근할 수 있다.
 * 배포도 마이그레이션도 필요 없다 — 게시판은 코드가 아니라 데이터이기 때문이다.
 *
 * <p><b>메뉴 등록은 두 번째 조작이다.</b> 게시판을 만드는 순간 전사 네비게이션이 바뀌면
 * 테스트로 만든 게시판·오타 난 이름이 즉시 모두에게 노출된다. 그래서 만들고 → 확인하고 →
 * 올린다. 메뉴 행 자체는 order-service 의 기존 `POST /admin/menus` 로 만든다(board-service 는
 * 메뉴 테이블을 소유하지 않는다). 두 서비스를 잇는 대조는 조인이 아니라 이 화면이 한다.
 */

type FormState = {
  boardKey: string;
  name: string;
  description: string;
  skin: BoardSkin;
  contentFormat: BoardContentFormat;
  commentsEnabled: boolean;
  secretEnabled: boolean;
  categoryGroupCode: string;
  attachmentsEnabled: boolean;
  maxCount: number;
  maxSizeKb: number;
  allowedExtensions: string;
  readRoles: string;
  writeRoles: string;
  commentRoles: string;
  manageRoles: string;
};

const blankForm: FormState = {
  boardKey: '', name: '', description: '',
  skin: 'LIST', contentFormat: 'TEXT',
  commentsEnabled: true, secretEnabled: false, categoryGroupCode: '',
  attachmentsEnabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: 'jpg,png,pdf',
  readRoles: '', writeRoles: 'ADMIN', commentRoles: 'ADMIN,MANAGER,USER', manageRoles: 'ADMIN',
};

const csv = (value: string): string[] =>
  value.split(',').map((token) => token.trim()).filter((token) => token.length > 0);

const toForm = (board: BoardDefinition): FormState => ({
  boardKey: board.boardKey,
  name: board.name,
  description: board.description ?? '',
  skin: board.skin,
  contentFormat: board.content.contentFormat,
  commentsEnabled: board.content.commentsEnabled,
  secretEnabled: board.content.secretEnabled,
  categoryGroupCode: board.content.categoryGroupCode ?? '',
  attachmentsEnabled: board.attachment.enabled,
  maxCount: board.attachment.maxCount || 5,
  maxSizeKb: board.attachment.maxSizeKb || 5120,
  allowedExtensions: board.attachment.allowedExtensions.join(',') || 'jpg,png,pdf',
  readRoles: board.access.readRoles.join(','),
  writeRoles: board.access.writeRoles.join(','),
  commentRoles: board.access.commentRoles.join(','),
  manageRoles: board.access.manageRoles.join(','),
});

const flatten = (nodes: MenuNode[]): MenuNode[] =>
  nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);

const BoardAdminPage: React.FC = () => {
  const [boards, setBoards] = useState<BoardDefinition[]>([]);
  const [menus, setMenus] = useState<MenuNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<FormState>(blankForm);
  const [saving, setSaving] = useState(false);

  const [menuTarget, setMenuTarget] = useState<BoardDefinition | null>(null);
  const [menuArea, setMenuArea] = useState<MenuArea>('SHOP');
  const [menuParentId, setMenuParentId] = useState<number | null>(null);
  const [menuIcon, setMenuIcon] = useState('📋');
  const [menuRole, setMenuRole] = useState('');
  const [linking, setLinking] = useState(false);

  /** 메뉴 행이 가리키는 경로 집합 — 게시판과의 대조는 조인이 아니라 이 화면에서 한다. */
  const linkedPaths = useMemo(
    () => new Set(flatten(menus).map((node) => node.path).filter((path): path is string => !!path)),
    [menus],
  );

  /**
   * 메뉴에 올라가지 않은 **활성** 게시판.
   *
   * 게시판(lemuel_board)과 메뉴(opslab)는 다른 DB라 FK 로 묶을 수 없다 — 이 대조가 그 자리를
   * 대신한다(설계문서 §3). 행별 배지만 있으면 목록이 길어질 때 놓치므로 상단에 수를 모아 둔다.
   *
   * 닫힌 게시판은 세지 않는다. 메뉴에서 내리고 닫는 것이 정상 절차이므로, 그때마다 경고가 뜨면
   * 경고가 배경 소음이 된다.
   */
  const unlinkedBoards = useMemo(
    () => boards.filter((board) => board.active && !linkedPaths.has(board.path)),
    [boards, linkedPaths],
  );

  const orphanMenus = useMemo(() => {
    const boardPaths = new Set(boards.map((board) => board.path));
    return flatten(menus).filter(
      (node) => node.path?.startsWith('/boards/') && !boardPaths.has(node.path),
    );
  }, [menus, boards]);

  const load = async () => {
    setLoading(true);
    try {
      const [boardList, menuTree] = await Promise.all([
        boardAdminApi.list(),
        // 메뉴 조회가 실패해도 게시판 관리는 계속돼야 한다 — 연결 배지만 못 그릴 뿐이다.
        menuApi.getTree().catch(() => [] as MenuNode[]),
      ]);
      setBoards(boardList);
      setMenus(menuTree);
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, '게시판 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const resetForm = () => {
    setEditingId(null);
    setForm(blankForm);
  };

  const startEdit = (board: BoardDefinition) => {
    setEditingId(board.id);
    setForm(toForm(board));
    setNotice(null);
  };

  const handleSkinChange = (skin: BoardSkin) => {
    // 서버가 어차피 막지만, 저장 버튼을 누른 뒤에야 알게 되는 것보다 지금 켜 주는 편이 낫다.
    setForm((prev) => ({
      ...prev,
      skin,
      attachmentsEnabled: skinRequiresAttachments(skin) ? true : prev.attachmentsEnabled,
      commentsEnabled: skinRequiresComments(skin) ? true : prev.commentsEnabled,
    }));
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setNotice(null);
    const payload = {
      name: form.name,
      description: form.description || undefined,
      skin: form.skin,
      content: {
        contentFormat: form.contentFormat,
        commentsEnabled: form.commentsEnabled,
        secretEnabled: form.secretEnabled,
        categoryGroupCode: form.categoryGroupCode || null,
      },
      attachment: {
        enabled: form.attachmentsEnabled,
        maxCount: form.maxCount,
        maxSizeKb: form.maxSizeKb,
        allowedExtensions: csv(form.allowedExtensions),
      },
      access: {
        readRoles: csv(form.readRoles),
        writeRoles: csv(form.writeRoles),
        commentRoles: csv(form.commentRoles),
        manageRoles: csv(form.manageRoles),
      },
    };

    try {
      if (editingId === null) {
        const created = await boardAdminApi.create({ boardKey: form.boardKey, ...payload });
        setNotice(`게시판 '${created.name}' 을 만들었습니다. 아직 메뉴에는 올라가지 않았습니다.`);
      } else {
        await boardAdminApi.update(editingId, payload);
        setNotice('수정했습니다.');
      }
      resetForm();
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '저장하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (board: BoardDefinition) => {
    try {
      if (board.active) {
        await boardAdminApi.deactivate(board.id);
      } else {
        await boardAdminApi.activate(board.id);
      }
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '상태를 바꾸지 못했습니다.'));
    }
  };

  const remove = async (board: BoardDefinition) => {
    if (!window.confirm(`'${board.name}' 게시판을 삭제할까요? 되돌릴 수 없습니다.`)) return;
    try {
      await boardAdminApi.remove(board.id);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '삭제하지 못했습니다. 운영 중인 게시판은 먼저 닫아야 합니다.'));
    }
  };

  const openMenuDialog = (board: BoardDefinition) => {
    setMenuTarget(board);
    setMenuArea('SHOP');
    setMenuParentId(null);
    setMenuIcon('📋');
    setMenuRole(board.access.readRoles.join(','));
    setNotice(null);
  };

  const linkMenu = async () => {
    if (!menuTarget) return;
    setLinking(true);
    try {
      await menuApi.create({
        name: menuTarget.name,
        path: menuTarget.path,
        icon: menuIcon || undefined,
        description: menuTarget.description ?? undefined,
        area: menuArea,
        menuType: 'ITEM',
        parentId: menuParentId,
        sortOrder: 99,
        requiredRole: menuRole || undefined,
        visible: true,
      });
      setMenuTarget(null);
      setNotice('메뉴에 추가했습니다. 새로고침하면 네비게이션에 나타납니다.');
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '메뉴에 추가하지 못했습니다.'));
    } finally {
      setLinking(false);
    }
  };

  const groupCandidates = useMemo(
    () => flatten(menus).filter((node) => node.menuType === 'GROUP' && node.area === menuArea),
    [menus, menuArea],
  );

  if (loading) {
    return <div className="py-20 flex justify-center"><Spinner size="lg" message="게시판 로드 중..." /></div>;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">게시판 관리</h1>
        <p className="text-sm text-gray-500 mt-1">
          게시판 하나가 화면 하나입니다. 만들면 즉시 <code>/boards/&#123;키&#125;</code> 로 열리고,
          메뉴에 올리는 것은 별도 조작입니다.
        </p>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 flex justify-between">
          <span>{error}</span>
          <button onClick={() => setError(null)} className="text-red-400 hover:text-red-600">닫기</button>
        </div>
      )}
      {notice && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-700">
          {notice}
        </div>
      )}
      {unlinkedBoards.length > 0 && (
        <div className="rounded-lg border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-800">
          메뉴에 없는 게시판 {unlinkedBoards.length}개 —{' '}
          {unlinkedBoards.map((board) => board.name).join(', ')}. 만들어져 있지만 네비게이션에서
          찾아갈 수 없습니다. 각 행의 <b>메뉴에 추가</b>로 올리세요.
        </div>
      )}

      {orphanMenus.length > 0 && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          링크가 끊긴 메뉴 {orphanMenus.length}건 —{' '}
          {orphanMenus.map((node) => node.path).join(', ')} 에 해당하는 게시판이 없습니다.
          메뉴 관리에서 정리하세요.
        </div>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        {/* 목록 */}
        <div className="xl:col-span-3 bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-900">게시판</h3>
            <span className="text-sm text-gray-400">{boards.length}개</span>
          </div>
          <ul className="divide-y divide-gray-100">
            {boards.map((board) => {
              const linked = linkedPaths.has(board.path);
              return (
                <li key={board.id} className="px-4 py-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-bold text-gray-900">{board.name}</span>
                        <span className="font-mono text-xs text-blue-700">{board.path}</span>
                        <span className="text-xs px-2 py-0.5 rounded-full bg-indigo-100 text-indigo-800">
                          {board.skin}
                        </span>
                        <span className={`text-xs px-2 py-0.5 rounded-full ${
                          board.active ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
                        }`}>
                          {board.active ? '활성' : '닫힘'}
                        </span>
                        <span className={`text-xs px-2 py-0.5 rounded-full ${
                          linked ? 'bg-sky-100 text-sky-800' : 'bg-amber-100 text-amber-800'
                        }`}>
                          {linked ? '메뉴 연결됨' : '메뉴 없음'}
                        </span>
                        {board.access.publicRead && (
                          <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">공개</span>
                        )}
                      </div>
                      <p className="text-sm text-gray-500 mt-1 truncate">
                        {board.description || '설명 없음'}
                      </p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0 text-xs">
                      {!linked && (
                        <button onClick={() => openMenuDialog(board)}
                                className="px-2 py-1 rounded bg-sky-600 text-white hover:bg-sky-700">
                          메뉴에 추가
                        </button>
                      )}
                      <button onClick={() => startEdit(board)} className="px-2 py-1 rounded border border-gray-300 hover:bg-gray-50">
                        수정
                      </button>
                      <button onClick={() => toggleActive(board)} className="px-2 py-1 rounded border border-gray-300 hover:bg-gray-50">
                        {board.active ? '닫기' : '열기'}
                      </button>
                      <button onClick={() => remove(board)} className="px-2 py-1 rounded text-red-600 hover:bg-red-50">
                        삭제
                      </button>
                    </div>
                  </div>
                </li>
              );
            })}
            {boards.length === 0 && (
              <li className="px-4 py-10 text-center text-gray-400 text-sm">아직 게시판이 없습니다.</li>
            )}
          </ul>
        </div>

        {/* 생성·수정 폼 */}
        <div className="xl:col-span-2 bg-white rounded-xl border border-gray-200">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-900">{editingId === null ? '새 게시판' : '게시판 수정'}</h3>
            {editingId !== null && (
              <button onClick={resetForm} className="text-xs text-gray-500 hover:text-gray-700">새로 만들기</button>
            )}
          </div>
          <form onSubmit={submit} className="p-4 space-y-3 text-sm">
            <label className="block">
              <span className="text-gray-600">게시판 키 (URL)</span>
              <input
                value={form.boardKey}
                onChange={(e) => setForm({ ...form, boardKey: e.target.value })}
                disabled={editingId !== null}
                placeholder="notice"
                className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg disabled:bg-gray-100"
              />
              <span className="text-xs text-gray-400">
                소문자·숫자·하이픈 2~40자. 만든 뒤에는 바꿀 수 없습니다(링크가 죽습니다).
              </span>
            </label>

            <label className="block">
              <span className="text-gray-600">게시판명</span>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
                     className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg" />
            </label>

            <label className="block">
              <span className="text-gray-600">설명</span>
              <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}
                     className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg" />
            </label>

            <div className="grid grid-cols-2 gap-3">
              <label className="block">
                <span className="text-gray-600">스킨</span>
                <select value={form.skin} onChange={(e) => handleSkinChange(e.target.value as BoardSkin)}
                        className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg">
                  {BOARD_SKINS.map((skin) => (
                    <option key={skin} value={skin}>{BOARD_SKIN_LABEL[skin]}</option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="text-gray-600">본문 형식</span>
                <select value={form.contentFormat}
                        onChange={(e) => setForm({ ...form, contentFormat: e.target.value as BoardContentFormat })}
                        className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg">
                  {BOARD_CONTENT_FORMATS.map((format) => (
                    <option key={format} value={format}>{format}</option>
                  ))}
                </select>
              </label>
            </div>

            <div className="flex items-center gap-4">
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={form.commentsEnabled}
                       disabled={skinRequiresComments(form.skin)}
                       onChange={(e) => setForm({ ...form, commentsEnabled: e.target.checked })} />
                <span className="text-gray-600">댓글</span>
              </label>
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={form.secretEnabled}
                       onChange={(e) => setForm({ ...form, secretEnabled: e.target.checked })} />
                <span className="text-gray-600">비밀글</span>
              </label>
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={form.attachmentsEnabled}
                       disabled={skinRequiresAttachments(form.skin)}
                       onChange={(e) => setForm({ ...form, attachmentsEnabled: e.target.checked })} />
                <span className="text-gray-600">첨부</span>
              </label>
            </div>

            {editingId !== null && !form.attachmentsEnabled && (
              <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                첨부를 끄면 <b>새 업로드만</b> 막힙니다. 이미 붙은 파일은 남아 있고 글에서 그대로
                보이며 다운로드도 됩니다 — 정책은 미래를 향합니다. 기존 파일을 없애려면 글에서
                개별 삭제하세요.
              </p>
            )}

            {form.attachmentsEnabled && (
              <div className="grid grid-cols-3 gap-2">
                <label className="block">
                  <span className="text-gray-600 text-xs">최대 개수</span>
                  <input type="number" value={form.maxCount}
                         onChange={(e) => setForm({ ...form, maxCount: Number(e.target.value) })}
                         className="mt-1 w-full px-2 py-1.5 border border-gray-300 rounded-lg" />
                </label>
                <label className="block">
                  <span className="text-gray-600 text-xs">최대 KB</span>
                  <input type="number" value={form.maxSizeKb}
                         onChange={(e) => setForm({ ...form, maxSizeKb: Number(e.target.value) })}
                         className="mt-1 w-full px-2 py-1.5 border border-gray-300 rounded-lg" />
                </label>
                <label className="block">
                  <span className="text-gray-600 text-xs">확장자</span>
                  <input value={form.allowedExtensions}
                         onChange={(e) => setForm({ ...form, allowedExtensions: e.target.value })}
                         className="mt-1 w-full px-2 py-1.5 border border-gray-300 rounded-lg" />
                </label>
              </div>
            )}

            <label className="block">
              <span className="text-gray-600">분류 코드그룹 (선택)</span>
              <input value={form.categoryGroupCode}
                     onChange={(e) => setForm({ ...form, categoryGroupCode: e.target.value })}
                     placeholder="BOARD_CAT_NOTICE"
                     className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg" />
              <span className="text-xs text-gray-400">공통코드 관리에서 만든 그룹 코드를 그대로 씁니다.</span>
            </label>

            <div className="pt-2 border-t border-gray-100 space-y-2">
              <p className="text-xs text-gray-500">
                역할은 쉼표로 구분합니다. <b>읽기를 비우면 공개 게시판</b>(비로그인 포함)이 되고,
                쓰기·댓글·운영은 비울 수 없습니다.
              </p>
              {([
                ['readRoles', '읽기'], ['writeRoles', '쓰기'],
                ['commentRoles', '댓글'], ['manageRoles', '운영'],
              ] as const).map(([field, label]) => (
                <label key={field} className="flex items-center gap-2">
                  <span className="w-10 text-gray-600 text-xs">{label}</span>
                  <input value={form[field]} onChange={(e) => setForm({ ...form, [field]: e.target.value })}
                         className="flex-1 px-3 py-1.5 border border-gray-300 rounded-lg font-mono text-xs" />
                </label>
              ))}
            </div>

            <button type="submit" disabled={saving}
                    className="w-full py-2 rounded-lg bg-blue-600 text-white font-semibold hover:bg-blue-700 disabled:opacity-50">
              {saving ? '저장 중...' : editingId === null ? '게시판 만들기' : '수정 저장'}
            </button>
          </form>
        </div>
      </div>

      {/* 메뉴 연결 */}
      {menuTarget && (
        <div className="bg-white rounded-xl border-2 border-sky-200 p-4 space-y-3">
          <h3 className="font-bold text-gray-900">
            메뉴에 추가 — {menuTarget.name}
            <span className="ml-2 font-mono text-xs text-blue-700">{menuTarget.path}</span>
          </h3>
          <p className="text-xs text-gray-500">
            메뉴 행은 order-service 가 소유합니다. 이 버튼은 기존 <code>POST /admin/menus</code> 를
            호출할 뿐이라, 메뉴 관리 화면에서 만든 것과 완전히 같은 행이 생깁니다.
          </p>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-3 text-sm">
            <label className="block">
              <span className="text-gray-600 text-xs">영역</span>
              <select value={menuArea}
                      onChange={(e) => { setMenuArea(e.target.value as MenuArea); setMenuParentId(null); }}
                      className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg">
                {MENU_AREAS.map((area) => <option key={area} value={area}>{area}</option>)}
              </select>
            </label>
            <label className="block">
              <span className="text-gray-600 text-xs">상위 그룹</span>
              <select value={menuParentId ?? ''}
                      onChange={(e) => setMenuParentId(e.target.value ? Number(e.target.value) : null)}
                      className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg">
                <option value="">(최상위)</option>
                {groupCandidates.map((node) => (
                  <option key={node.id} value={node.id}>{node.name}</option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-gray-600 text-xs">아이콘</span>
              <input value={menuIcon} onChange={(e) => setMenuIcon(e.target.value)}
                     className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg" />
            </label>
            <label className="block">
              <span className="text-gray-600 text-xs">노출 역할 (비우면 전체)</span>
              <input value={menuRole} onChange={(e) => setMenuRole(e.target.value)}
                     className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-xs" />
            </label>
          </div>
          <div className="flex gap-2">
            <button onClick={linkMenu} disabled={linking}
                    className="px-4 py-2 rounded-lg bg-sky-600 text-white font-semibold hover:bg-sky-700 disabled:opacity-50">
              {linking ? '추가 중...' : '메뉴에 추가'}
            </button>
            <button onClick={() => setMenuTarget(null)}
                    className="px-4 py-2 rounded-lg border border-gray-300 hover:bg-gray-50">
              취소
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default BoardAdminPage;
