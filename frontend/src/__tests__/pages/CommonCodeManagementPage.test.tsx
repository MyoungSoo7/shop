import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CommonCodeManagementPage from '@/pages/system/CommonCodeManagementPage';
import { commonCodeApi } from '@/api/system';

vi.mock('@/api/system', () => ({
  commonCodeApi: {
    getGroups: vi.fn(),
    getCodes: vi.fn(),
    createGroup: vi.fn(),
    updateGroup: vi.fn(),
    deleteGroup: vi.fn(),
    createCode: vi.fn(),
    updateCode: vi.fn(),
    deleteCode: vi.fn(),
  },
}));

const mocked = vi.mocked(commonCodeApi);

const group = (over: Record<string, unknown> = {}) =>
  ({
    groupCode: 'ORDER_STATUS',
    name: '주문 상태',
    description: '주문 상태 코드',
    active: true,
    ...over,
  }) as never;

const code = (over: Record<string, unknown> = {}) =>
  ({ id: 1, groupCode: 'ORDER_STATUS', code: 'CREATED', label: '주문완료', sortOrder: 0, extra1: null, active: true, ...over }) as never;

let confirmSpy: ReturnType<typeof vi.spyOn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.getGroups.mockResolvedValue([
    group(),
    group({ groupCode: 'PAY_METHOD', name: '결제 수단', description: null, active: false }),
  ] as never);
  mocked.getCodes.mockResolvedValue([
    code(),
    code({ id: 2, code: 'PAID', label: '결제완료', sortOrder: 1, extra1: 'green' }),
  ] as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

const renderAndWait = async () => {
  render(<CommonCodeManagementPage />);
  await screen.findByText('공통코드 관리');
};

describe('CommonCodeManagementPage — 로드', () => {
  it('그룹을 읽고 첫 그룹의 코드를 함께 보여 준다', async () => {
    await renderAndWait();

    expect(screen.getByText('ORDER_STATUS')).toBeInTheDocument();
    expect(await screen.findByText('CREATED')).toBeInTheDocument();
    expect(mocked.getCodes).toHaveBeenCalledWith('ORDER_STATUS');
  });

  it('로드 중에는 스피너를 보여 준다', () => {
    render(<CommonCodeManagementPage />);

    expect(screen.getByText('공통코드 로드 중...')).toBeInTheDocument();
  });

  it('그룹 조회 실패는 화면 전체를 오류 문구로 대체한다', async () => {
    mocked.getGroups.mockRejectedValue(new Error('down'));
    render(<CommonCodeManagementPage />);

    expect(await screen.findByText('공통코드 그룹을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('그룹이 없으면 그 사실을 알리고 코드 영역은 선택 안내를 띄운다', async () => {
    mocked.getGroups.mockResolvedValue([] as never);
    await renderAndWait();

    expect(screen.getByText('그룹이 없습니다.')).toBeInTheDocument();
    expect(screen.getByText('왼쪽에서 그룹을 선택하세요.')).toBeInTheDocument();
  });

  it('코드 조회가 실패하면 목록을 비운다 (옛 그룹 코드를 남기지 않는다)', async () => {
    mocked.getCodes.mockRejectedValue(new Error('down'));
    await renderAndWait();

    expect(await screen.findByText('코드가 없습니다.')).toBeInTheDocument();
  });
});

describe('CommonCodeManagementPage — 그룹', () => {
  it('그룹을 검색으로 좁힌다', async () => {
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText(/그룹 검색/), 'PAY');

    expect(screen.getByText('PAY_METHOD')).toBeInTheDocument();
    expect(screen.queryByText('ORDER_STATUS')).not.toBeInTheDocument();
  });

  it('검색 결과가 없으면 그 사실을 알린다', async () => {
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText(/그룹 검색/), '없는그룹');

    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('그룹을 고르면 그 그룹의 코드를 읽는다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByText('PAY_METHOD'));

    await waitFor(() => expect(mocked.getCodes).toHaveBeenLastCalledWith('PAY_METHOD'));
  });

  it('그룹 코드는 대문자로 정규화해 생성한다', async () => {
    mocked.createGroup.mockResolvedValue(group({ groupCode: 'NEW_GROUP', name: '신규' }));
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText('GROUP_CODE'), 'new_group');
    await userEvent.type(screen.getByPlaceholderText('그룹명'), '신규');
    await userEvent.click(screen.getByRole('button', { name: '+ 그룹 추가' }));

    await waitFor(() =>
      expect(mocked.createGroup).toHaveBeenCalledWith({
        groupCode: 'NEW_GROUP',
        name: '신규',
        description: undefined,
      }),
    );
  });

  it('그룹 생성 실패는 알림으로 알린다', async () => {
    mocked.createGroup.mockRejectedValue({ response: { data: { message: '중복 코드' } } });
    await renderAndWait();

    await userEvent.type(screen.getByPlaceholderText('GROUP_CODE'), 'X');
    await userEvent.type(screen.getByPlaceholderText('그룹명'), 'X');
    await userEvent.click(screen.getByRole('button', { name: '+ 그룹 추가' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('중복 코드'));
  });

  it('활성 배지를 누르면 상태를 뒤집어 저장한다', async () => {
    mocked.updateGroup.mockResolvedValue(group({ active: false }));
    await renderAndWait();
    const row = screen.getByText('ORDER_STATUS').closest('li') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: '활성' }));

    await waitFor(() =>
      expect(mocked.updateGroup).toHaveBeenCalledWith('ORDER_STATUS', {
        name: '주문 상태',
        description: '주문 상태 코드',
        active: false,
      }),
    );
  });

  it('그룹 삭제는 확인을 거치고, 선택 중이던 그룹이면 코드 영역을 비운다', async () => {
    mocked.deleteGroup.mockResolvedValue(undefined as never);
    await renderAndWait();
    const row = screen.getByText('ORDER_STATUS').closest('li') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.deleteGroup).toHaveBeenCalledWith('ORDER_STATUS'));
    expect(await screen.findByText('왼쪽에서 그룹을 선택하세요.')).toBeInTheDocument();
  });

  it('삭제 확인을 취소하면 호출하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderAndWait();
    const row = screen.getByText('ORDER_STATUS').closest('li') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: '삭제' }));

    expect(mocked.deleteGroup).not.toHaveBeenCalled();
  });
});

describe('CommonCodeManagementPage — 코드', () => {
  it('코드를 검색으로 좁힌다', async () => {
    await renderAndWait();
    await screen.findByText('CREATED');

    await userEvent.type(screen.getByPlaceholderText(/코드 검색/), 'PAID');

    expect(screen.getByText('PAID')).toBeInTheDocument();
    expect(screen.queryByText('CREATED')).not.toBeInTheDocument();
  });

  it('검색 중에는 순서 이동을 막는다 (보이는 순서와 실제 순서가 어긋나므로)', async () => {
    await renderAndWait();
    await screen.findByText('CREATED');

    await userEvent.type(screen.getByPlaceholderText(/코드 검색/), 'PAID');

    expect(screen.getByTitle('위로')).toBeDisabled();
    expect(screen.getByTitle('아래로')).toBeDisabled();
  });

  it('코드를 대문자로 정규화해 추가한다', async () => {
    mocked.createCode.mockResolvedValue(code({ id: 3, code: 'CANCELED', label: '취소', sortOrder: 2 }));
    await renderAndWait();
    await screen.findByText('CREATED');

    await userEvent.type(screen.getByPlaceholderText('CODE'), 'canceled');
    await userEvent.type(screen.getByPlaceholderText('라벨'), '취소');
    await userEvent.click(screen.getByRole('button', { name: '+ 코드' }));

    await waitFor(() =>
      expect(mocked.createCode).toHaveBeenCalledWith({
        groupCode: 'ORDER_STATUS',
        code: 'CANCELED',
        label: '취소',
        sortOrder: 0,
        extra1: undefined,
      }),
    );
  });

  it('코드 생성 실패는 알림으로 알린다', async () => {
    mocked.createCode.mockRejectedValue(new Error('down'));
    await renderAndWait();
    await screen.findByText('CREATED');

    await userEvent.type(screen.getByPlaceholderText('CODE'), 'X');
    await userEvent.type(screen.getByPlaceholderText('라벨'), 'X');
    await userEvent.click(screen.getByRole('button', { name: '+ 코드' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('코드 생성 실패'));
  });

  it('코드 활성 상태를 뒤집는다', async () => {
    mocked.updateCode.mockResolvedValue(code({ active: false }));
    await renderAndWait();
    const row = (await screen.findByText('CREATED')).closest('tr') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: '활성' }));

    await waitFor(() =>
      expect(mocked.updateCode).toHaveBeenCalledWith(1, {
        label: '주문완료',
        sortOrder: 0,
        active: false,
        extra1: undefined,
      }),
    );
  });

  it('코드 삭제는 확인을 거친다', async () => {
    mocked.deleteCode.mockResolvedValue(undefined as never);
    await renderAndWait();
    const row = (await screen.findByText('CREATED')).closest('tr') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.deleteCode).toHaveBeenCalledWith(1));
  });

  it('순서 이동은 자리가 바뀐 항목만 다시 저장하고 목록을 다시 읽는다', async () => {
    mocked.updateCode.mockResolvedValue(code() as never);
    await renderAndWait();
    await screen.findByText('CREATED');

    const secondRow = screen.getByText('PAID').closest('tr') as HTMLElement;
    await userEvent.click(within(secondRow).getByTitle('위로'));

    await waitFor(() => expect(mocked.updateCode).toHaveBeenCalledTimes(2));
    expect(mocked.getCodes).toHaveBeenCalledTimes(2);
  });

  it('첫 행의 위로·마지막 행의 아래로는 잠겨 있다', async () => {
    await renderAndWait();
    await screen.findByText('CREATED');

    const firstRow = screen.getByText('CREATED').closest('tr') as HTMLElement;
    const lastRow = screen.getByText('PAID').closest('tr') as HTMLElement;
    expect(within(firstRow).getByTitle('위로')).toBeDisabled();
    expect(within(lastRow).getByTitle('아래로')).toBeDisabled();
  });

  it('순서 변경 실패는 알림으로 알린다', async () => {
    mocked.updateCode.mockRejectedValue(new Error('down'));
    await renderAndWait();
    await screen.findByText('CREATED');

    const secondRow = screen.getByText('PAID').closest('tr') as HTMLElement;
    await userEvent.click(within(secondRow).getByTitle('위로'));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('순서 변경 실패'));
  });
});
