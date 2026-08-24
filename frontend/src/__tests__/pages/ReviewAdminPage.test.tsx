import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ReviewAdminPage from '@/pages/system/ReviewAdminPage';
import { reviewAdminApi, type ReviewPage, type ReviewRow } from '@/api/reviewAdmin';
import { saveBlob } from '@/api/auditLog';

vi.mock('@/api/reviewAdmin', () => ({
  reviewAdminApi: {
    search: vi.fn(),
    statusCounts: vi.fn(),
    statuses: vi.fn(),
    hide: vi.fn(),
    restore: vi.fn(),
    export: vi.fn(),
  },
}));
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(reviewAdminApi);
const mockedSave = vi.mocked(saveBlob);

const review = (overrides: Partial<ReviewRow> = {}): ReviewRow => ({
  id: 11,
  productId: 2,
  productName: '무선 이어폰',
  userId: 3,
  userEmail: 'hong@lemuel.io',
  rating: 1,
  content: '최악입니다',
  status: 'VISIBLE',
  hiddenReason: null,
  hiddenBy: null,
  hiddenAt: null,
  createdAt: '2026-03-01T12:00:00',
  ...overrides,
});

const pageOf = (rows: ReviewRow[]): ReviewPage => ({
  content: rows,
  page: 0,
  size: 50,
  totalElements: rows.length,
  totalPages: rows.length === 0 ? 0 : 1,
});

describe('ReviewAdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.statuses.mockResolvedValue(['VISIBLE', 'HIDDEN']);
    mocked.statusCounts.mockResolvedValue([]);
    mocked.search.mockResolvedValue(pageOf([]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('공개 리뷰에는 "블라인드" 버튼이 뜬다 — "삭제"라고 말하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([review()]));
    render(<ReviewAdminPage />);

    expect(await screen.findByRole('button', { name: '블라인드' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
  });

  it('블라인드된 리뷰에는 되돌리기 버튼과 사유가 보인다', async () => {
    mocked.search.mockResolvedValue(pageOf([
      review({ status: 'HIDDEN', hiddenReason: '욕설 신고' }),
    ]));
    render(<ReviewAdminPage />);

    expect(await screen.findByRole('button', { name: '공개로 되돌리기' })).toBeInTheDocument();
    expect(screen.getByText('사유: 욕설 신고')).toBeInTheDocument();
  });

  it('블라인드는 사유와 함께 보낸다', async () => {
    mocked.search.mockResolvedValue(pageOf([review()]));
    mocked.hide.mockResolvedValue(undefined);
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('욕설 신고'));
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '블라인드' }));

    await waitFor(() => expect(mocked.hide).toHaveBeenCalledWith(11, '욕설 신고'));
  });

  it('사유를 비우면 블라인드하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([review()]));
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('  '));
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '블라인드' }));

    expect(mocked.hide).not.toHaveBeenCalled();
  });

  it('블라인드 후 안내는 원문이 남는다는 사실을 말한다', async () => {
    mocked.search.mockResolvedValue(pageOf([review()]));
    mocked.hide.mockResolvedValue(undefined);
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('욕설'));
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '블라인드' }));

    expect(await screen.findByRole('status')).toHaveTextContent('원문은 남아 있습니다');
  });

  it('되돌리기는 사유 없이 부른다', async () => {
    mocked.search.mockResolvedValue(pageOf([review({ status: 'HIDDEN', hiddenReason: '욕설' })]));
    mocked.restore.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '공개로 되돌리기' }));

    await waitFor(() => expect(mocked.restore).toHaveBeenCalledWith(11));
  });

  it('조작 후 목록을 다시 읽는다 — 낙관적 갱신을 하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([review({ status: 'HIDDEN' })]));
    mocked.restore.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await screen.findByRole('button', { name: '공개로 되돌리기' });

    const before = mocked.search.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '공개로 되돌리기' }));

    await waitFor(() => expect(mocked.search.mock.calls.length).toBeGreaterThan(before));
  });

  it('평점 상한을 고르면 질의에 실린다', async () => {
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('평점 상한'), '2');

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.maxRating === 2)).toBe(true));
  });

  it('상태를 골라도 집계 질의에는 상태를 싣지 않는다', async () => {
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.statusCounts).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('노출 상태'), 'HIDDEN');

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.status === 'HIDDEN')).toBe(true));
    expect(mocked.statusCounts.mock.calls.every(([q]) => q.status === undefined)).toBe(true);
  });

  it('결과가 없으면 그렇게 말한다', async () => {
    render(<ReviewAdminPage />);

    expect(await screen.findByText('조건에 맞는 리뷰가 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사용자에게 드러낸다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<ReviewAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('상태 목록을 못 받아도 화면은 뜬다', async () => {
    mocked.statuses.mockRejectedValue(new Error('boom'));
    render(<ReviewAdminPage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    expect(within(await screen.findByLabelText('노출 상태')).getAllByRole('option')).toHaveLength(1);
  });

  it('CSV 실패도 드러낸다', async () => {
    mocked.export.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('CSV');
  });

  it('블라인드 실패는 사용자에게 드러난다', async () => {
    mocked.search.mockResolvedValue(pageOf([review()]));
    mocked.hide.mockRejectedValue(new Error('boom'));
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('욕설'));
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '블라인드' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('본문·상품ID·기간 필터는 그대로 질의에 실린다', async () => {
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.type(await screen.findByLabelText('본문 검색'), '최악');
    await user.type(screen.getByLabelText('상품 ID'), '2');
    await user.type(screen.getByLabelText('작성 시작일'), '2026-03-01');
    await user.type(screen.getByLabelText('작성 종료일'), '2026-03-31');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.search.mock.calls.some(([q]) =>
      q.keyword === '최악' && q.productId === 2
      && q.from === '2026-03-01' && q.to === '2026-03-31',
    )).toBe(true));
  });

  it('여러 페이지면 이동이 나오고, 조회를 다시 누르면 1페이지로 돌아온다', async () => {
    mocked.search.mockResolvedValue({
      ...pageOf([review()]), totalElements: 120, totalPages: 3,
    });
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '조회' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
  });

  it('상태별 건수 칩을 누르면 그 상태로 좁혀 조회한다', async () => {
    mocked.statusCounts.mockResolvedValue([{ status: 'HIDDEN', count: 3 }]);
    const user = userEvent.setup();
    render(<ReviewAdminPage />);

    await user.click(await screen.findByRole('button', { name: /블라인드 3/ }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.status === 'HIDDEN')).toBe(true));
  });

  it('CSV 가 온전하면 전부 받았다고 알린다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']), fileName: 'reviews.csv', truncated: false, total: 7,
    });
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('7건을 모두 내려받았습니다');
  });

  it('CSV 가 잘리면 몇 건 중 몇 건인지 말한다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']),
      fileName: 'reviews.csv',
      truncated: true,
      total: 12345,
    });
    const user = userEvent.setup();
    render(<ReviewAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('12,345건 중 앞 5,000건');
    expect(mockedSave).toHaveBeenCalled();
  });
});
