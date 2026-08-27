import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 하트가 지켜야 하는 규율.
 *
 * <p>① <b>서버 응답만 그린다.</b> 낙관적으로 먼저 뒤집는 방식은, 연타로 두 번째 응답이 첫 번째보다
 * 먼저 오는 순간 화면과 서버가 어긋난 채 굳는다. 서버 응답의 {@code wished} 는 "방금 한 동작"이
 * 아니라 <b>끝난 뒤의 상태</b>라, 순서가 뒤집혀도 마지막 응답을 그리면 화면이 맞는다.
 *
 * <p>② <b>안 바뀌었으면 바뀌었다고 말하지 않는다.</b> {@code changed:false} 는 다른 탭에서 이미
 * 같은 일이 끝나 있었다는 뜻이다. 결과는 사용자가 원한 그대로이므로 실패도 아니고, 토스트를
 * 띄우면 하지도 않은 일을 했다고 말하게 된다.
 *
 * <p>③ <b>비로그인이라고 숨기지 않는다.</b> 숨기면 찜할 수 있다는 사실 자체가 안 보인다.
 */

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/wishlist', () => ({
  wishlistApi: { contains: vi.fn(), add: vi.fn(), remove: vi.fn() },
}));

const { wishlistApi } = await import('@/api/wishlist');
const { default: WishlistHeart } = await import('@/components/product/WishlistHeart');
const mocked = vi.mocked(wishlistApi);

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  mocked.contains.mockResolvedValue({ productId: 10, wished: false });
});

describe('WishlistHeart', () => {
  it('상태를 넘겨받지 않으면 단건으로 물어본다 — 목록 전체를 끌어오지 않는다', async () => {
    render(<WishlistHeart productId={10} />);

    await waitFor(() => expect(mocked.contains).toHaveBeenCalledWith(7, 10));
  });

  it('상태를 넘겨받았으면 묻지 않는다 — 20줄 목록이 20번 조회하게 두지 않는다', async () => {
    render(<WishlistHeart productId={10} initialWished />);

    expect(await screen.findByRole('button')).toHaveAttribute('aria-pressed', 'true');
    expect(mocked.contains).not.toHaveBeenCalled();
  });

  it('담기면 서버가 준 결과 상태를 그린다', async () => {
    mocked.add.mockResolvedValue({ wished: true, changed: true, count: 3 });
    render(<WishlistHeart productId={10} initialWished={false} />);

    await userEvent.click(screen.getByRole('button'));

    expect(mocked.add).toHaveBeenCalledWith(7, 10);
    await waitFor(() => expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true'));
    expect(showToast).toHaveBeenCalledWith('찜했습니다.', 'success');
  });

  it('담긴 상태에서 누르면 빼기를 부른다', async () => {
    mocked.remove.mockResolvedValue({ wished: false, changed: true, count: 2 });
    render(<WishlistHeart productId={10} initialWished />);

    await userEvent.click(screen.getByRole('button'));

    expect(mocked.remove).toHaveBeenCalledWith(7, 10);
    await waitFor(() => expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false'));
  });

  it('changed 가 false 면 토스트를 띄우지 않는다 — 하지 않은 일을 했다고 말하지 않는다', async () => {
    mocked.add.mockResolvedValue({ wished: true, changed: false, count: 3 });
    render(<WishlistHeart productId={10} initialWished={false} />);

    await userEvent.click(screen.getByRole('button'));

    await waitFor(() => expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true'));
    expect(showToast).not.toHaveBeenCalled();
  });

  it('서버가 wished 를 뒤집지 않았으면 화면도 뒤집지 않는다 — 내가 누른 것이 아니라 응답을 그린다', async () => {
    // 상한 초과 등으로 서버가 담지 못했을 때. "눌렀으니 켠다"였다면 여기서 어긋난다.
    mocked.add.mockResolvedValue({ wished: false, changed: false, count: 300 });
    const onChange = vi.fn();
    render(<WishlistHeart productId={10} initialWished={false} onChange={onChange} />);

    await userEvent.click(screen.getByRole('button'));

    // "안 바뀐다"는 바뀔 기회가 지난 뒤에야 확인된다. 호출됨만 기다리고 곧바로 보면
    // 반영 전 상태를 보고 통과하는 — 무엇을 해도 초록인 — 테스트가 된다.
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(false, 300));
    expect(await screen.findByRole('button', { name: '찜하기' }))
      .toHaveAttribute('aria-pressed', 'false');
  });

  it('실패해도 상태를 뒤집지 않는다 — 서버가 뭘 했는지 모르는 채 그리지 않는다', async () => {
    mocked.add.mockRejectedValue(new Error('400'));
    render(<WishlistHeart productId={10} initialWished={false} />);
    const button = screen.getByRole('button');

    await userEvent.click(button);

    // 실패 흐름의 끝은 finally 의 잠금 해제다. 그 지점을 지나야 "안 뒤집혔다"가 의미를 갖는다.
    await waitFor(() => {
      expect(mocked.add).toHaveBeenCalled();
      expect(button).toBeEnabled();
    });
    expect(button).toHaveAttribute('aria-pressed', 'false');
  });

  it('바깥에 결과를 알린다 — 개수 표시가 따라 움직여야 한다', async () => {
    mocked.add.mockResolvedValue({ wished: true, changed: true, count: 3 });
    const onChange = vi.fn();
    render(<WishlistHeart productId={10} initialWished={false} onChange={onChange} />);

    await userEvent.click(screen.getByRole('button'));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(true, 3));
  });

  it('비로그인이면 하트는 보이되 누르면 로그인을 안내한다', async () => {
    mockAuth.userId = null;
    render(<WishlistHeart productId={10} />);

    const button = screen.getByRole('button');
    expect(button).toBeInTheDocument();

    await userEvent.click(button);

    expect(mocked.add).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('로그인하면 찜할 수 있습니다.', 'info');
  });

  it('비로그인이면 조회도 하지 않는다 — 401 을 만들어 낼 이유가 없다', async () => {
    mockAuth.userId = null;
    render(<WishlistHeart productId={10} />);

    await waitFor(() => expect(screen.getByRole('button')).toBeInTheDocument());
    expect(mocked.contains).not.toHaveBeenCalled();
  });

  it('상태 조회가 실패해도 화면을 깨뜨리지 않는다 — 안 칠해진 채로 둔다', async () => {
    mocked.contains.mockRejectedValue(new Error('boom'));
    render(<WishlistHeart productId={10} />);

    await waitFor(() => expect(mocked.contains).toHaveBeenCalled());
    expect(await screen.findByRole('button', { name: '찜하기' }))
      .toHaveAttribute('aria-pressed', 'false');
  });

  /** 하트 모양은 스크린리더가 읽지 못한다. 담긴 상태를 알려 주는 것은 이 두 속성뿐이다. */
  it('담긴 상태를 aria 로 알린다', async () => {
    render(<WishlistHeart productId={10} initialWished />);

    const button = await screen.findByRole('button', { name: '찜 빼기' });
    expect(button).toHaveAttribute('aria-pressed', 'true');
  });
});
