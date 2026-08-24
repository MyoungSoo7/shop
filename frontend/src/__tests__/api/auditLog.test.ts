import { describe, it, expect, vi, beforeEach } from 'vitest';
import { auditLogApi, saveBlob } from '@/api/auditLog';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 감사 로그 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것 셋 —
 *
 * <ul>
 *   <li><b>표면이 둘</b>이라는 사실. 커머스와 정산의 감사 테이블은 서비스마다 자기 DB 에 따로
 *       있어 경로가 다르다. 하나로 합치면 게이트웨이가 한쪽으로만 보내 나머지가 도달 불가가 된다.
 *   <li><b>빈 값은 보내지 않는다.</b> 빈 문자열을 실어 보내면 서버가 "빈 문자열과 일치"로 읽어
 *       결과가 통째로 사라진다 — 화면은 조용히 빈 표를 그린다.
 *   <li><b>잘림 여부는 헤더에서 읽는다.</b> 본문에 경고 행을 끼우면 그 행이 데이터로 세어진다.
 * </ul>
 */
describe('auditLogApi — 표면 분기', () => {
  it('커머스는 /admin/audit-logs 로 간다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await auditLogApi.search('COMMERCE', { from: '2026-03-01', to: '2026-03-31' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-logs', {
      params: { from: '2026-03-01', to: '2026-03-31' },
    });
  });

  it('정산은 /admin/audit-trail 로 간다 — 테이블이 서비스마다 따로라 경로도 둘이다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await auditLogApi.search('SETTLEMENT', { from: '2026-03-01' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-trail', {
      params: { from: '2026-03-01' },
    });
  });

  it('액션별 건수·액션 목록도 고른 표면을 따른다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await auditLogApi.actionCounts('SETTLEMENT', {});
    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-trail/action-counts', { params: {} });

    await auditLogApi.actions('COMMERCE');
    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-logs/actions');
  });
});

describe('auditLogApi — 파라미터 정규화', () => {
  it('빈 문자열·undefined·null 은 아예 보내지 않는다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await auditLogApi.search('COMMERCE', {
      actorEmail: '',
      action: undefined,
      resourceType: 'PAYOUT',
      page: 0,
    });

    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-logs', {
      params: { resourceType: 'PAYOUT', page: 0 },
    });
  });

  it('page 0 은 유효한 값이라 살아남는다 — falsy 로 걷어내면 첫 페이지를 못 부른다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await auditLogApi.search('COMMERCE', { page: 0, size: 50 });

    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-logs', { params: { page: 0, size: 50 } });
  });
});

describe('auditLogApi — 내보내기', () => {
  it('blob 으로 받고 파일명·잘림·총건수를 헤더에서 읽는다', async () => {
    mocked.get.mockResolvedValue({
      data: new Blob(['x']),
      headers: {
        'content-disposition': 'attachment; filename="audit-logs_2026-03-02.csv"',
        'x-export-truncated': 'true',
        'x-export-total': '12345',
      },
    } as never);

    const result = await auditLogApi.export('COMMERCE', { from: '2026-03-01' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/audit-logs/export', {
      params: { from: '2026-03-01' },
      responseType: 'blob',
    });
    expect(result.fileName).toBe('audit-logs_2026-03-02.csv');
    expect(result.truncated).toBe(true);
    expect(result.total).toBe(12345);
  });

  it('RFC 5987 파일명(UTF-8 인코딩)도 해독한다 — 한글 파일명이 %EA%B0%90… 로 온다', async () => {
    mocked.get.mockResolvedValue({
      data: new Blob(['x']),
      headers: {
        'content-disposition': "attachment; filename*=UTF-8''%EA%B0%90%EC%82%AC.csv",
      },
    } as never);

    const result = await auditLogApi.export('COMMERCE', {});

    expect(result.fileName).toBe('감사.csv');
  });

  it('헤더가 없으면 기본 파일명으로 떨어지고 잘리지 않은 것으로 본다', async () => {
    mocked.get.mockResolvedValue({ data: new Blob(['x']), headers: {} } as never);

    const result = await auditLogApi.export('SETTLEMENT', {});

    expect(result.fileName).toBe('audit-logs.csv');
    expect(result.truncated).toBe(false);
    expect(result.total).toBe(0);
  });
});

describe('saveBlob', () => {
  it('오브젝트 URL 을 만들고 반드시 해제한다 — 안 하면 내려받은 CSV 가 브라우저에 쌓인다', () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:fake');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const click = vi.fn();
    const anchor = { href: '', download: '', click } as unknown as HTMLAnchorElement;
    vi.spyOn(document, 'createElement').mockReturnValue(anchor);
    const append = vi.spyOn(document.body, 'appendChild').mockImplementation(node => node);
    const remove = vi.spyOn(document.body, 'removeChild').mockImplementation(node => node);

    saveBlob(new Blob(['x']), 'audit.csv');

    expect(anchor.href).toBe('blob:fake');
    expect(anchor.download).toBe('audit.csv');
    expect(click).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:fake');

    append.mockRestore();
    remove.mockRestore();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
});
