import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import DisplaySectionAdminPage from '@/pages/DisplaySectionAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { displaySectionApi, type DisplaySection } from '@/api/displaySection';

vi.mock('@/api/displaySection', () => ({
  displaySectionApi: {
    listAll: vi.fn(),
    listVisible: vi.fn(),
    items: vi.fn(),
    create: vi.fn(),
    addItem: vi.fn(),
    removeItem: vi.fn(),
    reschedule: vi.fn(),
    setActive: vi.fn(),
  },
}));

const mocked = vi.mocked(displaySectionApi);

const summer: DisplaySection = {
  id: 7, code: 'EXH_2026_SUMMER', name: '2026 여름 기획전', kind: 'EXHIBITION',
  categoryId: null, startsAt: '2026-06-01T00:00:00', endsAt: '2026-08-31T23:59:00',
  sortOrder: 3, active: true,
};

/** 활성 플래그는 살아 있지만 기간이 끝난 편성 — "플래그만 보면 노출중" 으로 착각하기 쉬운 행 */
const spring: DisplaySection = {
  id: 8, code: 'EXH_2026_SPRING', name: '2026 봄 기획전', kind: 'EXHIBITION',
  categoryId: null, startsAt: '2026-03-01T00:00:00', endsAt: '2026-05-31T23:59:00',
  sortOrder: 1, active: true,
};

const renderPage = () => render(<ToastProvider><DisplaySectionAdminPage /></ToastProvider>);

const openSection = async (name: string) => {
  const row = (await screen.findByText(name)).closest('tr')!;
  fireEvent.click(within(row).getByRole('button', { name: '열기' }));
  await waitFor(() => expect(mocked.items).toHaveBeenCalled());
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.listAll.mockResolvedValue([spring, summer]);
  mocked.listVisible.mockResolvedValue([summer]);
  mocked.items.mockResolvedValue([
    { productId: 101, sortOrder: 5, pinned: true },
    { productId: 102, sortOrder: 1, pinned: false },
  ]);
  mocked.create.mockResolvedValue(summer);
  mocked.addItem.mockResolvedValue(undefined);
  mocked.removeItem.mockResolvedValue(undefined);
  mocked.reschedule.mockResolvedValue(summer);
  mocked.setActive.mockResolvedValue({ ...summer, active: false });
});

describe('DisplaySectionAdminPage', () => {
  it('노출 여부는 클라이언트가 기간을 계산하지 않고 서버의 노출 목록으로 판정한다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());
    expect(mocked.listVisible).toHaveBeenCalled();

    // 둘 다 active=true 지만, 노출 목록에 든 것만 '노출중' 이다
    const summerRow = (await screen.findByText('2026 여름 기획전')).closest('tr')!;
    const springRow = screen.getByText('2026 봄 기획전').closest('tr')!;
    expect(within(summerRow).getByText('노출중')).toBeInTheDocument();
    expect(within(springRow).getByText('비노출')).toBeInTheDocument();
  });

  it('편성을 열면 담긴 상품을 서버가 준 순서 그대로 보여 준다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());

    await openSection('2026 여름 기획전');

    expect(mocked.items).toHaveBeenCalledWith('EXH_2026_SUMMER');
    // items 호출 확인과 렌더 반영 사이에 상태 갱신 한 틱이 있다 — findAllBy 로 재시도 조회 (CI 플레이크 실측)
    const rows = await screen.findAllByTestId('section-item-row');
    expect(within(rows[0]).getByText('101')).toBeInTheDocument();
    expect(within(rows[0]).getByText('고정')).toBeInTheDocument();
    expect(within(rows[1]).getByText('102')).toBeInTheDocument();
  });

  it('카테고리 베스트인데 대상 카테고리가 비면 생성 요청을 보내지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());

    fireEvent.change(await screen.findByLabelText('편성 코드'), { target: { value: 'BEST_SHOES' } });
    fireEvent.change(screen.getByLabelText('편성 이름'), { target: { value: '신발 베스트' } });
    fireEvent.change(screen.getByLabelText('종류'), { target: { value: 'CATEGORY_BEST' } });
    fireEvent.click(screen.getByRole('button', { name: '편성 만들기' }));

    await waitFor(() => expect(mocked.create).not.toHaveBeenCalled());
  });

  it('편성을 만들면 목록을 다시 읽는다 — 노출 판정이 서버 몫이기 때문이다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalledTimes(1));

    fireEvent.change(await screen.findByLabelText('편성 코드'), { target: { value: 'EXH_2026_FALL' } });
    fireEvent.change(screen.getByLabelText('편성 이름'), { target: { value: '2026 가을 기획전' } });
    fireEvent.change(screen.getByLabelText('시작 시각'), { target: { value: '2026-09-01T00:00' } });
    fireEvent.change(screen.getByLabelText('정렬 순서'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: '편성 만들기' }));

    await waitFor(() => expect(mocked.create).toHaveBeenCalledWith({
      code: 'EXH_2026_FALL',
      name: '2026 가을 기획전',
      kind: 'EXHIBITION',
      categoryId: null,
      startsAt: '2026-09-01T00:00',
      endsAt: null,
      sortOrder: 4,
    }));
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalledTimes(2));
  });

  it('상품을 담으면 편성 내용을 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());
    await openSection('2026 여름 기획전');

    fireEvent.change(await screen.findByLabelText('상품 ID'), { target: { value: '103' } });
    fireEvent.change(screen.getByLabelText('표시 순서'), { target: { value: '2' } });
    fireEvent.click(screen.getByLabelText('맨 앞 고정'));
    fireEvent.click(screen.getByRole('button', { name: '상품 담기' }));

    await waitFor(() => expect(mocked.addItem).toHaveBeenCalledWith('EXH_2026_SUMMER',
      { productId: 103, sortOrder: 2, pinned: true }));
    await waitFor(() => expect(mocked.items).toHaveBeenCalledTimes(2));
  });

  it('상품 ID 없이 담기를 누르면 요청하지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());
    await openSection('2026 여름 기획전');

    fireEvent.click(await screen.findByRole('button', { name: '상품 담기' }));

    await waitFor(() => expect(mocked.addItem).not.toHaveBeenCalled());
  });

  it('담긴 상품을 뺀다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());
    await openSection('2026 여름 기획전');

    const rows = await screen.findAllByTestId('section-item-row');
    fireEvent.click(within(rows[1]).getByRole('button', { name: '빼기' }));

    await waitFor(() => expect(mocked.removeItem).toHaveBeenCalledWith('EXH_2026_SUMMER', 102));
  });

  it('노출 중지는 활성 플래그를 내린다 — 삭제가 아니라 되돌릴 수 있는 조작이다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalledTimes(1));

    const row = (await screen.findByText('2026 여름 기획전')).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '노출 중지' }));

    await waitFor(() => expect(mocked.setActive).toHaveBeenCalledWith('EXH_2026_SUMMER', false));
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalledTimes(2));
  });

  it('끝난 편성은 기간을 다시 잡아 되살린다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAll).toHaveBeenCalled());
    await openSection('2026 봄 기획전');

    fireEvent.change(await screen.findByLabelText('기간 종료'), { target: { value: '2026-12-31T23:59' } });
    fireEvent.click(screen.getByRole('button', { name: '기간 저장' }));

    await waitFor(() => expect(mocked.reschedule).toHaveBeenCalledWith('EXH_2026_SPRING',
      { startsAt: '2026-03-01T00:00', endsAt: '2026-12-31T23:59' }));
  });

  it('편성이 하나도 없으면 빈 상태를 알린다', async () => {
    mocked.listAll.mockResolvedValue([]);
    mocked.listVisible.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/등록된 편성이 없습니다/)).toBeInTheDocument());
  });

  it('목록 조회 실패는 화면에 남긴다', async () => {
    mocked.listAll.mockRejectedValue(new Error('boom'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/편성을 불러오지 못했습니다|boom/)).toBeInTheDocument());
  });
});
