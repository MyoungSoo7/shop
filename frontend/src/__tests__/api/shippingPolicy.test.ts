import { describe, it, expect, vi, beforeEach } from 'vitest';
import { shippingPolicyApi, describeThreshold, formatWon } from '@/api/shippingPolicy';
import api from '@/api/axios';

/**
 * 이 모듈이 존재하는 이유는 <b>서버가 보내는 모양과 화면이 기대하는 모양이 다르기</b> 때문이다.
 *
 * <p>REST 응답의 BigDecimal 은 JSON <b>숫자</b>로 온다(문자열 직렬화기는 Outbox 전용이다).
 * 화면과 폼은 금액을 문자열로 다루므로, 숫자가 그대로 새어 들어가면 문자열을 기대하는 검증에서
 * 터진다 — 실제 기동 검증에서 '변경' 버튼이 `e.trim is not a function` 으로 화면을 하얗게 만들었다.
 * 타입 선언을 `string` 으로 적어 두는 것만으로는 아무것도 보장되지 않는다는 사실을 이 테스트가 지킨다.
 */
vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), put: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

describe('shippingPolicyApi — 경계에서 금액을 문자열로 고정한다', () => {
  it('list: 서버가 숫자로 준 금액을 문자열로 바꿔 돌려준다', async () => {
    mocked.get.mockResolvedValue({
      data: [
        { sellerId: 2, baseFee: 3000.0, freeThreshold: 50000.0 },
        { sellerId: 8, baseFee: 2500.0, freeThreshold: null },
      ],
    });

    const result = await shippingPolicyApi.list();

    expect(result).toEqual([
      { sellerId: 2, baseFee: '3000', freeThreshold: '50000' },
      { sellerId: 8, baseFee: '2500', freeThreshold: null },
    ]);
    // 폼 검증이 trim() 을 부를 수 있어야 한다 — 이게 깨지면 '변경' 이 화면을 죽인다.
    expect(typeof result[0].baseFee).toBe('string');
    expect(typeof result[0].freeThreshold).toBe('string');
  });

  it('list: 임계 0 은 null 로 뭉개지 않는다 — 0 은 "항상 무료" 라는 뜻이다', async () => {
    mocked.get.mockResolvedValue({ data: [{ sellerId: 9, baseFee: 4000, freeThreshold: 0 }] });

    const [policy] = await shippingPolicyApi.list();

    expect(policy.freeThreshold).toBe('0');
    expect(describeThreshold(policy.freeThreshold)).toBe('항상 무료');
  });

  it('upsert: 응답도 같은 정규화를 거친다(저장 직후 값이 폼으로 되돌아가므로)', async () => {
    mocked.put.mockResolvedValue({ data: { sellerId: 7, baseFee: 3000, freeThreshold: null } });

    const saved = await shippingPolicyApi.upsert(7, { baseFee: '3000', freeThreshold: null });

    expect(saved).toEqual({ sellerId: 7, baseFee: '3000', freeThreshold: null });
    expect(mocked.put).toHaveBeenCalledWith('/admin/shipping-policies/7', {
      baseFee: '3000',
      freeThreshold: null,
    });
  });

  it('get: 단건도 정규화된다', async () => {
    mocked.get.mockResolvedValue({ data: { sellerId: 3, baseFee: 4000, freeThreshold: 60000 } });

    await expect(shippingPolicyApi.get(3)).resolves.toEqual({
      sellerId: 3,
      baseFee: '4000',
      freeThreshold: '60000',
    });
  });
});

describe('표시 함수', () => {
  it('formatWon 은 천 단위로 끊는다', () => {
    expect(formatWon('50000')).toBe('50,000원');
    expect(formatWon('0')).toBe('0원');
  });

  it('describeThreshold 는 null 과 0 을 반대 문장으로 구분한다', () => {
    expect(describeThreshold(null)).toBe('무료배송 없음');
    expect(describeThreshold('0')).toBe('항상 무료');
    expect(describeThreshold('30000')).toBe('30,000원 이상 무료');
  });
});
