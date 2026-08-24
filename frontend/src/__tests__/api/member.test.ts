import { describe, it, expect, vi, beforeEach } from 'vitest';
import { memberApi } from '@/api/member';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 회원 관리 API 클라이언트 계약.
 *
 * <p>가장 중요한 것은 <b>표면이 둘로 나뉜 이유</b>가 코드에 남아 있는가다. 조회·역할변경은
 * {@code /admin/members}, 승인·정지는 기존 {@code /memberships} 다 — 후자는 membership_approvals
 * 이력까지 남기는 서비스가 이미 소유하고 있어 옮기지 않았다. 나중에 "왜 둘이지?" 하며 한쪽으로
 * 합치면 이력이 갈라진다.
 */
describe('memberApi — 조회', () => {
  it('검색은 /admin/members 로 간다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await memberApi.search({ keyword: '홍', page: 0, size: 50 });

    expect(mocked.get).toHaveBeenCalledWith('/admin/members', {
      params: { keyword: '홍', page: 0, size: 50 },
    });
  });

  it('빈 문자열 필터는 보내지 않는다 — 서버가 "빈 문자열과 일치"로 읽으면 결과가 사라진다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await memberApi.search({ keyword: '', role: '', status: 'SUSPENDED' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/members', { params: { status: 'SUSPENDED' } });
  });

  it('active=false 는 살아남는다 — falsy 로 걷어내면 탈퇴 회원을 영영 못 찾는다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await memberApi.search({ active: false });

    expect(mocked.get).toHaveBeenCalledWith('/admin/members', { params: { active: false } });
  });

  it('상태별 인원과 enum 목록은 각자의 경로를 쓴다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await memberApi.statusCounts({ keyword: '홍' });
    expect(mocked.get).toHaveBeenCalledWith('/admin/members/status-counts', {
      params: { keyword: '홍' },
    });

    await memberApi.enums();
    expect(mocked.get).toHaveBeenCalledWith('/admin/members/enums');
  });
});

describe('memberApi — 역할 변경', () => {
  it('PATCH 로 역할과 사유를 함께 보낸다 — 사유 없는 권한 변경은 감사에서 설명되지 않는다', async () => {
    mocked.patch.mockResolvedValue({ data: {} } as never);

    await memberApi.changeRole(42, 'MANAGER', 'CS 팀 배치');

    expect(mocked.patch).toHaveBeenCalledWith('/admin/members/42/role', {
      role: 'MANAGER',
      reason: 'CS 팀 배치',
    });
  });
});

describe('memberApi — 승인 워크플로는 기존 표면을 쓴다', () => {
  it('승인·복구는 사유 없이, 반려·정지는 사유와 함께 /memberships 로 간다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await memberApi.approve(42);
    expect(mocked.post).toHaveBeenCalledWith('/memberships/42/approve');

    await memberApi.reinstate(42);
    expect(mocked.post).toHaveBeenCalledWith('/memberships/42/reinstate');

    await memberApi.reject(42, '서류 미비');
    expect(mocked.post).toHaveBeenCalledWith('/memberships/42/reject', { reason: '서류 미비' });

    await memberApi.suspend(42, '약관 위반');
    expect(mocked.post).toHaveBeenCalledWith('/memberships/42/suspend', { reason: '약관 위반' });
  });
});

describe('memberApi — 내보내기', () => {
  it('blob 으로 받고 잘림·총원을 헤더에서 읽는다', async () => {
    mocked.get.mockResolvedValue({
      data: new Blob(['x']),
      headers: {
        'content-disposition': 'attachment; filename="members_2026-03-02.csv"',
        'x-export-truncated': 'true',
        'x-export-total': '12345',
      },
    } as never);

    const result = await memberApi.export({ status: 'APPROVED' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/members/export', {
      params: { status: 'APPROVED' },
      responseType: 'blob',
    });
    expect(result.fileName).toBe('members_2026-03-02.csv');
    expect(result.truncated).toBe(true);
    expect(result.total).toBe(12345);
  });

  it('헤더가 없으면 기본 파일명으로 떨어진다', async () => {
    mocked.get.mockResolvedValue({ data: new Blob(['x']), headers: {} } as never);

    const result = await memberApi.export({});

    expect(result.fileName).toBe('members.csv');
    expect(result.truncated).toBe(false);
  });
});
