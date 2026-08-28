import React, { useCallback, useEffect, useState } from 'react';
import {
  sellerTierApi,
  SELLER_TIER_LABEL,
  type SellerTierGrade,
  type SellerTierPolicyView,
  type SellerTierRoster,
  type SellerTierRow,
  type TierEvaluationReport,
  type TierIntegrityReport,
} from '@/api/sellerTier';
import { apiErrorMessage, errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 셀러 등급 운영 콘솔 (ADR 0031).
 *
 * <p>이 화면이 필요한 이유는 등급이 <b>정산 금액을 바꾸기 때문</b>이다. 등급 하나가 수수료율
 * (NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%)·정산주기·홀드백을 동시에 정하는데, 지금까지는
 * 바꿀 진입점이 DB 밖에 없었다.
 *
 * <p>화면의 규율 셋:
 * <ul>
 *   <li><b>재산정은 미리보기가 먼저</b>다. 미리보기를 받은 뒤에만 반영 버튼이 열린다.
 *   <li><b>지정에는 사유가 필수</b>다. 서버가 @NotBlank 로 막지만, 버튼 단계에서 먼저 거른다 —
 *       빈 사유로 눌렀다가 400 을 보는 것은 사유를 쓰게 만드는 방법이 아니다.
 *   <li><b>임계를 함께 보여 준다.</b> 임계는 배포 환경마다 다를 수 있어서, 그걸 모르면
 *       "왜 이 셀러가 승급되지 않았나"를 화면에서 답할 수 없다.
 * </ul>
 *
 * <p>정합 검사를 나란히 둔 이유: {@code users.seller_tier} 는 캐시이고 결제는 그 캐시값으로
 * 정산을 확정한다. 정산은 스냅샷이라 사후에 정본을 고쳐도 되돌아오지 않으므로, 드리프트는
 * "언젠가 맞출 것"이 아니라 결제 전에 잡아야 하는 것이다.
 *
 * <p><b>명부가 맨 위에 있는 이유</b>: 처음 이 화면에는 <b>바꾸는 길만</b> 있었다 — 재산정·지정·검사.
 * 셋 다 버튼을 눌러야 무언가 나오고, 지정은 셀러 ID 를 이미 알아야 쓸 수 있었다(입력란이 숫자다).
 * 그래서 "셀러 등급이 안 보인다"는 말이 정확했다. 볼 수 없는 콘솔은 바꿀 수 있어도 쓸 수 없다.
 */

const fmtMoney = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const tierLabel = (tier: string | null): string => {
  if (!tier) return '—';
  return SELLER_TIER_LABEL[tier as SellerTierGrade] ?? tier;
};

/** 셀러를 사람이 알아볼 수 있는 이름으로. 이름이 없으면 이메일, 그것도 없으면 번호뿐이다. */
const sellerName = (row: Pick<SellerTierRow, 'sellerId' | 'email' | 'name'>): string =>
  row.name ?? row.email ?? `#${row.sellerId}`;

/* ─────────────────────────────────────────
   셀러 명부 — 이 콘솔에서 "누가 몇 등급인가"에 답하는 유일한 곳
───────────────────────────────────────── */
interface RosterPanelProps {
  roster: SellerTierRoster | null;
  loading: boolean;
  error: string | null;
  onReload: () => void;
  onPick: (row: SellerTierRow) => void;
  selectedId: number | null;
}

const RosterPanel: React.FC<RosterPanelProps> = ({
  roster, loading, error, onReload, onPick, selectedId,
}) => (
  <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3" data-testid="tier-roster">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div>
        <h2 className="font-semibold text-gray-900">셀러 명부</h2>
        <p className="text-sm text-gray-500 mt-1">
          상품을 가졌거나 등급이 부여된 계정입니다. 순매출이 큰 순서로 보여줍니다.
        </p>
      </div>
      <button type="button" onClick={onReload} disabled={loading}
        className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 bg-white text-gray-700 disabled:opacity-50">
        {loading ? '불러오는 중…' : '새로고침'}
      </button>
    </div>

    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

    {roster === null ? (
      !error && <p className="text-sm text-gray-400">불러오는 중…</p>
    ) : roster.rows.length === 0 ? (
      <p className="text-sm text-gray-500" data-testid="tier-roster-empty">
        상품을 가진 셀러가 없습니다. 등급을 매길 대상 자체가 아직 없다는 뜻입니다.
      </p>
    ) : (
      <>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="text-left text-gray-500">
              <tr>
                <th className="py-1 pr-4">셀러</th>
                <th className="py-1 pr-4">등급</th>
                <th className="py-1 pr-4">적용일</th>
                <th className="py-1 pr-4">강등 유예</th>
                <th className="py-1 pr-4">12개월 순매출</th>
                <th className="py-1 pr-4">상품</th>
                <th className="py-1"> </th>
              </tr>
            </thead>
            <tbody>
              {roster.rows.map((row) => (
                <tr key={row.sellerId} data-testid="tier-roster-row"
                  className={`border-t border-gray-100 ${selectedId === row.sellerId ? 'bg-blue-50' : ''}`}>
                  <td className="py-1.5 pr-4">
                    <span className="text-gray-900">{sellerName(row)}</span>
                    <span className="text-gray-400 ml-1">#{row.sellerId}</span>
                  </td>
                  <td className="py-1.5 pr-4">
                    <span className="font-medium">{tierLabel(row.tier)}</span>
                    {/* 정본과 캐시가 어긋난 행은 결제가 낡은 등급으로 정산을 확정하는 중이다.
                        총계는 정합 검사가 알려주지만, 어느 셀러인지는 여기서만 보인다. */}
                    {row.mismatched && (
                      <span className="ml-2 text-xs rounded px-1.5 py-0.5 bg-red-50 text-red-700">
                        캐시 {tierLabel(row.cachedTier)}
                      </span>
                    )}
                  </td>
                  <td className="py-1.5 pr-4 text-gray-500">{row.effectiveFrom ?? '—'}</td>
                  <td className="py-1.5 pr-4 text-gray-500">{row.demotionGuardUntil ?? '—'}</td>
                  <td className="py-1.5 pr-4">{fmtMoney(row.netSales12m)}</td>
                  <td className="py-1.5 pr-4 text-gray-500">{row.productCount}</td>
                  <td className="py-1.5">
                    <button type="button" onClick={() => onPick(row)}
                      className="px-2 py-1 text-xs font-semibold rounded border border-gray-300 bg-white text-gray-700">
                      등급 지정
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="text-xs text-gray-500">
          셀러 {roster.total}명
          {/* 잘린 사실을 숨기면 화면은 "이게 전부"로 읽힌다 — 없는 셀러를 찾는 것보다
              있는 셀러가 안 보이는 쪽이 훨씬 오래 안 들킨다. */}
          {roster.truncated && ` 중 ${roster.rows.length}명 (순매출 상위)`}
          {' · '}등급이 <b>—</b> 인 셀러는 아직 한 번도 산정되지 않은 계정입니다.
        </p>
      </>
    )}
  </section>
);

/* ─────────────────────────────────────────
   등급 재산정 (미리보기 → 반영)
───────────────────────────────────────── */
const EvaluatePanel: React.FC = () => {
  const { showToast } = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [date, setDate] = useState(today);
  const [preview, setPreview] = useState<TierEvaluationReport | null>(null);
  const [applied, setApplied] = useState<TierEvaluationReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 기준일이 바뀌면 이전 미리보기는 다른 날의 판정이다 — 남겨 두면 남의 결과로 반영하게 된다.
  const changeDate = (value: string) => {
    setDate(value);
    setPreview(null);
    setApplied(null);
  };

  const run = async (dryRun: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const report = await sellerTierApi.evaluate(dryRun, date);
      if (dryRun) {
        setPreview(report);
        setApplied(null);
      } else {
        setApplied(report);
        setPreview(null);
        showToast(`${report.promoted}건 승급 · ${report.demoted}건 강등 반영`, 'success');
      }
    } catch (err) {
      setError(errorDetail(err, '등급 재산정을 실행하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const report = applied ?? preview;
  const changing = preview ? preview.promoted + preview.demoted : 0;

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
      data-testid="tier-evaluate">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900">등급 재산정</h2>
          <p className="text-sm text-gray-500 mt-1">
            기준일의 거래액으로 등급을 다시 판정합니다. 반영은 되돌릴 수 없습니다.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input type="date" value={date} onChange={(e) => changeDate(e.target.value)}
            aria-label="재산정 기준일"
            className="rounded border border-gray-300 px-2 py-1.5 text-sm" />
          <button type="button" onClick={() => void run(true)} disabled={busy}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 bg-white text-gray-700 disabled:opacity-50">
            {busy ? '처리 중…' : '미리보기'}
          </button>
          {/* 미리보기를 받지 않았거나 바뀔 게 없으면 반영 버튼은 열리지 않는다. */}
          <button type="button" onClick={() => void run(false)}
            disabled={busy || preview === null || changing === 0}
            className="px-3 py-2 text-sm font-semibold rounded bg-blue-600 text-white disabled:opacity-50">
            {preview ? `${changing}건 반영` : '반영'}
          </button>
        </div>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {report && (
        <div className="space-y-2" data-testid="tier-evaluate-result">
          <p className="text-sm text-gray-700">
            {report.dryRun ? '미리보기 — 아직 아무것도 바뀌지 않았습니다. ' : '반영 완료. '}
            대상 {report.evaluated}건 · 승급 {report.promoted} · 강등 {report.demoted} ·
            유지 {report.held} · 보호 {report.guarded} · 실패 {report.failed}
          </p>
          {report.guarded > 0 && (
            <p className="text-xs text-gray-500">
              보호(guarded)는 관리자 지정에 딸린 강등 유예 기간이라 이번 판정이 되돌리지 못한 건입니다.
            </p>
          )}

          {report.lines.length > 0 && (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-1 pr-4">셀러</th>
                    <th className="py-1 pr-4">이전</th>
                    <th className="py-1 pr-4">이후</th>
                    <th className="py-1 pr-4">판정</th>
                    <th className="py-1 pr-4">순매출</th>
                    <th className="py-1">사유</th>
                  </tr>
                </thead>
                <tbody>
                  {report.lines.map((line) => (
                    <tr key={line.sellerId} className="border-t border-gray-100">
                      <td className="py-1 pr-4">#{line.sellerId}</td>
                      <td className="py-1 pr-4">{tierLabel(line.fromTier)}</td>
                      <td className="py-1 pr-4">{tierLabel(line.toTier)}</td>
                      <td className="py-1 pr-4">{line.outcome}</td>
                      <td className="py-1 pr-4">{fmtMoney(line.netSales)}</td>
                      <td className="py-1 text-gray-500">{line.reason ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  );
};

/* ─────────────────────────────────────────
   관리자 등급 지정
───────────────────────────────────────── */
interface OverridePanelProps {
  /** 명부에서 고른 셀러. null 이면 번호를 직접 적는다(명부가 못 뜬 상황에서도 지정은 되어야 한다). */
  target: SellerTierRow | null;
  onClear: () => void;
  onDone: () => void;
}

const OverridePanel: React.FC<OverridePanelProps> = ({ target, onClear, onDone }) => {
  const { showToast } = useToast();
  const [sellerId, setSellerId] = useState('');
  const [tier, setTier] = useState<SellerTierGrade>('VIP');
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 서버가 @NotBlank 로 막는 사유를 버튼 단계에서 먼저 거른다.
  const targetId = target ? String(target.sellerId) : sellerId.trim();
  const valid = /^\d+$/.test(targetId) && memo.trim() !== '';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const assignment = await sellerTierApi.override(Number(targetId), tier, memo.trim());
      showToast(
        `셀러 #${assignment.sellerId} → ${tierLabel(assignment.tier)} (강등 유예 ${assignment.demotionGuardUntil ?? '없음'})`,
        'success');
      setMemo('');
      // 명부를 다시 읽는다 — 방금 바꾼 등급이 표에 그대로 낡아 있으면 무엇이 반영됐는지 알 수 없다.
      onDone();
    } catch (err) {
      setError(errorDetail(err, '등급을 지정하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
      data-testid="tier-override">
      <div>
        <h2 className="font-semibold text-gray-900">관리자 등급 지정</h2>
        <p className="text-sm text-gray-500 mt-1">
          자동 판정으로 담을 수 없는 사정(계약 · 보상 · 합의)을 반영합니다. 지정에는 강등 유예가
          함께 걸려 다음 재산정이 곧바로 되돌리지 못합니다.
        </p>
      </div>

      <div className="flex flex-wrap gap-2">
        {target ? (
          // 명부에서 고른 셀러. 번호를 외워서 적는 대신 고른 대상을 그대로 보여준다 —
          // 숫자만 적히면 엉뚱한 셀러의 수수료율을 바꿔도 화면에서 알아챌 방법이 없다.
          <span data-testid="tier-override-target"
            className="flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-sm">
            <span className="font-medium text-gray-900">{sellerName(target)}</span>
            <span className="text-gray-500">#{target.sellerId} · 현재 {tierLabel(target.tier)}</span>
            <button type="button" onClick={onClear} aria-label="선택 해제"
              className="text-gray-400 hover:text-gray-600">✕</button>
          </span>
        ) : (
          <input value={sellerId} onChange={(e) => setSellerId(e.target.value)}
            aria-label="셀러 ID" placeholder="셀러 ID" inputMode="numeric"
            className="w-32 rounded border border-gray-300 px-3 py-2 text-sm" />
        )}
        <select value={tier} onChange={(e) => setTier(e.target.value as SellerTierGrade)}
          aria-label="지정할 등급"
          className="rounded border border-gray-300 px-3 py-2 text-sm">
          {(Object.keys(SELLER_TIER_LABEL) as SellerTierGrade[]).map((grade) => (
            <option key={grade} value={grade}>{SELLER_TIER_LABEL[grade]}</option>
          ))}
        </select>
        <input value={memo} onChange={(e) => setMemo(e.target.value)}
          aria-label="변경 사유" placeholder="변경 사유 (필수)"
          className="flex-1 min-w-48 rounded border border-gray-300 px-3 py-2 text-sm" />
        <button type="button" onClick={() => void submit()} disabled={!valid || busy}
          className="px-3 py-2 text-sm font-semibold rounded bg-blue-600 text-white disabled:opacity-50">
          {busy ? '지정 중…' : '지정'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
    </section>
  );
};

/* ─────────────────────────────────────────
   정본 ↔ 캐시 정합 검사
───────────────────────────────────────── */
const IntegrityPanel: React.FC = () => {
  const [report, setReport] = useState<TierIntegrityReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await sellerTierApi.integrity());
    } catch (err) {
      setError(errorDetail(err, '정합 검사를 실행하지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
      data-testid="tier-integrity">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900">등급 캐시 정합 검사</h2>
          <p className="text-sm text-gray-500 mt-1">
            읽기 전용입니다. 어긋난 채로 결제가 일어나면 그 시점 캐시값으로 정산이 확정되고,
            정산은 스냅샷이라 사후에 정본을 고쳐도 되돌아오지 않습니다.
          </p>
        </div>
        <button type="button" onClick={() => void load()} disabled={loading}
          className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 bg-white text-gray-700 disabled:opacity-50">
          {loading ? '검사 중…' : '검사'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {report && (
        <div className="space-y-2" data-testid="tier-integrity-result">
          <p className="text-sm text-gray-700">
            불일치 {report.drifted}건 · 판독 불가 {report.unreadable}건
          </p>
          {report.unreadable > 0 && (
            <p className="text-sm text-yellow-800 bg-yellow-50 rounded px-3 py-2">
              등급 문자열이 알 수 없는 값인 행이 있습니다 — 그 자체가 조사 대상입니다.
            </p>
          )}
          {report.samples.length > 0 && (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-1 pr-4">셀러</th>
                    <th className="py-1 pr-4">정본</th>
                    <th className="py-1 pr-4">캐시</th>
                    <th className="py-1">유형</th>
                  </tr>
                </thead>
                <tbody>
                  {report.samples.map((drift) => (
                    <tr key={drift.sellerId} className="border-t border-gray-100">
                      <td className="py-1 pr-4">#{drift.sellerId}</td>
                      <td className="py-1 pr-4">{drift.authoritativeTier ?? '—'}</td>
                      <td className="py-1 pr-4">{drift.cachedTier ?? '—'}</td>
                      <td className="py-1 text-gray-500">{drift.kind}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {report.drifted === 0 && report.unreadable === 0 && (
            <p className="text-sm text-green-700">정본과 캐시가 일치합니다.</p>
          )}
        </div>
      )}
    </section>
  );
};

/* ─────────────────────────────────────────
   셀러 등급 콘솔
───────────────────────────────────────── */
const SellerTierAdminPage: React.FC = () => {
  const [policy, setPolicy] = useState<SellerTierPolicyView | null>(null);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [roster, setRoster] = useState<SellerTierRoster | null>(null);
  const [rosterError, setRosterError] = useState<string | null>(null);
  const [rosterLoading, setRosterLoading] = useState(false);
  const [target, setTarget] = useState<SellerTierRow | null>(null);

  const loadPolicy = useCallback(async () => {
    setPolicyError(null);
    try {
      setPolicy(await sellerTierApi.policy());
    } catch (err) {
      setPolicyError(errorDetail(err, '등급 임계를 불러오지 못했습니다.'));
    }
  }, []);

  // 명부는 버튼을 눌러야 나오는 것이 아니라 화면에 들어오면 바로 있어야 한다 —
  // 등급을 확인하러 온 사람에게 빈 화면을 먼저 보여줄 이유가 없다.
  const loadRoster = useCallback(async () => {
    setRosterLoading(true);
    setRosterError(null);
    try {
      setRoster(await sellerTierApi.list());
    } catch (err) {
      // 읽기 실패는 원인 문구가 아니라 화면용 문구로 떨어뜨린다 — "Network Error" 를 그대로
      // 띄우면 관리자는 명부가 비었다는 건지 못 불러온 건지 구분하지 못한다.
      setRosterError(apiErrorMessage(err, '셀러 명부를 불러오지 못했습니다.'));
    } finally {
      setRosterLoading(false);
    }
  }, []);

  useEffect(() => { void loadPolicy(); }, [loadPolicy]);
  useEffect(() => { void loadRoster(); }, [loadRoster]);

  // 전체 페이지 래퍼는 두지 않는다 — 이 화면은 SideNavLayout 안에서 그려진다.
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">셀러 등급</h1>
        <p className="text-sm text-gray-500 mt-1">
          등급은 수수료율 · 정산주기 · 홀드백을 동시에 정합니다. 바꾸기 전에 미리보기로 규모를 확인하세요.
        </p>
      </div>

      <section className="bg-white rounded-xl border border-gray-200 p-4" data-testid="tier-policy">
        <h2 className="font-semibold text-gray-900">적용 중인 임계</h2>
        {policyError ? (
          <p role="alert" className="text-sm text-red-600 mt-2">{policyError}</p>
        ) : policy === null ? (
          <p className="text-sm text-gray-400 mt-2">불러오는 중…</p>
        ) : (
          <dl className="mt-2 flex flex-wrap gap-6 text-sm">
            <div className="flex gap-2">
              <dt className="text-gray-500">VIP 이상</dt>
              <dd className="font-semibold" data-testid="vip-threshold">{fmtMoney(policy.vipThreshold)}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-gray-500">전략 이상</dt>
              <dd className="font-semibold" data-testid="strategic-threshold">
                {fmtMoney(policy.strategicThreshold)}
              </dd>
            </div>
          </dl>
        )}
        <p className="text-xs text-gray-500 mt-2">
          임계는 배포 환경 설정값입니다 — 재산정 결과를 해석하려면 지금 무슨 기준으로 도는지 알아야 합니다.
        </p>
      </section>

      <RosterPanel roster={roster} loading={rosterLoading} error={rosterError}
        onReload={() => void loadRoster()} onPick={setTarget}
        selectedId={target?.sellerId ?? null} />
      <OverridePanel target={target} onClear={() => setTarget(null)}
        onDone={() => { setTarget(null); void loadRoster(); }} />
      <EvaluatePanel />
      <IntegrityPanel />
    </div>
  );
};

export default SellerTierAdminPage;
