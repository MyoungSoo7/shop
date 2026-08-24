import { useCallback, useEffect, useState } from 'react';
import {
  couponAdminApi,
  type CouponEnums,
  type CouponLifecycleCount,
  type CouponPage,
  type CouponQuery,
  type CouponRow,
  type CouponUsageRow,
} from '@/api/couponAdmin';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 쿠폰 운영 콘솔.
 *
 * <p><b>왜 필요한가</b>: 관리자 조회라곤 전체를 한 번에 내려주는 목록뿐이었고, 잘못 나간
 * 쿠폰을 멈추는 방법은 DB 직접 UPDATE 뿐이었다. 할인은 나가는 돈이라 멈추는 데 DBA 를
 * 기다려야 하는 상태 자체가 사고다.
 *
 * <p><b>"삭제" 대신 "중단"</b>: 이미 사용된 쿠폰을 지우면 사용 이력의 참조가 끊겨 그 할인이
 * 어디서 왔는지 설명할 수 없다. 화면에도 삭제 버튼을 두지 않는다.
 *
 * <p><b>소진율을 앞에 보여 주는 이유</b>: 운영자가 가장 먼저 묻는 것은 "이 쿠폰이 얼마나
 * 나갔나"다. 한도 0 은 <b>무제한</b>이라 그렇게 표기한다 — 0 을 "발급 불가"로 읽으면
 * 살아 있는 쿠폰을 죽은 것으로 오해한다.
 */

const PAGE_SIZE = 50;

const LIFECYCLE_LABEL: Record<string, string> = {
  ACTIVE: '사용 가능',
  SCHEDULED: '시작 전',
  EXPIRED: '기간 만료',
  EXHAUSTED: '한도 소진',
  INACTIVE: '중단됨',
};

export default function CouponAdminPage() {
  const [code, setCode] = useState('');
  const [lifecycle, setLifecycle] = useState('');
  const [type, setType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  const [enums, setEnums] = useState<CouponEnums>({ lifecycles: [], types: [] });
  const [result, setResult] = useState<CouponPage | null>(null);
  const [counts, setCounts] = useState<CouponLifecycleCount[]>([]);
  const [usages, setUsages] = useState<{ couponId: number; rows: CouponUsageRow[] } | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyCode, setBusyCode] = useState<string | null>(null);

  const query = useCallback(
    (withPaging: boolean): CouponQuery => ({
      code: code.trim() || undefined,
      lifecycle: lifecycle || undefined,
      type: type || undefined,
      from: from || undefined,
      to: to || undefined,
      ...(withPaging ? { page, size: PAGE_SIZE } : {}),
    }),
    [code, lifecycle, type, from, to, page],
  );

  useEffect(() => {
    let cancelled = false;
    void couponAdminApi
      .enums()
      .then(value => {
        if (!cancelled) setEnums(value);
      })
      .catch(() => {
        if (!cancelled) setEnums({ lifecycles: [], types: [] });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const [pageResult, lifecycleCounts] = await Promise.all([
        couponAdminApi.search(query(true)),
        // 상태별 집계에 상태 필터를 실으면 고른 상태 하나만 남아 집계가 무의미해진다.
        couponAdminApi.lifecycleCounts({ ...query(false), lifecycle: undefined }),
      ]);
      setResult(pageResult);
      setCounts(lifecycleCounts);
      setUsages(null);
    } catch (err) {
      setError(apiErrorMessage(err, '쿠폰 목록을 불러오지 못했습니다.'));
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

  const toggle = async (coupon: CouponRow) => {
    setError(null);
    setNotice(null);
    setBusyCode(coupon.code);
    try {
      if (coupon.active) {
        await couponAdminApi.deactivate(coupon.code);
        setNotice(`${coupon.code} 를 중단했습니다. 지금부터 사용할 수 없습니다.`);
      } else {
        await couponAdminApi.activate(coupon.code);
        setNotice(`${coupon.code} 를 재개했습니다. 기간·한도 조건은 그대로 적용됩니다.`);
      }
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '처리에 실패했습니다.'));
    } finally {
      setBusyCode(null);
    }
  };

  const showUsages = async (coupon: CouponRow) => {
    if (usages?.couponId === coupon.id) {
      setUsages(null);
      return;
    }
    setError(null);
    try {
      setUsages({ couponId: coupon.id, rows: await couponAdminApi.usages(coupon.id) });
    } catch (err) {
      setError(apiErrorMessage(err, '사용 내역을 불러오지 못했습니다.'));
    }
  };

  const download = async () => {
    setNotice(null);
    setError(null);
    try {
      const exported = await couponAdminApi.export(query(false));
      saveBlob(exported.blob, exported.fileName);
      setNotice(
        exported.truncated
          ? `내려받은 CSV 는 조건에 맞는 ${exported.total.toLocaleString()}장 중 앞 5,000장만 담고 있습니다. 조건을 좁혀 다시 받으세요.`
          : `${exported.total.toLocaleString()}장을 모두 내려받았습니다.`,
      );
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 내려받기에 실패했습니다.'));
    }
  };

  const totalPages = result?.totalPages ?? 0;

  return (
    <main className="space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">쿠폰 운영</h1>
        <p className="text-sm text-gray-500">
          발행된 쿠폰을 찾아 즉시 중단하거나 재개합니다. 중단은 삭제가 아니며 사용 이력은 보존됩니다.
        </p>
      </header>

      <section className="grid gap-3 rounded border p-4 sm:grid-cols-3">
        <label className="flex flex-col gap-1">
          <span className="text-sm">쿠폰 코드</span>
          <input aria-label="쿠폰 코드" value={code} onChange={e => setCode(e.target.value)}
            placeholder="코드 일부" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">상태</span>
          <select aria-label="상태" value={lifecycle} onChange={e => setLifecycle(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {enums.lifecycles.map(name => (
              <option key={name} value={name}>{LIFECYCLE_LABEL[name] ?? name}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">할인 유형</span>
          <select aria-label="할인 유형" value={type} onChange={e => setType(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {enums.types.map(name => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">생성 시작일</span>
          <input aria-label="생성 시작일" type="date" value={from} onChange={e => setFrom(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">생성 종료일</span>
          <input aria-label="생성 종료일" type="date" value={to} onChange={e => setTo(e.target.value)}
            className="rounded border px-3 py-2" />
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
        <section aria-label="상태별 장수" className="flex flex-wrap gap-2">
          {counts.map(item => (
            <button key={item.lifecycle} type="button"
              onClick={() => { setLifecycle(item.lifecycle); setPage(0); }}
              className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700 hover:bg-gray-200">
              {LIFECYCLE_LABEL[item.lifecycle] ?? item.lifecycle} <b>{item.count.toLocaleString()}</b>
            </button>
          ))}
        </section>
      )}

      <section>
        <p className="mb-2 text-sm text-gray-600">
          총 {(result?.totalElements ?? 0).toLocaleString()}장
        </p>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-3 py-2">코드</th>
                <th className="px-3 py-2">할인</th>
                <th className="px-3 py-2">최소 주문액</th>
                <th className="px-3 py-2">소진</th>
                <th className="px-3 py-2">기간</th>
                <th className="px-3 py-2">상태</th>
                <th className="px-3 py-2">조작</th>
              </tr>
            </thead>
            <tbody>
              {(result?.content ?? []).map(coupon => (
                <tr key={coupon.id} className="border-t align-top">
                  <td className="px-3 py-2 font-mono">{coupon.code}</td>
                  <td className="px-3 py-2">
                    {coupon.type === 'PERCENTAGE'
                      ? `${coupon.discountValue}%`
                      : `${coupon.discountValue.toLocaleString()}원`}
                  </td>
                  <td className="px-3 py-2">
                    {coupon.minOrderAmount ? `${coupon.minOrderAmount.toLocaleString()}원` : '없음'}
                  </td>
                  <td className="px-3 py-2">
                    {coupon.usedCount.toLocaleString()} / {coupon.maxUses > 0 ? coupon.maxUses.toLocaleString() : '무제한'}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2">
                    {coupon.startsAt ? coupon.startsAt.slice(0, 10) : '즉시'} ~{' '}
                    {coupon.expiresAt ? coupon.expiresAt.slice(0, 10) : '무기한'}
                  </td>
                  <td className="px-3 py-2">{LIFECYCLE_LABEL[coupon.lifecycle] ?? coupon.lifecycle}</td>
                  <td className="space-x-1 px-3 py-2">
                    <button type="button" disabled={busyCode === coupon.code}
                      onClick={() => void toggle(coupon)}
                      className={`rounded px-2 py-1 text-xs text-white disabled:opacity-50 ${
                        coupon.active ? 'bg-red-600' : 'bg-blue-600'
                      }`}>
                      {coupon.active ? '중단' : '재개'}
                    </button>
                    <button type="button" onClick={() => void showUsages(coupon)}
                      className="rounded border px-2 py-1 text-xs">
                      사용 내역
                    </button>
                    {usages?.couponId === coupon.id && (
                      <div className="mt-2 rounded bg-gray-50 p-2 text-xs">
                        {usages.rows.length === 0 ? (
                          <p>아직 사용된 적이 없습니다.</p>
                        ) : (
                          <ul className="space-y-1">
                            {usages.rows.map(usage => (
                              <li key={usage.id}>
                                {usage.usedAt.slice(0, 16).replace('T', ' ')} ·{' '}
                                {usage.userEmail ?? `#${usage.userId}`}
                                {usage.orderId ? ` · 주문 ${usage.orderId}` : ''}
                                {usage.revokedAt && (
                                  <span className="ml-1 text-red-700">
                                    (회수됨{usage.revokeReason ? `: ${usage.revokeReason}` : ''})
                                  </span>
                                )}
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!loading && (result?.content.length ?? 0) === 0 && (
          <p className="py-6 text-center text-gray-500">조건에 맞는 쿠폰이 없습니다.</p>
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
