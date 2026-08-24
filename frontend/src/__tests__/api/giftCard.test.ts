import { describe, it, expect, vi, beforeEach } from 'vitest';
import { giftCardApi } from '@/api/giftCard';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 기프트카드 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것은 <b>만료 실행의 기본값이 미리보기</b>라는 점이다. 호출부가 dryRun 을
 * 빠뜨렸을 때 잔액이 실제로 소멸하는 기본값이면, 실수 한 번이 돌이킬 수 없는 소멸로 이어진다.
 */
describe('giftCardApi', () => {
  it('발행은 요청 본문을 그대로 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: [] } as never);
    const body = { quantity: 10, faceAmount: 5000, validityDays: 90, activate: false };

    await giftCardApi.issue(body);

    expect(mocked.post).toHaveBeenCalledWith('/admin/gift-cards/issue', body);
  });

  it('만료 실행의 기본값은 미리보기다 — 인자를 빠뜨려도 소멸하지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: { dryRun: true } } as never);

    await giftCardApi.runExpiry();

    expect(mocked.post).toHaveBeenCalledWith('/admin/gift-cards/expiry/run', null, {
      params: { dryRun: true, batchSize: 500 },
    });
  });

  it('실제 소멸은 명시적으로 dryRun=false 를 넘겨야 한다', async () => {
    mocked.post.mockResolvedValue({ data: { dryRun: false } } as never);

    await giftCardApi.runExpiry(false, 100);

    expect(mocked.post).toHaveBeenCalledWith('/admin/gift-cards/expiry/run', null, {
      params: { dryRun: false, batchSize: 100 },
    });
  });

  it('등록은 코드를 본문으로 보낸다 — 경로에 실으면 코드가 로그·리퍼러에 남는다', async () => {
    mocked.post.mockResolvedValue({ data: { giftCardId: 1 } } as never);

    await giftCardApi.redeem('ABCD-EFGH-IJKL');

    expect(mocked.post).toHaveBeenCalledWith('/api/gift-cards/redeem', { code: 'ABCD-EFGH-IJKL' });
  });

  it('내 잔액은 식별자 없이 부른다', async () => {
    mocked.get.mockResolvedValue({ data: { userId: 3, available: 5000 } } as never);

    expect(await giftCardApi.myBalance()).toEqual({ userId: 3, available: 5000 });
    expect(mocked.get).toHaveBeenCalledWith('/api/gift-cards/me/balance');
  });
});
