import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>기본 배송지를 해제할 수 없다.</b> 주소록에 줄이 있는 한 기본은 반드시 하나다. 0개가
 * 되면 주문서의 배송지 칸이 빈 채로 열리고 사용자는 왜 비었는지 알 수 없다.
 *
 * <p>② <b>기본 지정은 요청 한 번이다.</b> "전부 내리기"와 "하나 올리기"를 화면이 두 번 부르면
 * 사이에서 끊길 때 기본이 0개인 상태가 남는다. 레거시가 그랬다.
 *
 * <p>③ <b>기본 지정·삭제 뒤에 다시 조회하지 않는다.</b> 서버가 주소록 전체를 돌려주므로 그걸
 * 그대로 쓴다. 다시 읽으면 그 사이 다른 탭의 변경이 섞여 방금 누른 결과가 뒤집혀 보인다.
 *
 * <p>④ <b>별칭과 받는 분은 다른 칸이다.</b> 등록에서 두 값이 각각 그대로 실려 나가야 한다.
 */

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

vi.mock('@/api/addressBook', () => ({
  addressBookApi: {
    list: vi.fn(), register: vi.fn(), modify: vi.fn(), setDefault: vi.fn(), remove: vi.fn(),
  },
}));

const { addressBookApi } = await import('@/api/addressBook');
const { default: AddressBookPage } = await import('@/pages/AddressBookPage');
const mocked = vi.mocked(addressBookApi);

const address = (over: Record<string, unknown> = {}) => ({
  id: 1,
  label: '집',
  recipientName: '홍길동',
  phone: '010-1234-5678',
  postalCode: '06236',
  address1: '서울 강남구 테헤란로 1',
  address2: '301호',
  deliveryMemo: null,
  isDefault: true,
  createdAt: '2026-08-01T10:00:00',
  updatedAt: '2026-08-01T10:00:00',
  ...over,
});

const twoEntries = {
  addresses: [address(), address({ id: 2, label: '회사', recipientName: '김철수', isDefault: false })],
  totalCount: 2,
  maxAddresses: 30,
};

const renderPage = () => render(<MemoryRouter><AddressBookPage /></MemoryRouter>);

const fill = async () => {
  await userEvent.type(screen.getByTestId('address-label'), '회사');
  await userEvent.type(screen.getByTestId('address-recipientName'), '김철수');
  await userEvent.type(screen.getByTestId('address-phone'), '010-0000-0000');
  await userEvent.type(screen.getByTestId('address-postalCode'), '06236');
  await userEvent.type(screen.getByTestId('address-address1'), '서울 강남구');
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  mockAuth.loading = false;
  mocked.list.mockResolvedValue(twoEntries);
});

describe('AddressBookPage — 목록', () => {
  it('기본 배송지에만 표시가 붙는다', async () => {
    renderPage();

    expect(await screen.findByTestId('address-default-1')).toHaveTextContent('기본 배송지');
    expect(screen.queryByTestId('address-default-2')).not.toBeInTheDocument();
  });

  it('별칭과 받는 분을 각각 보여 준다 — 한 값으로 뭉개지 않는다', async () => {
    renderPage();

    await screen.findByText('집');
    expect(screen.getByTestId('address-item-1')).toHaveTextContent('홍길동');
    expect(screen.getByTestId('address-item-2')).toHaveTextContent('회사');
    expect(screen.getByTestId('address-item-2')).toHaveTextContent('김철수');
  });

  it('보관 상한을 함께 알려 준다', async () => {
    renderPage();

    expect(await screen.findByTestId('address-count')).toHaveTextContent('2개 / 최대 30개');
  });

  it('빈 주소록은 오류가 아니다', async () => {
    mocked.list.mockResolvedValue({ addresses: [], totalCount: 0, maxAddresses: 30 });
    renderPage();

    expect(await screen.findByTestId('addressbook-empty')).toBeInTheDocument();
    expect(screen.getByText('첫 배송지는 지정하지 않아도 기본이 됩니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다 — 빈 주소록으로 위장하지 않는다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('주소록을 불러오지 못했습니다.');
    expect(screen.queryByTestId('addressbook-empty')).not.toBeInTheDocument();
  });
});

describe('AddressBookPage — 기본 배송지', () => {
  it('이미 기본인 줄에는 "기본으로" 버튼이 없다 — 누를 수 있으면 해제로 읽힌다', async () => {
    renderPage();

    await screen.findByTestId('address-item-1');
    expect(screen.queryByRole('button', { name: '집 기본 배송지로 지정' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '회사 기본 배송지로 지정' })).toBeInTheDocument();
  });

  it('기본 지정은 요청 한 번이고, 응답을 그대로 반영한다', async () => {
    mocked.setDefault.mockResolvedValue({
      addresses: [
        address({ id: 2, label: '회사', recipientName: '김철수', isDefault: true }),
        address({ isDefault: false }),
      ],
      totalCount: 2,
      maxAddresses: 30,
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '회사 기본 배송지로 지정' }));

    expect(mocked.setDefault).toHaveBeenCalledTimes(1);
    expect(mocked.setDefault).toHaveBeenCalledWith(7, 2);
    expect(await screen.findByTestId('address-default-2')).toBeInTheDocument();
    // 응답을 그대로 쓴다 — 다시 읽으면 그 사이 다른 탭의 변경이 섞인다.
    expect(mocked.list).toHaveBeenCalledTimes(1);
  });

  it('기본 지정 실패는 사유를 보여 준다', async () => {
    mocked.setDefault.mockRejectedValue(new Error('boom'));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '회사 기본 배송지로 지정' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('기본 배송지를 바꾸지 못했습니다.');
  });
});

describe('AddressBookPage — 등록', () => {
  it('별칭과 받는 분을 각각 실어 보낸다', async () => {
    mocked.register.mockResolvedValue(address({ id: 3 }));
    renderPage();

    await screen.findByTestId('address-count');
    await fill();
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith(7,
      expect.objectContaining({ label: '회사', recipientName: '김철수' })));
  });

  it('필수 칸이 비면 등록 버튼을 누를 수 없다', async () => {
    renderPage();

    await screen.findByTestId('address-count');
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled();
  });

  it('한도가 차면 등록을 막고 이유를 먼저 말한다 — 거부된 뒤에 알리면 늦다', async () => {
    mocked.list.mockResolvedValue({ ...twoEntries, totalCount: 30, maxAddresses: 30 });
    renderPage();

    expect(await screen.findByTestId('address-full')).toBeInTheDocument();
    await fill();
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled();
  });
});

describe('AddressBookPage — 수정', () => {
  it('수정을 누르면 그 줄의 값이 폼에 실린다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '회사 수정' }));

    expect(screen.getByTestId('address-label')).toHaveValue('회사');
    expect(screen.getByTestId('address-recipientName')).toHaveValue('김철수');
  });

  /**
   * 수정 폼의 "기본으로 지정"은 꺼진 채로 열려야 한다. 켜 둔 채 열면, 다른 줄을 기본으로 올려
   * 둔 뒤 이 줄을 손보기만 해도 기본이 조용히 되돌아온다.
   */
  it('수정 폼의 기본 지정 체크는 꺼진 채로 열린다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '집 수정' }));

    expect(screen.getByTestId('address-makeDefault')).not.toBeChecked();
  });

  it('저장은 그 배송지 id 로 나간다', async () => {
    mocked.modify.mockResolvedValue(address({ id: 2, label: '회사' }));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '회사 수정' }));
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mocked.modify).toHaveBeenCalledWith(7, 2,
      expect.objectContaining({ label: '회사' })));
  });
});

describe('AddressBookPage — 삭제', () => {
  it('바로 지우지 않고 먼저 묻는다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '회사 삭제' }));

    expect(screen.getByTestId('delete-confirm-2')).toHaveTextContent('되돌릴 수 없습니다');
    expect(mocked.remove).not.toHaveBeenCalled();
  });

  /** 기본을 지우면 다른 줄이 올라온다. 그 사실을 지운 다음에 알면 사용자는 놀란다. */
  it('기본을 지울 때는 다른 배송지가 기본이 된다는 것을 미리 말한다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '집 삭제' }));

    expect(screen.getByTestId('delete-confirm-1'))
      .toHaveTextContent('지우면 다른 배송지가 기본이 됩니다');
  });

  it('지우고 나면 서버가 준 주소록을 그대로 그린다 — 승계가 화면에 바로 보인다', async () => {
    mocked.remove.mockResolvedValue({
      addresses: [address({ id: 2, label: '회사', recipientName: '김철수', isDefault: true })],
      totalCount: 1,
      maxAddresses: 30,
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '집 삭제' }));
    await userEvent.click(screen.getByRole('button', { name: '지우기' }));

    expect(await screen.findByTestId('address-default-2')).toBeInTheDocument();
    expect(screen.queryByTestId('address-item-1')).not.toBeInTheDocument();
    expect(mocked.list).toHaveBeenCalledTimes(1);
  });
});

describe('AddressBookPage — 인증', () => {
  it('인증 확인 중에는 로그아웃 화면을 번쩍이지 않는다', () => {
    mockAuth.loading = true;
    renderPage();

    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText(/로그인/)).not.toBeInTheDocument();
  });

  it('로그인하지 않았으면 로그인 링크를 보여 준다', () => {
    mockAuth.userId = null;
    renderPage();

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument();
    expect(mocked.list).not.toHaveBeenCalled();
  });
});
