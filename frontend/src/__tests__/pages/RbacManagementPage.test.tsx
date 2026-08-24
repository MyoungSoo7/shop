import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RbacManagementPage from '@/pages/system/RbacManagementPage';
import { rbacApi } from '@/api/system';

vi.mock('@/api/system', () => ({
  rbacApi: {
    getRoles: vi.fn(),
    getPermissions: vi.fn(),
    updateRolePermissions: vi.fn(),
    createRole: vi.fn(),
    updateRole: vi.fn(),
    cloneRole: vi.fn(),
    deleteRole: vi.fn(),
  },
}));

const mocked = vi.mocked(rbacApi);

const role = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    code: 'ADMIN',
    name: '관리자',
    description: '전체 권한',
    builtin: true,
    permissionIds: [1],
    ...over,
  }) as never;

const permission = (over: Record<string, unknown> = {}) =>
  ({ id: 1, code: 'ORDER_READ', name: '주문 조회', category: '주문', ...over }) as never;

let confirmSpy: ReturnType<typeof vi.spyOn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.getRoles.mockResolvedValue([
    role(),
    role({ id: 2, code: 'CS_AGENT', name: 'CS 상담원', builtin: false, permissionIds: [] }),
  ] as never);
  mocked.getPermissions.mockResolvedValue([
    permission(),
    permission({ id: 2, code: 'ORDER_CANCEL', name: '주문 취소', category: '주문' }),
    permission({ id: 3, code: 'SETTLEMENT_READ', name: '정산 조회', category: '정산' }),
  ] as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

describe('RbacManagementPage — 로드', () => {
  it('역할·권한을 함께 읽고 첫 역할을 자동 선택한다', async () => {
    render(<RbacManagementPage />);

    expect(await screen.findByText('RBAC 관리')).toBeInTheDocument();
    // 왼쪽 목록과 선택된 역할 헤더 양쪽에 코드가 찍힌다
    expect(screen.getAllByText('ADMIN').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/선택 1 \/ 전체 3/)).toBeInTheDocument();
  });

  it('로드 중에는 스피너를 보여 준다', () => {
    render(<RbacManagementPage />);

    expect(screen.getByText('RBAC 로드 중...')).toBeInTheDocument();
  });

  it('조회 실패는 화면 전체를 오류 문구로 대체한다', async () => {
    mocked.getRoles.mockRejectedValue(new Error('down'));
    render(<RbacManagementPage />);

    expect(await screen.findByText('RBAC 데이터를 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('역할이 하나도 없으면 선택 안내를 보여 준다', async () => {
    mocked.getRoles.mockResolvedValue([] as never);
    render(<RbacManagementPage />);

    expect(await screen.findByText('왼쪽에서 역할을 선택하세요.')).toBeInTheDocument();
  });

  it('권한이 없으면 그 사실을 알린다', async () => {
    mocked.getPermissions.mockResolvedValue([] as never);
    render(<RbacManagementPage />);

    expect(await screen.findByText('등록된 권한이 없습니다.')).toBeInTheDocument();
  });
});

describe('RbacManagementPage — 권한 매트릭스', () => {
  const renderAndWait = async () => {
    render(<RbacManagementPage />);
    await screen.findByText('RBAC 관리');
  };

  it('카테고리별로 묶어 보여 주고 선택 수를 센다', async () => {
    await renderAndWait();

    expect(screen.getByText('주문')).toBeInTheDocument();
    expect(screen.getByText('(1/2)')).toBeInTheDocument(); // 주문 카테고리 1개 선택
    expect(screen.getByText('정산')).toBeInTheDocument();
  });

  it('저장 버튼은 변경 전에는 잠겨 있다', async () => {
    await renderAndWait();

    expect(screen.getByRole('button', { name: '권한 저장' })).toBeDisabled();
  });

  it('권한을 켜면 변경됨 표시가 뜨고 저장이 열린다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('checkbox', { name: /주문 취소/ }));

    expect(screen.getByText('· 변경됨')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '권한 저장' })).toBeEnabled();
  });

  it('켠 권한을 다시 끄면 원상태로 돌아가 저장이 다시 잠긴다', async () => {
    await renderAndWait();
    const checkbox = screen.getByRole('checkbox', { name: /주문 취소/ });

    await userEvent.click(checkbox);
    await userEvent.click(checkbox);

    expect(screen.getByRole('button', { name: '권한 저장' })).toBeDisabled();
  });

  it('카테고리 전체 선택·해제가 동작한다', async () => {
    await renderAndWait();
    const orderHeader = screen.getByText('주문').closest('div') as HTMLElement;

    await userEvent.click(within(orderHeader).getByRole('button', { name: '전체 선택' }));
    expect(screen.getByText('(2/2)')).toBeInTheDocument();

    await userEvent.click(within(orderHeader).getByRole('button', { name: '전체 해제' }));
    expect(screen.getByText('(0/2)')).toBeInTheDocument();
  });

  it('권한을 검색으로 좁힌다', async () => {
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText(/권한 검색/), '정산');

    expect(screen.getByText('정산 조회')).toBeInTheDocument();
    expect(screen.queryByText('주문 조회')).not.toBeInTheDocument();
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText(/권한 검색/), '없는권한');

    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('저장하면 서버 결과로 목록과 선택 상태를 갱신한다', async () => {
    mocked.updateRolePermissions.mockResolvedValue(role({ permissionIds: [1, 2] }));
    await renderAndWait();
    await userEvent.click(screen.getByRole('checkbox', { name: /주문 취소/ }));

    await userEvent.click(screen.getByRole('button', { name: '권한 저장' }));

    await waitFor(() => expect(mocked.updateRolePermissions).toHaveBeenCalledWith(1, [1, 2]));
    expect(await screen.findByText('권한이 저장되었습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '권한 저장' })).toBeDisabled();
  });

  it('저장 실패는 알림으로 알린다', async () => {
    mocked.updateRolePermissions.mockRejectedValue({ response: { data: { message: '권한 없음' } } });
    await renderAndWait();
    await userEvent.click(screen.getByRole('checkbox', { name: /주문 취소/ }));

    await userEvent.click(screen.getByRole('button', { name: '권한 저장' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('권한 없음'));
  });

  it('다른 역할을 고르면 그 역할의 선택 상태로 바뀐다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByText('CS_AGENT'));

    expect(screen.getByText(/선택 0 \/ 전체 3/)).toBeInTheDocument();
  });
});

describe('RbacManagementPage — 역할 CRUD', () => {
  const renderAndWait = async () => {
    render(<RbacManagementPage />);
    await screen.findByText('RBAC 관리');
  };

  it('새 역할을 만들면 코드가 대문자로 정규화된다', async () => {
    mocked.createRole.mockResolvedValue(
      role({ id: 3, code: 'CS_AGENT2', name: 'CS2', builtin: false, permissionIds: [] }),
    );
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '+ 새 역할' }));
    await userEvent.type(screen.getByPlaceholderText('CS_AGENT'), 'cs_agent2');
    await userEvent.type(screen.getByPlaceholderText('CS 상담원'), 'CS2');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mocked.createRole).toHaveBeenCalledWith({
        code: 'CS_AGENT2',
        name: 'CS2',
        description: undefined,
      }),
    );
  });

  it('수정 폼에서는 코드를 바꿀 수 없다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    expect(screen.getByPlaceholderText('CS_AGENT')).toBeDisabled();
    expect(screen.getByText(/역할 수정 — ADMIN/)).toBeInTheDocument();
  });

  it('수정 저장은 이름·설명만 보낸다', async () => {
    mocked.updateRole.mockResolvedValue(role({ name: '최고 관리자' }));
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    await userEvent.clear(screen.getByPlaceholderText('CS 상담원'));
    await userEvent.type(screen.getByPlaceholderText('CS 상담원'), '최고 관리자');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mocked.updateRole).toHaveBeenCalledWith(1, {
        name: '최고 관리자',
        description: '전체 권한',
      }),
    );
  });

  it('복제 폼은 코드·이름 기본값을 원본에서 파생한다', async () => {
    mocked.cloneRole.mockResolvedValue(
      role({ id: 4, code: 'ADMIN_COPY', builtin: false, permissionIds: [1] }),
    );
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '복제' }));

    expect(screen.getByPlaceholderText('CS_AGENT')).toHaveValue('ADMIN_COPY');
    expect(screen.getByPlaceholderText('CS 상담원')).toHaveValue('관리자 (복제)');

    // ByRoleOptions 에 exact 는 없다(타입 오류) — 정확 일치는 name 정규식으로 표현한다
    await userEvent.click(screen.getByRole('button', { name: /^복제$/ }));

    await waitFor(() => expect(mocked.cloneRole).toHaveBeenCalledWith(1, 'ADMIN_COPY', '관리자 (복제)'));
  });

  it('기본 역할은 삭제 버튼이 없다', async () => {
    await renderAndWait();

    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
  });

  it('사용자 정의 역할은 확인 후 삭제하고 다음 역할로 넘어간다', async () => {
    mocked.deleteRole.mockResolvedValue(undefined as never);
    await renderAndWait();
    await userEvent.click(screen.getByText('CS_AGENT'));

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.deleteRole).toHaveBeenCalledWith(2));
    expect(screen.queryByText('CS_AGENT')).not.toBeInTheDocument();
  });

  it('삭제 확인을 취소하면 호출하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderAndWait();
    await userEvent.click(screen.getByText('CS_AGENT'));

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(mocked.deleteRole).not.toHaveBeenCalled();
  });

  it('역할 저장 실패는 알림으로 알린다', async () => {
    mocked.createRole.mockRejectedValue(new Error('down'));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '+ 새 역할' }));
    await userEvent.type(screen.getByPlaceholderText('CS_AGENT'), 'X');
    await userEvent.type(screen.getByPlaceholderText('CS 상담원'), 'X');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('역할 저장 실패'));
  });

  it('폼에서 취소하면 매트릭스로 돌아온다', async () => {
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '+ 새 역할' }));

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.getByPlaceholderText(/권한 검색/)).toBeInTheDocument();
  });
});
