import React, { useCallback, useEffect, useState } from 'react';
import {
  operatorAdminApi,
  type OperatorQuery,
  type OperatorPage,
  type OperatorSummary,
} from '@/api/operatorAdmin';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 권한 계정 콘솔 — <b>조작할 수 있는 사람</b>의 명부.
 *
 * <p>회원 관리(/admin/system/members)와 나눈 이유는 위험의 성격이 다르기 때문이다. 방치된 일반
 * 회원은 아무 일도 일으키지 않지만, 방치된 ADMIN 계정은 남의 손에 들어가는 순간 전 권한이다.
 * 그래서 이 화면의 기본 정렬은 <b>마지막 로그인이 오래된 순</b>이고, 검색창이 아니라 회수 후보
 * 필터(오래 안 씀 · 로그인한 적 없음)가 먼저 온다 — 검색을 먼저 두면 "이미 아는 계정"만 확인하게 된다.
 *
 * <p><b>"오래 안 씀"과 "쓴 적 없음"을 한 필터로 합치지 않았다.</b> 후자는 발급만 하고 아무도
 * 받아가지 않은 계정이라 회수에 망설일 이유가 거의 없다. 전자는 사람이 아직 있는데 안 쓰는
 * 것일 수 있어 판단이 필요하다. 합치면 그 판단이 사라진다.
 *
 * <p><b>잠금 해제에는 사유가 필수다.</b> 잠금은 연속 로그인 실패로 걸리므로, 푸는 행위는
 * 공격 시도를 무효화하는 것일 수도 본인의 실수를 풀어 주는 것일 수도 있다. 둘을 나중에
 * 구분할 유일한 근거가 그때 적은 사유다 — 서버가 감사 로그에 남긴다.
 */

const PAGE_SIZE = 50;

const fmtDate = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }) : '-';

const ROLES = ['', 'ADMIN', 'MANAGER'];

const OperatorAdminPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [role, setRole] = useState('');
  const [lockedOnly, setLockedOnly] = useState(false);
  const [idleDays, setIdleDays] = useState('');
  const [neverLoggedIn, setNeverLoggedIn] = useState(false);
  const [page, setPage] = useState(0);

  const [result, setResult] = useState<OperatorPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [unlocking, setUnlocking] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const query = useCallback((): OperatorQuery => ({
    keyword: keyword.trim() || undefined,
    role: role || undefined,
    lockedOnly,
    idleDays: idleDays === '' ? undefined : Number(idleDays),
    neverLoggedIn,
    page,
    size: PAGE_SIZE,
  }), [keyword, role, lockedOnly, idleDays, neverLoggedIn, page]);

  const load = useCallback(async () => {
    setError(null);
    try {
      setResult(await operatorAdminApi.search(query()));
    } catch (err) {
      // 빈 표를 그리면 조회 실패가 "권한 계정 0건"으로 위장한다 — 그 둘을 뭉개지 않는다.
      setResult(null);
      setError(apiErrorMessage(err, '권한 계정을 불러오지 못했습니다.'));
    }
  }, [query]);

  useEffect(() => { void load(); }, [load]);

  const search = (event: React.FormEvent) => {
    event.preventDefault();
    setPage(0);   // 조건이 바뀌면 페이지도 처음으로 — 안 그러면 빈 3페이지를 보고 "없다"고 읽는다
    void load();
  };

  const download = async () => {
    setError(null);
    try {
      const { blob, fileName, truncated, total } = await operatorAdminApi.export(query());
      saveBlob(blob, fileName);
      setNotice(truncated
        ? `내려받았습니다 — 전체 ${total}건 중 일부만 담겼습니다(서버 상한).`
        : `내려받았습니다 — ${total}건.`);
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 를 내려받지 못했습니다.'));
    }
  };

  const submitUnlock = async (userId: number) => {
    setError(null);
    try {
      const unlocked = await operatorAdminApi.unlock(userId, reason.trim());
      // 목록을 열어 둔 사이 시간이 지나 저절로 풀렸을 수 있다. 그때 "해제했습니다"라고만 하면
      // 하지도 않은 일을 했다고 보고하는 셈이다.
      setNotice(unlocked.wasLocked
        ? `${unlocked.email} 의 잠금을 해제했습니다(직전 연속 실패 ${unlocked.previousFailedAttempts}회).`
        : `${unlocked.email} 은 이미 잠겨 있지 않았습니다 — 해제할 것이 없었습니다.`);
      setUnlocking(null);
      setReason('');
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '잠금을 해제하지 못했습니다.'));
    }
  };

  const rows: OperatorSummary[] = result?.content ?? [];

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">권한 계정</h1>
          <p className="text-sm text-gray-500 mt-1">
            시스템을 <b>조작할 수 있는</b> 계정입니다. 기본 정렬은 마지막 로그인이 오래된 순 —
            안 쓰는 계정이 먼저 보여야 회수할 대상을 고를 수 있습니다.
          </p>
        </div>

        <form onSubmit={search} className="flex flex-wrap items-end gap-3 rounded bg-white p-4 shadow-sm">
          <label className="text-sm">
            <span className="block text-gray-600">이메일 · 이름</span>
            <input value={keyword} onChange={(e) => setKeyword(e.target.value)}
              className="mt-1 rounded border border-gray-300 px-2 py-1" placeholder="검색어" />
          </label>
          <label className="text-sm">
            <span className="block text-gray-600">역할</span>
            <select value={role} onChange={(e) => setRole(e.target.value)}
              className="mt-1 rounded border border-gray-300 px-2 py-1">
              {ROLES.map((r) => <option key={r} value={r}>{r === '' ? '전체' : r}</option>)}
            </select>
          </label>
          <label className="text-sm">
            <span className="block text-gray-600">N일 이상 미사용</span>
            <input type="number" min={1} value={idleDays} onChange={(e) => setIdleDays(e.target.value)}
              className="mt-1 w-28 rounded border border-gray-300 px-2 py-1" placeholder="예: 90" />
          </label>
          {/* "쓴 적 없음"은 idleDays 로 잡히지 않는 부류다 — 마지막 로그인이 아예 없기 때문이다. */}
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={neverLoggedIn}
              onChange={(e) => setNeverLoggedIn(e.target.checked)} />
            로그인한 적 없음
          </label>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={lockedOnly}
              onChange={(e) => setLockedOnly(e.target.checked)} />
            잠긴 계정만
          </label>
          <button type="submit"
            className="rounded bg-gray-900 px-3 py-2 text-sm font-semibold text-white">조회</button>
          <button type="button" onClick={() => void download()}
            className="rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
            CSV
          </button>
        </form>

        {/* 감사 기록이 남는다는 사실을 내려받기 전에 알린다 — 권한 계정 명부다. */}
        <p className="text-xs text-gray-500">CSV 내려받기는 감사 로그에 기록됩니다.</p>

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        {notice && <p className="text-sm text-green-700" data-testid="operator-notice">{notice}</p>}

        {result === null ? (
          !error && <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-gray-600" data-testid="operator-empty">
            조건에 맞는 권한 계정이 없습니다.
          </p>
        ) : (
          <>
            <p className="text-sm text-gray-600" data-testid="operator-total">
              전체 {result.totalElements}건 · {result.page + 1}/{Math.max(result.totalPages, 1)} 쪽
            </p>
            <table className="w-full text-sm" data-testid="operator-table">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">계정</th><th>역할</th><th>마지막 로그인</th>
                  <th>연속 실패</th><th>상태</th><th />
                </tr>
              </thead>
              <tbody>
                {rows.map((op) => (
                  <React.Fragment key={op.id}>
                    <tr className={`border-t ${op.locked ? 'bg-red-50' : ''}`}
                      data-testid={`operator-row-${op.id}`}>
                      <td className="py-2">
                        <div className="font-medium text-gray-900">{op.email}</div>
                        <div className="text-xs text-gray-500">{op.name}</div>
                      </td>
                      <td>{op.role}</td>
                      <td className="text-xs text-gray-600" data-testid={`last-login-${op.id}`}>
                        {/* "오래 안 썼다"와 "쓴 적이 없다"는 다른 상태다 — 같은 '-' 로 뭉개지 않는다. */}
                        {op.lastLoginAt === null ? '로그인한 적 없음' : fmtDate(op.lastLoginAt)}
                      </td>
                      <td>{op.failedLoginAttempts}</td>
                      <td>
                        {!op.active && (
                          <span className="mr-1 rounded bg-gray-600 px-2 py-0.5 text-xs text-white">비활성</span>
                        )}
                        {op.locked ? (
                          <span className="rounded bg-red-600 px-2 py-0.5 text-xs text-white"
                            data-testid={`locked-${op.id}`}>
                            잠김 · {fmtDate(op.lockedUntil)} 까지
                          </span>
                        ) : (
                          <span className="text-xs text-gray-500">정상</span>
                        )}
                      </td>
                      <td className="text-right">
                        {op.locked && (
                          <button type="button"
                            onClick={() => { setUnlocking(op.id); setReason(''); }}
                            className="rounded border border-gray-300 bg-white px-2 py-1 text-xs font-semibold text-gray-700">
                            잠금 해제
                          </button>
                        )}
                      </td>
                    </tr>

                    {unlocking === op.id && (
                      <tr className="border-t bg-gray-50" data-testid={`unlock-form-${op.id}`}>
                        <td colSpan={6} className="p-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <label className="text-sm text-gray-700">
                              사유
                              <input value={reason} onChange={(e) => setReason(e.target.value)}
                                aria-label="잠금 해제 사유"
                                className="ml-2 w-80 rounded border border-gray-300 px-2 py-1"
                                placeholder="예: 본인 확인 후 비밀번호 재설정 안내" />
                            </label>
                            {/* 사유가 비면 서버가 400 을 준다. 그 왕복을 기다리게 하지 않고 여기서 막는다. */}
                            <button type="button" disabled={reason.trim() === ''}
                              onClick={() => void submitUnlock(op.id)}
                              className="rounded bg-gray-900 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-40">
                              해제
                            </button>
                            <button type="button" onClick={() => setUnlocking(null)}
                              className="rounded border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700">
                              취소
                            </button>
                          </div>
                          <p className="mt-2 text-xs text-gray-500">
                            사유는 감사 로그에 그대로 남습니다. 잠금은 연속 로그인 실패로 걸리므로,
                            공격 시도인지 본인의 실수인지 나중에 구분할 근거가 이 문장뿐입니다.
                          </p>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>

            <div className="flex items-center gap-2">
              <button type="button" disabled={result.page <= 0}
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
                className="rounded border border-gray-300 bg-white px-3 py-1.5 text-sm disabled:opacity-40">
                이전
              </button>
              <button type="button" disabled={result.page + 1 >= result.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded border border-gray-300 bg-white px-3 py-1.5 text-sm disabled:opacity-40">
                다음
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default OperatorAdminPage;
