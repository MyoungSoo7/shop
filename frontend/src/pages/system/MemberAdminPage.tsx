import { useCallback, useEffect, useState } from 'react';
import {
  memberApi,
  type MemberEnums,
  type MemberPage,
  type MemberQuery,
  type MemberStatusCount,
  type MemberSummary,
} from '@/api/member';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 회원 관리 콘솔.
 *
 * <p><b>왜 이 화면이 필요한가</b>: 승인·반려·정지·복구 조작은 이미 있었지만 대상을 <b>찾는</b>
 * 방법이 없었다. 목록이라곤 전 회원 무페이징 API 와 승인 대기 목록뿐이라, "정지된 그 사람"이나
 * "이 번호로 가입한 사람"을 화면에서 찾을 수 없었다.
 *
 * <p><b>가입일 기본값을 두지 않는다</b>: 운영자가 찾는 회원은 대개 언제 가입했는지 모르는
 * 사람이다. "최근 30일"을 기본으로 깔면 3년 전 가입자를 찾을 때마다 빈 결과가 나오고,
 * 운영자는 그 이유를 알 수 없다.
 *
 * <p><b>CSV 내보내기가 감사에 남는다는 사실을 숨기지 않는다</b>: 개인정보를 파일로 가져가는
 * 조작이라 서버가 기록한다. 그것을 화면이 알려 주지 않으면 운영자는 나중에 감사 로그에서
 * 자기 이름을 보고 놀란다.
 */

const PAGE_SIZE = 50;

const STATUS_LABEL: Record<string, string> = {
  PENDING: '승인 대기',
  APPROVED: '승인',
  REJECTED: '반려',
  SUSPENDED: '정지',
};

const ACTIVE_OPTIONS = [
  { value: '', label: '전체(탈퇴 포함)' },
  { value: 'true', label: '활성' },
  { value: 'false', label: '비활성(탈퇴)' },
];

export default function MemberAdminPage() {
  const [keyword, setKeyword] = useState('');
  const [role, setRole] = useState('');
  const [status, setStatus] = useState('');
  const [active, setActive] = useState('');
  const [joinedFrom, setJoinedFrom] = useState('');
  const [joinedTo, setJoinedTo] = useState('');
  const [page, setPage] = useState(0);

  const [enums, setEnums] = useState<MemberEnums>({ roles: [], membershipStatuses: [] });
  const [result, setResult] = useState<MemberPage | null>(null);
  const [counts, setCounts] = useState<MemberStatusCount[]>([]);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const query = useCallback(
    (withPaging: boolean): MemberQuery => ({
      keyword: keyword.trim() || undefined,
      role: role || undefined,
      status: status || undefined,
      active: active === '' ? undefined : active === 'true',
      joinedFrom: joinedFrom || undefined,
      joinedTo: joinedTo || undefined,
      ...(withPaging ? { page, size: PAGE_SIZE } : {}),
    }),
    [keyword, role, status, active, joinedFrom, joinedTo, page],
  );

  useEffect(() => {
    let cancelled = false;
    void memberApi
      .enums()
      .then(value => {
        if (!cancelled) setEnums(value);
      })
      .catch(() => {
        if (!cancelled) setEnums({ roles: [], membershipStatuses: [] });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const [pageResult, statusCounts] = await Promise.all([
        memberApi.search(query(true)),
        // 상태별 집계에 상태 필터를 실으면 고른 상태 하나만 남아 집계의 의미가 사라진다.
        // 서버도 이 값을 버리지만, 보내지 않는 편이 화면의 의도를 정확히 말한다.
        memberApi.statusCounts({ ...query(false), status: undefined }),
      ]);
      setResult(pageResult);
      setCounts(statusCounts);
    } catch (err) {
      setError(apiErrorMessage(err, '회원 목록을 불러오지 못했습니다.'));
      setResult(null);
      setCounts([]);
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const search = () => {
    if (page === 0) {
      void load();
    } else {
      setPage(0);
    }
  };

  /**
   * 상태 전이·역할 변경 후 목록을 다시 읽는다.
   *
   * <p>낙관적 갱신을 하지 않는 이유: 이 화면의 값들은 서버 상태머신이 정하고, 전이가 거부될
   * 수도 있다. 화면이 먼저 바꿔 놓으면 실패한 전이가 성공처럼 보인다.
   */
  const runAction = async (userId: number, action: () => Promise<unknown>, successMessage: string) => {
    setError(null);
    setNotice(null);
    setBusyId(userId);
    try {
      await action();
      setNotice(successMessage);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '처리에 실패했습니다.'));
    } finally {
      setBusyId(null);
    }
  };

  const approve = (member: MemberSummary) =>
    runAction(member.id, () => memberApi.approve(member.id), `${member.email} 승인 처리했습니다.`);

  const reject = (member: MemberSummary) => {
    const reason = window.prompt('반려 사유를 입력하세요.');
    if (!reason?.trim()) return;
    void runAction(member.id, () => memberApi.reject(member.id, reason.trim()),
      `${member.email} 반려 처리했습니다.`);
  };

  const suspend = (member: MemberSummary) => {
    const reason = window.prompt('정지 사유를 입력하세요.');
    if (!reason?.trim()) return;
    void runAction(member.id, () => memberApi.suspend(member.id, reason.trim()),
      `${member.email} 정지 처리했습니다.`);
  };

  const reinstate = (member: MemberSummary) =>
    runAction(member.id, () => memberApi.reinstate(member.id), `${member.email} 정지를 해제했습니다.`);

  const changeRole = (member: MemberSummary) => {
    const next = window.prompt(
      `바꿀 역할을 입력하세요 (${enums.roles.join(' / ')})`, member.role);
    if (!next?.trim()) return;
    const reason = window.prompt('역할 변경 사유를 입력하세요. 근거 없는 권한 변경은 감사에서 설명되지 않습니다.');
    if (!reason?.trim()) return;
    void runAction(member.id,
      () => memberApi.changeRole(member.id, next.trim().toUpperCase(), reason.trim()),
      `${member.email} 역할을 ${next.trim().toUpperCase()} 로 바꿨습니다.`);
  };

  const download = async () => {
    setNotice(null);
    setError(null);
    try {
      const exported = await memberApi.export(query(false));
      saveBlob(exported.blob, exported.fileName);
      setNotice(
        exported.truncated
          ? `내려받은 CSV 는 조건에 맞는 ${exported.total.toLocaleString()}명 중 앞 5,000명만 담고 있습니다. 조건을 좁혀 다시 받으세요. (이 내보내기는 감사 로그에 남았습니다)`
          : `${exported.total.toLocaleString()}명을 내려받았습니다. 이 내보내기는 감사 로그에 남았습니다.`,
      );
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 내려받기에 실패했습니다.'));
    }
  };

  const totalPages = result?.totalPages ?? 0;

  return (
    <main className="space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">회원 관리</h1>
        <p className="text-sm text-gray-500">
          회원을 찾아 승인 상태와 역할을 관리합니다. 개인정보를 다루는 화면이며 내보내기는 감사 로그에 남습니다.
        </p>
      </header>

      <section className="grid gap-3 rounded border p-4 sm:grid-cols-3">
        <label className="flex flex-col gap-1 sm:col-span-3">
          <span className="text-sm">검색어</span>
          <input aria-label="검색어" value={keyword} onChange={e => setKeyword(e.target.value)}
            placeholder="이메일 · 이름 · 연락처 일부" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">역할</span>
          <select aria-label="역할" value={role} onChange={e => setRole(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {enums.roles.map(name => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">승인 상태</span>
          <select aria-label="승인 상태" value={status} onChange={e => setStatus(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {enums.membershipStatuses.map(name => (
              <option key={name} value={name}>{STATUS_LABEL[name] ?? name}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">계정 상태</span>
          <select aria-label="계정 상태" value={active} onChange={e => setActive(e.target.value)}
            className="rounded border px-3 py-2">
            {ACTIVE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">가입 시작일</span>
          <input aria-label="가입 시작일" type="date" value={joinedFrom}
            onChange={e => setJoinedFrom(e.target.value)} className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">가입 종료일</span>
          <input aria-label="가입 종료일" type="date" value={joinedTo}
            onChange={e => setJoinedTo(e.target.value)} className="rounded border px-3 py-2" />
        </label>

        <div className="flex items-end gap-2">
          <button type="button" onClick={search} disabled={loading}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
            {loading ? '조회 중…' : '조회'}
          </button>
          <button type="button" onClick={() => void download()} disabled={loading}
            className="rounded bg-slate-700 px-4 py-2 text-white disabled:opacity-50">
            CSV 내려받기
          </button>
        </div>
      </section>

      {error && <p role="alert" className="text-red-600">{error}</p>}
      {notice && <p role="status" className="rounded bg-amber-50 p-3 text-amber-800">{notice}</p>}

      {counts.length > 0 && (
        <section aria-label="승인 상태별 인원" className="flex flex-wrap gap-2">
          {counts.map(item => (
            <button key={item.membershipStatus} type="button"
              onClick={() => { setStatus(item.membershipStatus); setPage(0); }}
              className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700 hover:bg-gray-200">
              {STATUS_LABEL[item.membershipStatus] ?? item.membershipStatus}{' '}
              <b>{item.count.toLocaleString()}</b>
            </button>
          ))}
        </section>
      )}

      <section>
        <p className="mb-2 text-sm text-gray-600">
          총 {(result?.totalElements ?? 0).toLocaleString()}명
        </p>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-3 py-2">이메일</th>
                <th className="px-3 py-2">이름</th>
                <th className="px-3 py-2">연락처</th>
                <th className="px-3 py-2">역할</th>
                <th className="px-3 py-2">승인 상태</th>
                <th className="px-3 py-2">계정</th>
                <th className="px-3 py-2">가입일</th>
                <th className="px-3 py-2">조작</th>
              </tr>
            </thead>
            <tbody>
              {(result?.content ?? []).map(member => (
                <tr key={member.id} className="border-t">
                  <td className="px-3 py-2">{member.email}</td>
                  <td className="px-3 py-2">{member.name ?? '—'}</td>
                  <td className="whitespace-nowrap px-3 py-2">{member.phoneNumber ?? '—'}</td>
                  <td className="px-3 py-2">{member.role}</td>
                  <td className="px-3 py-2">{STATUS_LABEL[member.membershipStatus] ?? member.membershipStatus}</td>
                  <td className="px-3 py-2">{member.active ? '활성' : '비활성'}</td>
                  <td className="whitespace-nowrap px-3 py-2">{member.createdAt.slice(0, 10)}</td>
                  <td className="space-x-1 px-3 py-2">
                    {member.membershipStatus === 'PENDING' && (
                      <>
                        <button type="button" disabled={busyId === member.id}
                          onClick={() => void approve(member)}
                          className="rounded bg-green-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                          승인
                        </button>
                        <button type="button" disabled={busyId === member.id}
                          onClick={() => reject(member)}
                          className="rounded bg-gray-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                          반려
                        </button>
                      </>
                    )}
                    {member.membershipStatus === 'APPROVED' && (
                      <button type="button" disabled={busyId === member.id}
                        onClick={() => suspend(member)}
                        className="rounded bg-red-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                        정지
                      </button>
                    )}
                    {member.membershipStatus === 'SUSPENDED' && (
                      <button type="button" disabled={busyId === member.id}
                        onClick={() => void reinstate(member)}
                        className="rounded bg-blue-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                        정지 해제
                      </button>
                    )}
                    <button type="button" disabled={busyId === member.id}
                      onClick={() => changeRole(member)}
                      className="rounded border px-2 py-1 text-xs disabled:opacity-50">
                      역할 변경
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!loading && (result?.content.length ?? 0) === 0 && (
          <p className="py-6 text-center text-gray-500">조건에 맞는 회원이 없습니다.</p>
        )}
      </section>

      {totalPages > 1 && (
        <nav aria-label="페이지" className="flex items-center gap-3">
          <button type="button" onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0 || loading} className="rounded border px-3 py-1 disabled:opacity-40">
            이전
          </button>
          <span className="text-sm">{page + 1} / {totalPages}</span>
          <button type="button" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1 || loading}
            className="rounded border px-3 py-1 disabled:opacity-40">
            다음
          </button>
        </nav>
      )}
    </main>
  );
}
