import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import MenuManagementPage from '@/pages/system/MenuManagementPage';
import { menuApi, rbacApi, MenuNode, Role } from '@/api/system';

/**
 * 이 화면은 전 사용자의 네비게이션을 쓰는 유일한 운영 경로다(생성·수정·삭제·순서변경 4갈래).
 * 잘못 저장하면 사이드바가 통째로 어긋나므로, 서버로 무엇이 나가는지를 인자 단위로 고정한다.
 *
 * MENU_AREAS·MENU_TYPES 는 실물을 그대로 쓴다 — 셀렉트 후보를 테스트가 다시 적으면
 * 상수가 바뀌어도 초록이라 드리프트를 못 잡는다.
 */
vi.mock('@/api/system', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/system')>();
  return {
    ...actual,
    menuApi: {
      getTree: vi.fn(), getFlat: vi.fn(), create: vi.fn(),
      update: vi.fn(), reorder: vi.fn(), remove: vi.fn(),
    },
    rbacApi: { ...actual.rbacApi, getRoles: vi.fn() },
  };
});

const refreshShellMenus = vi.fn();
vi.mock('@/contexts/useMenus', () => ({
  useMenus: () => ({ menus: [], loading: false, degraded: false, refresh: refreshShellMenus }),
}));

const mockedMenu = vi.mocked(menuApi);
const mockedRbac = vi.mocked(rbacApi);

const node = (over: Partial<MenuNode> & Pick<MenuNode, 'id' | 'name'>): MenuNode => ({
  parentId: null, shortName: null, path: null, icon: null, description: null,
  area: 'BACKOFFICE', menuType: 'ITEM', sortOrder: 0, requiredRole: null,
  requiredPermission: null, visible: true, active: true,
  createdAt: '2026-08-01T00:00:00', updatedAt: '2026-08-01T00:00:00', children: [],
  ...over,
});

/** 정산(1) > [정산관리(11), 지급관리(12)] · 시스템(2) > [메뉴 관리(21)] */
const 정산관리 = node({ id: 11, name: '정산관리', parentId: 1, path: '/admin/settlement', sortOrder: 0, requiredRole: 'ADMIN,MANAGER' });
const 지급관리 = node({ id: 12, name: '지급관리', parentId: 1, path: '/admin/settlement/payouts', sortOrder: 1, requiredRole: 'ADMIN' });
const 메뉴관리 = node({ id: 21, name: '메뉴 관리', parentId: 2, path: '/admin/system/menus', sortOrder: 0 });
const 정산 = node({ id: 1, name: '정산', menuType: 'GROUP', sortOrder: 0, children: [정산관리, 지급관리] });
const 시스템 = node({ id: 2, name: '시스템', menuType: 'GROUP', area: 'SYSTEM', sortOrder: 1, children: [메뉴관리] });

const tree = () => [정산, 시스템];
const flat = () => [정산, 정산관리, 지급관리, 시스템, 메뉴관리];

const roles: Role[] = [
  { id: 1, code: 'ADMIN', name: '관리자', builtin: true, createdAt: '2026-01-01T00:00:00', permissionIds: [], permissionCodes: [] },
  { id: 2, code: 'MANAGER', name: '매니저', builtin: true, createdAt: '2026-01-01T00:00:00', permissionIds: [], permissionCodes: [] },
  { id: 3, code: 'USER', name: '사용자', builtin: true, createdAt: '2026-01-01T00:00:00', permissionIds: [], permissionCodes: [] },
];

/** Field 가 label 을 input 에 묶지 않아(htmlFor 없음) 역할·순서로 집는다. */
const nameInput = () => screen.getAllByRole('textbox')[0];
const pathInput = () => screen.getByPlaceholderText('/admin/system/...');
const parentSelect = () => screen.getByRole('option', { name: '(최상위)' }).closest('select')!;
const submit = () => screen.getByRole('button', { name: /메뉴 추가|수정 저장/ });
const editButtons = () => screen.getAllByRole('button', { name: '수정' });
/** 메뉴 이름은 트리 표와 부모 셀렉트 양쪽에 나온다 — 트리에 남았는지는 표 안에서만 본다. */
const inTree = (name: string) => within(screen.getByRole('table')).getByText(name);
const deleteButtons = () => screen.getAllByRole('button', { name: '삭제' });

const renderPage = async () => {
  render(<MenuManagementPage />);
  await waitFor(() => expect(screen.getByText('메뉴 트리')).toBeInTheDocument());
};

let confirmSpy: ReturnType<typeof vi.spyOn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedMenu.getTree.mockResolvedValue(tree());
  mockedMenu.getFlat.mockResolvedValue(flat());
  mockedMenu.create.mockResolvedValue(정산관리);
  mockedMenu.update.mockResolvedValue(정산관리);
  mockedMenu.reorder.mockResolvedValue(flat());
  mockedMenu.remove.mockResolvedValue(undefined);
  mockedRbac.getRoles.mockResolvedValue(roles);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

describe('MenuManagementPage — 조회', () => {
  it('트리와 평면 목록을 함께 불러 화면을 세운다 (부모 후보는 평면 목록에서 온다)', async () => {
    await renderPage();

    expect(mockedMenu.getTree).toHaveBeenCalledTimes(1);
    expect(mockedMenu.getFlat).toHaveBeenCalledTimes(1);
    expect(inTree('정산')).toBeInTheDocument();
    expect(inTree('지급관리')).toBeInTheDocument();
  });

  it('메뉴를 불러오지 못하면 편집 폼을 열지 않는다 — 빈 트리 위에 저장하면 구조가 어긋난다', async () => {
    mockedMenu.getTree.mockRejectedValue(new Error('boom'));
    render(<MenuManagementPage />);

    await waitFor(() => expect(screen.getByText('메뉴를 불러오지 못했습니다.')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '메뉴 추가' })).not.toBeInTheDocument();
  });

  it('역할 목록 조회가 실패해도 메뉴 관리는 계속 동작한다 (역할은 기본값으로 떨어진다)', async () => {
    mockedRbac.getRoles.mockRejectedValue(new Error('rbac down'));
    await renderPage();

    await waitFor(() => expect(screen.getByLabelText('ADMIN')).toBeInTheDocument());
    expect(screen.getByLabelText('MANAGER')).toBeInTheDocument();
    expect(screen.getByLabelText('USER')).toBeInTheDocument();
  });

  it('구조 편집이 CI 게이트 밖이라는 경고를 화면에 남긴다', async () => {
    await renderPage();

    expect(screen.getByText(/CI 가 잡지 못합니다/)).toBeInTheDocument();
  });
});

describe('MenuManagementPage — 순환 참조 방지', () => {
  it('수정 중인 메뉴의 자기 자신과 모든 자손은 부모 후보에서 빠진다', async () => {
    await renderPage();
    fireEvent.click(editButtons()[0]); // 정산(1) — 자손 11·12

    const options = [...parentSelect().querySelectorAll('option')].map((o) => o.textContent);
    expect(options).not.toContain('정산');
    expect(options).not.toContain('정산관리');
    expect(options).not.toContain('지급관리');
    expect(options).toEqual(expect.arrayContaining(['(최상위)', '시스템', '메뉴 관리']));
  });

  it('새 메뉴 추가 때는 모든 메뉴가 부모 후보다', async () => {
    await renderPage();

    const options = [...parentSelect().querySelectorAll('option')].map((o) => o.textContent);
    expect(options).toEqual(['(최상위)', '정산', '정산관리', '지급관리', '시스템', '메뉴 관리']);
  });
});

describe('MenuManagementPage — 저장', () => {
  it('새 메뉴는 create 로 가고, 비어 있는 선택 항목은 아예 보내지 않는다', async () => {
    await renderPage();
    fireEvent.change(nameInput(), { target: { value: '  세무  ' } });
    fireEvent.change(pathInput(), { target: { value: '/admin/settlement/tax' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedMenu.create).toHaveBeenCalledTimes(1));
    expect(mockedMenu.create).toHaveBeenCalledWith(expect.objectContaining({
      name: '세무',                    // 앞뒤 공백은 잘라서 보낸다
      path: '/admin/settlement/tax',
      icon: undefined,                 // 빈 문자열을 그대로 보내면 아이콘이 ''로 덮인다
      description: undefined,
      requiredRole: undefined,
      parentId: null,
      visible: true,
    }));
    expect(mockedMenu.update).not.toHaveBeenCalled();
  });

  it('수정 저장은 update 로 가고 활성 플래그를 함께 싣는다', async () => {
    await renderPage();
    fireEvent.click(editButtons()[1]); // 정산관리(11)
    fireEvent.change(nameInput(), { target: { value: '정산관리(개편)' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedMenu.update).toHaveBeenCalledTimes(1));
    expect(mockedMenu.update).toHaveBeenCalledWith(11, expect.objectContaining({
      name: '정산관리(개편)', path: '/admin/settlement', active: true,
    }));
    expect(mockedMenu.create).not.toHaveBeenCalled();
  });

  it('저장 뒤 트리를 다시 읽고 상단 셸까지 갱신한다 — 저장했는데 사이드바가 옛날이면 오해한다', async () => {
    await renderPage();
    fireEvent.change(nameInput(), { target: { value: '세무' } });
    fireEvent.click(submit());

    await waitFor(() => expect(refreshShellMenus).toHaveBeenCalledTimes(1));
    expect(mockedMenu.getTree).toHaveBeenCalledTimes(2);
  });

  it('저장 실패는 사유를 알리고 폼을 비우지 않는다 — 입력을 다시 치게 만들지 않는다', async () => {
    mockedMenu.create.mockRejectedValue({ response: { data: { message: '경로가 중복입니다' } } });
    await renderPage();
    fireEvent.change(nameInput(), { target: { value: '세무' } });
    fireEvent.click(submit());

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('경로가 중복입니다'));
    expect(nameInput()).toHaveValue('세무');
    expect(refreshShellMenus).not.toHaveBeenCalled();
  });
});

describe('MenuManagementPage — 역할 allowlist', () => {
  it('CSV 다중 역할을 체크박스로 되살린다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByLabelText('ADMIN')).toBeInTheDocument());
    fireEvent.click(editButtons()[1]); // 정산관리 = 'ADMIN,MANAGER'

    expect(screen.getByLabelText('ADMIN')).toBeChecked();
    expect(screen.getByLabelText('MANAGER')).toBeChecked();
    expect(screen.getByLabelText('USER')).not.toBeChecked();
  });

  it('역할 하나를 빼도 나머지 allowlist 는 살아남는다 (통째로 날아가면 메뉴가 잠긴다)', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByLabelText('ADMIN')).toBeInTheDocument());
    fireEvent.click(editButtons()[1]);
    fireEvent.click(screen.getByLabelText('ADMIN')); // 해제
    fireEvent.click(submit());

    await waitFor(() => expect(mockedMenu.update).toHaveBeenCalledWith(11, expect.objectContaining({
      requiredRole: 'MANAGER',
    })));
  });

  it('지급 메뉴는 ADMIN 역할을 해제할 수 없다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByLabelText('ADMIN')).toBeInTheDocument());
    fireEvent.click(editButtons()[2]); // 지급관리 = 'ADMIN'
    expect(screen.getByLabelText('ADMIN')).toBeChecked();
    expect(screen.getByLabelText('MANAGER')).toBeDisabled();
    fireEvent.click(submit());

    await waitFor(() => expect(mockedMenu.update).toHaveBeenCalledWith(12, expect.objectContaining({
      requiredRole: 'ADMIN',
    })));
  });

  it('시스템 메뉴는 ADMIN 역할만 선택할 수 있다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByLabelText('ADMIN')).toBeInTheDocument());
    fireEvent.click(editButtons()[3]); // 시스템(2)

    expect(screen.getByLabelText('ADMIN')).toBeChecked();
    expect(screen.getByLabelText('MANAGER')).toBeDisabled();
    expect(screen.getByText(/시스템·민감 운영 메뉴는 ADMIN 전용/)).toBeInTheDocument();
  });
});

describe('MenuManagementPage — 순서 변경', () => {
  it('형제 전체의 정렬을 0..n 으로 다시 매겨 한 번에 저장한다', async () => {
    await renderPage();
    fireEvent.click(screen.getAllByTitle('아래로')[1]); // 정산관리(11) ↓

    await waitFor(() => expect(mockedMenu.reorder).toHaveBeenCalledWith([
      { id: 12, parentId: 1, sortOrder: 0 },
      { id: 11, parentId: 1, sortOrder: 1 },
    ]));
    expect(refreshShellMenus).toHaveBeenCalledTimes(1);
  });

  it('맨 위의 ▲ 와 맨 아래의 ▼ 는 막혀 있다 — 형제 밖으로 나가는 이동은 없다', async () => {
    await renderPage();

    expect(screen.getAllByTitle('위로')[0]).toBeDisabled();      // 정산(첫 최상위)
    expect(screen.getAllByTitle('아래로')[2]).toBeDisabled();    // 지급관리(형제 중 마지막)
    expect(screen.getAllByTitle('아래로')[1]).toBeEnabled();     // 정산관리
  });

  it('순서 변경 실패는 알리고 트리를 그대로 둔다', async () => {
    mockedMenu.reorder.mockRejectedValue(new Error('conflict'));
    await renderPage();
    fireEvent.click(screen.getAllByTitle('아래로')[1]);

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('순서 변경 실패'));
    expect(inTree('정산관리')).toBeInTheDocument();
  });
});

describe('MenuManagementPage — 삭제', () => {
  it('확인을 취소하면 서버를 부르지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderPage();
    fireEvent.click(deleteButtons()[1]);

    await waitFor(() => expect(mockedMenu.remove).not.toHaveBeenCalled());
    expect(confirmSpy).toHaveBeenCalledWith('메뉴 "정산관리" 을 삭제하시겠습니까?');
  });

  it('확인하면 삭제하고 트리·셸을 다시 그린다', async () => {
    await renderPage();
    fireEvent.click(deleteButtons()[1]);

    await waitFor(() => expect(mockedMenu.remove).toHaveBeenCalledWith(11));
    expect(mockedMenu.getTree).toHaveBeenCalledTimes(2);
    expect(refreshShellMenus).toHaveBeenCalledTimes(1);
  });

  it('하위 메뉴가 있어 거부되면 사유를 알리고 메뉴를 남긴다', async () => {
    mockedMenu.remove.mockRejectedValue({ response: { data: { message: '하위 메뉴가 있어 삭제할 수 없습니다.' } } });
    await renderPage();
    fireEvent.click(deleteButtons()[0]); // 정산(GROUP)

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('하위 메뉴가 있어 삭제할 수 없습니다.'));
    expect(inTree('정산')).toBeInTheDocument();
    expect(refreshShellMenus).not.toHaveBeenCalled();
  });
});
