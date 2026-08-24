import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError } from 'axios';
import { organizationApi, LastOwnerError } from '@/api/organization';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const mocked = vi.mocked(api);

const httpError = (status: number, message?: string) => {
  const error = new AxiosError('boom');
  error.response = {
    status, data: message ? { status, message } : null,
    statusText: '', headers: {}, config: {} as never,
  };
  return error;
};

beforeEach(() => vi.clearAllMocks());

/**
 * 조직 API 계약.
 *
 * <p>고정하는 것 셋. ① <b>수락에 사용자 식별자를 싣지 않는다</b> — 서버가 호출자 자신의 초대만
 * 수락하므로, 대상을 실을 자리가 있으면 그게 곧 "남을 대신 수락" 경로가 된다.
 * ② <b>422 는 실패가 아니라 불변식</b>(활성 OWNER ≥ 1)이다 — 갈라 두지 않으면 화면이
 * "실패했습니다"로 뭉개고 운영자는 재시도만 반복한다.
 * ③ 404 만 null 로 접는다 — 403(권한 없음)이 "없는 조직"으로 위장하면 안 된다.
 */
describe('organizationApi — 조회·생성', () => {
  it('없는 조직은 null', async () => {
    mocked.get.mockRejectedValue(httpError(404));
    await expect(organizationApi.detail(7)).resolves.toBeNull();
  });

  it('403 은 삼키지 않는다', async () => {
    mocked.get.mockRejectedValue(httpError(403));
    await expect(organizationApi.detail(7)).rejects.toBeInstanceOf(AxiosError);
  });

  it('생성에 소유자를 싣지 않는다 — 서버가 호출자에서 파생한다', async () => {
    mocked.post.mockResolvedValue({ data: { id: 1 } } as never);

    await organizationApi.create({ name: '가나상사', type: 'SELLER' });

    expect(mocked.post).toHaveBeenCalledWith('/api/organizations',
      { name: '가나상사', type: 'SELLER' });
    const [, body] = mocked.post.mock.calls[0];
    expect(body).not.toHaveProperty('ownerUserId');
  });
});

describe('organizationApi — 멤버십', () => {
  it('수락은 대상 식별자를 싣지 않는다 (본인 것만 수락된다)', async () => {
    mocked.post.mockResolvedValue({ data: { userId: 9 } } as never);

    await organizationApi.acceptOwnInvite(3);

    expect(mocked.post).toHaveBeenCalledWith('/api/organizations/3/members/accept');
    // 인자가 URL 하나뿐 = 본문이 없다. 본문이 생기면 "남을 대신 수락"이 가능해 보인다.
    expect(mocked.post.mock.calls[0]).toHaveLength(1);
  });

  it('초대는 대상과 역할을 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await organizationApi.invite(3, 11, 'MANAGER');

    expect(mocked.post).toHaveBeenCalledWith('/api/organizations/3/members',
      { targetUserId: 11, role: 'MANAGER' });
  });

  it('역할 변경은 PATCH 다', async () => {
    mocked.patch.mockResolvedValue({ data: {} } as never);

    await organizationApi.changeRole(3, 11, 'STAFF');

    expect(mocked.patch).toHaveBeenCalledWith('/api/organizations/3/members/11/role',
      { newRole: 'STAFF' });
  });

  it('422 는 LastOwnerError 로 갈리고 서버 문구를 보존한다', async () => {
    mocked.patch.mockRejectedValue(httpError(422, '마지막 OWNER 는 강등할 수 없습니다'));

    await expect(organizationApi.changeRole(3, 11, 'STAFF'))
      .rejects.toThrow('마지막 OWNER 는 강등할 수 없습니다');
    await expect(organizationApi.changeRole(3, 11, 'STAFF'))
      .rejects.toBeInstanceOf(LastOwnerError);
  });

  it('422 에 문구가 없으면 기본 설명을 쓴다', async () => {
    mocked.delete.mockRejectedValue(httpError(422));
    await expect(organizationApi.remove(3, 11)).rejects.toThrow('활성 OWNER 는 최소 1명');
  });

  it('409(중복 멤버십)는 LastOwnerError 가 아니다', async () => {
    mocked.post.mockRejectedValue(httpError(409, '이미 활성 멤버입니다'));
    await expect(organizationApi.invite(3, 11, 'STAFF')).rejects.toBeInstanceOf(AxiosError);
  });
});
