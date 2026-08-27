import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>없어진 상품을 목록에서 빼지 않는다.</b> 조용히 빼면 사용자는 자기가 뭘 담았는지 영영
 * 알 수 없다. 사유를 그 자리에 쓴다.
 *
 * <p>② <b>품절은 정리 대상이 아니다.</b> 재입고를 기다리는 것이 찜의 가장 큰 용도라, 품절을
 * 일괄 정리에 넣으면 사용자가 제일 지키고 싶어 한 줄을 버튼 한 번이 지운다.
 *
 * <p>③ <b>되돌릴 수 없는 동작은 대상을 먼저 보여 준다.</b> 개수만 확인시키고 지우면 사용자가
 * 확인한 것은 숫자뿐이다.
 */

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

vi.mock('@/api/wishlist', () => ({
  wishlistApi: { list: vi.fn(), remove: vi.fn(), purgeGone: vi.fn() },
}));

const { wishlistApi } = await import('@/api/wishlist');
const { default: WishlistPage } = await import('@/pages/WishlistPage');
const mocked = vi.mocked(wishlistApi);

const item = (over: Record<string, unknown> = {}) => ({
  productId: 10,
  name: '살 수 있는 것',
  price: 1000,
  primaryImageUrl: null,
  availability: 'AVAILABLE' as const,
  reason: '구매 가능',
  available: true,
  gone: false,
  addedAt: '2026-08-01T10:00:00',
  ...over,
});

const mixed = {
  items: [
    item(),
    item({ productId: 11, name: '품절된 것', availability: 'OUT_OF_STOCK', reason: '품절', available: false }),
    item({ productId: 12, name: '단종된 것', availability: 'DISCONTINUED', reason: '단종', available: false, gone: true }),
    item({ productId: 13, name: '사라진 것', availability: 'REMOVED', reason: '판매 중지된 상품', available: false, gone: true, price: null }),
  ],
  totalCount: 4,
  goneCount: 2,
  maxItems: 300,
};

const renderPage = () => render(<MemoryRouter><WishlistPage /></MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  mockAuth.loading = false;
  mocked.list.mockResolvedValue(mixed);
});

describe('WishlistPage — 목록', () => {
  it('살 수 없는 상품도 목록에 남고 사유를 보여 준다', async () => {
    renderPage();

    expect(await screen.findByText('품절된 것')).toBeInTheDocument();
    expect(screen.getByTestId('reason-11')).toHaveTextContent('품절');
    expect(screen.getByText('단종된 것')).toBeInTheDocument();
    expect(screen.getByTestId('reason-12')).toHaveTextContent('단종');
  });

  it('살 수 있는 항목에는 사유를 붙이지 않는다 — 없는 문제를 설명하지 않는다', async () => {
    renderPage();

    await screen.findByText('살 수 있는 것');
    expect(screen.queryByTestId('reason-10')).not.toBeInTheDocument();
  });

  it('가격을 매길 수 없는 상품을 0원으로 그리지 않는다', async () => {
    renderPage();

    await screen.findByText('사라진 것');
    expect(screen.getByText('가격 정보 없음')).toBeInTheDocument();
    expect(screen.queryByText('0원')).not.toBeInTheDocument();
  });

  it('보관 상한을 함께 알려 준다', async () => {
    renderPage();

    expect(await screen.findByTestId('wishlist-count')).toHaveTextContent('4개 / 최대 300개');
  });

  it('한도가 가까우면 미리 알린다 — 담기가 거부된 다음에 알리면 늦다', async () => {
    mocked.list.mockResolvedValue({ ...mixed, totalCount: 285, maxItems: 300 });
    renderPage();

    expect(await screen.findByText(/보관 한도가 얼마 남지 않았습니다/)).toBeInTheDocument();
  });

  it('한도에서 멀면 경고하지 않는다', async () => {
    renderPage();

    await screen.findByTestId('wishlist-count');
    expect(screen.queryByText(/보관 한도가 얼마 남지 않았습니다/)).not.toBeInTheDocument();
  });

  it('빈 목록이어도 오류가 아니다', async () => {
    mocked.list.mockResolvedValue({ items: [], totalCount: 0, goneCount: 0, maxItems: 300 });
    renderPage();

    expect(await screen.findByTestId('wishlist-empty')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다 — 빈 목록으로 위장하지 않는다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('찜 목록을 불러오지 못했습니다.');
    expect(screen.queryByTestId('wishlist-empty')).not.toBeInTheDocument();
  });
});

describe('WishlistPage — 일괄 정리', () => {
  it('정리 전에 무엇을 지울지 이름으로 보여 준다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));

    const confirm = screen.getByTestId('purge-confirm');
    expect(confirm).toHaveTextContent('단종된 것');
    expect(confirm).toHaveTextContent('사라진 것');
    expect(confirm).toHaveTextContent('되돌릴 수 없습니다');
  });

  it('확인 목록에 품절은 들어가지 않는다 — 지우지 않을 것을 지운다고 말하지 않는다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));

    expect(screen.getByTestId('purge-confirm')).not.toHaveTextContent('품절된 것');
  });

  it('확인을 누르기 전에는 서버를 부르지 않는다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));

    expect(mocked.purgeGone).not.toHaveBeenCalled();
  });

  it('취소하면 아무것도 지우지 않는다', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));
    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByTestId('purge-confirm')).not.toBeInTheDocument();
    expect(mocked.purgeGone).not.toHaveBeenCalled();
  });

  it('정리한 뒤 무엇을 지웠는지 이름으로 말한다 — 개수만 말하지 않는다', async () => {
    mocked.purgeGone.mockResolvedValue({
      removed: [mixed.items[2], mixed.items[3]],
      wishlist: { items: [mixed.items[0], mixed.items[1]], totalCount: 2, goneCount: 0, maxItems: 300 },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));
    await userEvent.click(screen.getByRole('button', { name: '지우기' }));

    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('단종된 것');
    expect(status).toHaveTextContent('사라진 것');
  });

  it('정리 후에도 품절은 목록에 남는다', async () => {
    mocked.purgeGone.mockResolvedValue({
      removed: [mixed.items[2], mixed.items[3]],
      wishlist: { items: [mixed.items[0], mixed.items[1]], totalCount: 2, goneCount: 0, maxItems: 300 },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));
    await userEvent.click(screen.getByRole('button', { name: '지우기' }));

    await waitFor(() => expect(screen.queryByText('단종된 것')).not.toBeInTheDocument());
    expect(screen.getByText('품절된 것')).toBeInTheDocument();
  });

  it('정리 후에는 다시 조회하지 않는다 — 서버가 준 결과 상태를 그대로 쓴다', async () => {
    mocked.purgeGone.mockResolvedValue({
      removed: [mixed.items[2]],
      wishlist: { items: [mixed.items[0]], totalCount: 1, goneCount: 0, maxItems: 300 },
    });
    renderPage();

    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByRole('button', { name: '정리하기' }));
    await userEvent.click(screen.getByRole('button', { name: '지우기' }));

    await waitFor(() => expect(mocked.purgeGone).toHaveBeenCalledWith(7));
    expect(mocked.list).toHaveBeenCalledTimes(1);
  });

  it('지울 것이 없으면 정리 버튼 자체가 없다', async () => {
    mocked.list.mockResolvedValue({ ...mixed, goneCount: 0, items: [mixed.items[0], mixed.items[1]] });
    renderPage();

    await screen.findByText('품절된 것');
    expect(screen.queryByRole('button', { name: '정리하기' })).not.toBeInTheDocument();
  });
});

describe('WishlistPage — 개별 빼기 · 인증', () => {
  it('빼면 서버에 알리고 목록을 다시 읽는다', async () => {
    mocked.remove.mockResolvedValue({ wished: false, changed: true, count: 3 });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '살 수 있는 것 찜 빼기' }));

    expect(mocked.remove).toHaveBeenCalledWith(7, 10);
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });

  it('빼기 실패는 사유를 보여 준다', async () => {
    mocked.remove.mockRejectedValue(new Error('boom'));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '살 수 있는 것 찜 빼기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('찜에서 빼지 못했습니다.');
  });

  it('인증 확인 중에는 로그아웃 화면을 띄우지 않는다 — 새로고침마다 번쩍인다', () => {
    mockAuth.loading = true;
    mockAuth.userId = null;
    renderPage();

    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText(/로그인/)).not.toBeInTheDocument();
  });

  it('비로그인이면 조회하지 않고 로그인을 안내한다', async () => {
    mockAuth.userId = null;
    renderPage();

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument();
    expect(mocked.list).not.toHaveBeenCalled();
  });
});
