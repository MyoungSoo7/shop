import { useCallback, useEffect, useState } from 'react';
import {
  pointApi,
  type DeductPointResult,
  type ExpirePointResult,
  type ExpiringLotView,
  type GrantPointResult,
  type PointAccountDetail,
  type PointConsoleSummary,
  type PointEarnPolicyView,
  type PointEarnScope,
} from '@/api/point';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';

/**
 * 포인트 운영 콘솔.
 *
 * <p>되돌리기 어려운 조작 넷을 한다: <b>수기 지급</b>(없던 돈을 만든다)·<b>수기 차감</b>(고객
 * 재산을 거둬들인다)·<b>소멸 실행</b>(고객 재산을 지운다)·<b>정책 편집</b>(앞으로의 적립을 바꾼다).
 * 화면이 그 무게를 반영한다.
 *
 * <ul>
 *   <li>지급·차감 모두 <b>사유가 필수</b>다. 근거 없이 포인트가 생기거나 사라지면 나중에
 *       "왜 이 돈이 여기 있나/없나"에 답할 수 없고, 그 순간 원장은 설명력을 잃는다.
 *   <li>소멸은 <b>미리보기가 기본</b>이다. 실행하려면 미리보기를 먼저 돌려 규모를 본 뒤에만
 *       버튼이 열린다 — 파라미터를 빠뜨린 클릭이 실행이 되어선 안 된다.
 *   <li>정책은 <b>고치지 않는다</b>. 종료일 지정으로 자리를 비운 뒤 새 행을 등록하는 2단계다
 *       (ADR 0032). 화면도 그 순서대로만 조작할 수 있게 배치한다 — 무기한 정책에만
 *       "종료일 지정" 버튼이 뜬다.
 * </ul>
 *
 * <p>멱등 키(참조 ID)를 화면이 직접 받는 이유: 같은 보상을 두 번 눌러도 한 번만 지급되게 하려면
 * 서버가 그 키로 구분해야 한다. 자동 생성하면 재시도가 곧 이중 지급이 된다.
 *
 * <p><b>조회 4종이 위에 오는 이유</b>: 오래도록 이 화면에는 쓰기 둘뿐이었고, 운영자는 "이 계정이
 * 지금 얼마이고 왜 그런가"를 모르는 채 되돌리기 어려운 버튼을 눌러야 했다. 전체 3자 대조 ·
 * 계정 상세 · 적립정책 · 소멸 예정을 먼저 보여 주고, 그다음에 조작을 둔다.
 */
export default function PointConsolePage() {
  const [userId, setUserId] = useState('');
  const [amount, setAmount] = useState('');
  const [referenceId, setReferenceId] = useState('');
  const [reason, setReason] = useState('');
  const [validityDays, setValidityDays] = useState('365');

  const [granting, setGranting] = useState(false);
  const [grantResult, setGrantResult] = useState<GrantPointResult | null>(null);
  const [grantError, setGrantError] = useState<string | null>(null);

  const [preview, setPreview] = useState<ExpirePointResult | null>(null);
  const [expiryResult, setExpiryResult] = useState<ExpirePointResult | null>(null);
  const [expiryBusy, setExpiryBusy] = useState(false);
  const [expiryError, setExpiryError] = useState<string | null>(null);

  const [summary, setSummary] = useState<PointConsoleSummary | null>(null);
  const [summaryError, setSummaryError] = useState<string | null>(null);
  const [policies, setPolicies] = useState<PointEarnPolicyView[]>([]);
  const [expiring, setExpiring] = useState<ExpiringLotView[]>([]);
  const [withinDays, setWithinDays] = useState(30);

  const [lookupId, setLookupId] = useState('');
  const [detail, setDetail] = useState<PointAccountDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);

  const [deductUserId, setDeductUserId] = useState('');
  const [deductAmount, setDeductAmount] = useState('');
  const [deductReferenceId, setDeductReferenceId] = useState('');
  const [deductReason, setDeductReason] = useState('');
  const [deducting, setDeducting] = useState(false);
  const [deductResult, setDeductResult] = useState<DeductPointResult | null>(null);
  const [deductError, setDeductError] = useState<string | null>(null);

  const [policyScope, setPolicyScope] = useState<PointEarnScope>('GLOBAL');
  const [policyScopeKey, setPolicyScopeKey] = useState('-');
  const [policyRatePercent, setPolicyRatePercent] = useState('');
  const [policyValidityDays, setPolicyValidityDays] = useState('365');
  const [policyFrom, setPolicyFrom] = useState('');
  const [policyTo, setPolicyTo] = useState('');
  const [policyReason, setPolicyReason] = useState('');
  const [policyBusy, setPolicyBusy] = useState(false);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [policyNotice, setPolicyNotice] = useState<string | null>(null);
  const [closeTarget, setCloseTarget] = useState<PointEarnPolicyView | null>(null);
  const [closeDate, setCloseDate] = useState('');

  const grantDisabled =
    granting || !userId.trim() || !amount.trim() || !referenceId.trim() || !reason.trim();

  const deductDisabled = deducting || !deductUserId.trim() || !deductAmount.trim()
    || !deductReferenceId.trim() || !deductReason.trim();

  const loadOverview = useCallback(async () => {
    setSummaryError(null);
    try {
      const [nextSummary, nextPolicies, nextExpiring] = await Promise.all([
        pointApi.summary(withinDays),
        pointApi.policies(),
        pointApi.expiring(withinDays, 50),
      ]);
      setSummary(nextSummary);
      setPolicies(nextPolicies);
      setExpiring(nextExpiring);
    } catch (err) {
      setSummary(null);
      setSummaryError(apiErrorMessage(err, '포인트 현황을 불러오지 못했습니다.'));
    }
  }, [withinDays]);

  useEffect(() => { void loadOverview(); }, [loadOverview]);

  const deduct = async () => {
    setDeductError(null);
    setDeductResult(null);
    setDeducting(true);
    try {
      setDeductResult(await pointApi.deduct({
        userId: Number(deductUserId),
        amount: Number(deductAmount),
        referenceId: deductReferenceId.trim(),
        reason: deductReason.trim(),
      }));
      // 잔고가 줄었으므로 위쪽 현황은 낡았다 — 지급과 같은 이유로 다시 읽는다.
      await loadOverview();
    } catch (err) {
      setDeductError(apiErrorMessage(err, '포인트 차감에 실패했습니다.'));
    } finally {
      setDeducting(false);
    }
  };

  const registerPolicy = async () => {
    setPolicyError(null);
    setPolicyNotice(null);
    setPolicyBusy(true);
    try {
      // 화면은 사람이 읽는 %, 서버는 0~1 비율을 쓴다. 변환은 한 곳(여기)에서만 한다.
      await pointApi.registerPolicy({
        scope: policyScope,
        scopeKey: policyScopeKey.trim() || '-',
        earnRate: Number(policyRatePercent) / 100,
        validityDays: Number(policyValidityDays) || 365,
        effectiveFrom: policyFrom,
        effectiveTo: policyTo || null,
        reason: policyReason.trim(),
      });
      setPolicyNotice('정책을 등록했습니다.');
      setPolicyRatePercent('');
      setPolicyReason('');
      await loadOverview();
    } catch (err) {
      setPolicyError(apiErrorMessage(err, '정책 등록에 실패했습니다.'));
    } finally {
      setPolicyBusy(false);
    }
  };

  const closePolicy = async () => {
    if (!closeTarget) return;
    setPolicyError(null);
    setPolicyNotice(null);
    setPolicyBusy(true);
    try {
      await pointApi.closePolicy(closeTarget.id, closeDate);
      setPolicyNotice(`#${closeTarget.id} 정책을 ${closeDate} 부터 적용하지 않습니다.`);
      setCloseTarget(null);
      setCloseDate('');
      await loadOverview();
    } catch (err) {
      setPolicyError(apiErrorMessage(err, '정책 종료에 실패했습니다.'));
    } finally {
      setPolicyBusy(false);
    }
  };

  const lookup = async () => {
    setDetailError(null);
    setDetail(null);
    setDetailBusy(true);
    try {
      setDetail(await pointApi.account(Number(lookupId)));
    } catch (err) {
      // 404 는 장애가 아니다 — 포인트를 한 번도 쓴 적 없어 계정 자체가 없는 상태다.
      setDetailError(apiErrorStatus(err) === 404
        ? '이 회원은 포인트 계정이 아직 없습니다(적립·지급 이력이 없음). 잔액 0 인 계정과는 다릅니다.'
        : apiErrorMessage(err, '계정을 조회하지 못했습니다.'));
    } finally {
      setDetailBusy(false);
    }
  };

  const grant = async () => {
    setGrantError(null);
    setGrantResult(null);
    setGranting(true);
    try {
      const days = validityDays.trim() === '' ? null : Number(validityDays);
      setGrantResult(await pointApi.grant({
        userId: Number(userId),
        amount: Number(amount),
        referenceId: referenceId.trim(),
        reason: reason.trim(),
        validityDays: days,
      }));
      // 잔고가 늘었으므로 위쪽 현황은 이미 낡았다 — 남겨 두면 방금 만든 돈이 안 보인다.
      await loadOverview();
    } catch (err) {
      setGrantError(apiErrorMessage(err, '포인트 지급에 실패했습니다.'));
    } finally {
      setGranting(false);
    }
  };

  const runPreview = async () => {
    setExpiryError(null);
    setExpiryResult(null);
    setExpiryBusy(true);
    try {
      setPreview(await pointApi.runExpiry(true));
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 미리보기에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  const runExpiry = async () => {
    setExpiryError(null);
    setExpiryBusy(true);
    try {
      const result = await pointApi.runExpiry(false);
      setExpiryResult(result);
      setPreview(null);   // 실행 후 미리보기는 낡은 값이 된다 — 남겨 두면 오해를 부른다.
      await loadOverview();
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 실행에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  return (
    <main className="p-6 space-y-8">
      <header>
        <h1 className="text-2xl font-bold">포인트 운영</h1>
        <p className="text-sm text-gray-500">
          현황을 확인하고 수기 지급·유효기간 소멸을 실행합니다. 두 조작 모두 원장에 영구 기록됩니다.
        </p>
      </header>

      <section className="space-y-3 rounded border p-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold">원장 현황</h2>
            <p className="text-sm text-gray-500">
              잔고 총액 · ACTIVE 로트 합계 · 원장 누계는 갱신 경로가 다릅니다. 셋이 어긋났다면
              잔고만 움직이고 기록이 빠진 트랜잭션이 있다는 뜻입니다.
            </p>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <span>소멸 예정 기준</span>
            <input aria-label="소멸 예정 기준 일수" type="number" min={1} max={365} value={withinDays}
              onChange={e => setWithinDays(Number(e.target.value) || 30)}
              className="w-20 rounded border px-2 py-1" />
            <span>일 이내</span>
          </label>
        </div>

        {/* 이 영역의 상태 문구에는 role="alert"/"status" 를 쓰지 않는다 — 페이지 진입 시 함께
            그려지는 정적 내용이라, 지급·소멸 같은 조작 결과 알림과 같은 등급으로 읽히면
            스크린리더에서 정작 중요한 실행 결과가 묻힌다. */}
        {summaryError && <p data-testid="point-summary-error" className="text-red-600">{summaryError}</p>}

        {summary && (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
              {[
                { label: '계정 수', value: `${summary.accountCount.toLocaleString()}개` },
                { label: '잔고 총액', value: `${summary.totalBalance.toLocaleString()}P` },
                { label: 'ACTIVE 로트 합계', value: `${summary.totalActiveLotRemaining.toLocaleString()}P` },
                { label: '원장 누계', value: `${summary.totalEntryNet.toLocaleString()}P` },
                { label: `${summary.expiringWithinDays}일 내 소멸 예정`, value: `${summary.expiringAmount.toLocaleString()}P` },
              ].map(card => (
                <div key={card.label} className="rounded border p-3">
                  <p className="text-xs text-gray-500">{card.label}</p>
                  <p className="mt-1 font-bold">{card.value}</p>
                </div>
              ))}
            </div>

            {summary.driftedAccountCount === 0 ? (
              <p data-testid="point-ledger-balance" className="text-sm text-green-700">
                3자 대조 균형 — 잔고·로트·원장이 모두 일치합니다.
              </p>
            ) : (
              <p data-testid="point-ledger-drift" className="text-sm text-red-700">
                잔고와 상세가 어긋난 계정 {summary.driftedAccountCount}개 — 조사가 필요합니다.
                아래 계정 조회로 해당 회원의 로트·원장 내역을 확인하세요.
              </p>
            )}
          </>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">계정 조회</h2>
        <p className="text-sm text-gray-500">
          지급·소멸을 누르기 전에 그 회원의 잔액이 <b>왜</b> 그런지 확인합니다.
        </p>

        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-sm">조회할 회원 ID</span>
            <input aria-label="조회할 회원 ID" value={lookupId} onChange={e => setLookupId(e.target.value)}
              inputMode="numeric" className="w-40 rounded border px-3 py-2" />
          </label>
          <button type="button" onClick={() => void lookup()} disabled={detailBusy || !lookupId.trim()}
            className="rounded border px-4 py-2 disabled:opacity-50">
            {detailBusy ? '조회 중…' : '조회'}
          </button>
        </div>

        {detailError && <p data-testid="point-account-error" className="text-amber-700">{detailError}</p>}

        {detail && (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-4 text-sm">
              <span>계정 #{detail.accountId}</span>
              <span>상태 {detail.status}</span>
              <span>가용 {detail.available.toLocaleString()}P</span>
              <span>선점 {detail.locked.toLocaleString()}P</span>
              <span>합계 {detail.total.toLocaleString()}P</span>
            </div>

            {detail.health.accountTotal === detail.health.activeLotRemaining
              && detail.health.accountTotal === detail.health.entryNet ? (
                <p data-testid="account-health-balanced" className="text-sm text-green-700">
                  이 계정의 3자 대조는 균형입니다({detail.health.accountTotal.toLocaleString()}P).
                </p>
              ) : (
                <p data-testid="account-health-drift" className="text-sm text-red-700">
                  3자 대조 불일치 — 잔고 {detail.health.accountTotal.toLocaleString()}P ·
                  로트 합계 {detail.health.activeLotRemaining.toLocaleString()}P ·
                  원장 누계 {detail.health.entryNet.toLocaleString()}P
                </p>
              )}

            <div className="grid gap-4 lg:grid-cols-2">
              <div>
                <h3 className="mb-1 text-sm font-semibold">로트 {detail.lots.length}건</h3>
                {detail.lots.length === 0 ? (
                  <p className="text-sm text-gray-400">로트가 없습니다.</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {detail.lots.map(lot => (
                      <li key={lot.lotId} className="rounded border px-3 py-2">
                        <div className="flex justify-between">
                          <span>{lot.origin} · {lot.status}</span>
                          <span>{lot.remainingAmount.toLocaleString()} / {lot.originalAmount.toLocaleString()}P</span>
                        </div>
                        <div className="text-xs text-gray-500">
                          만료 {lot.expiresAt ? lot.expiresAt.slice(0, 10) : '무기한'} ·
                          근거 {lot.referenceType}/{lot.referenceId}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <div>
                <h3 className="mb-1 text-sm font-semibold">원장 {detail.entries.length}건</h3>
                {detail.entries.length === 0 ? (
                  <p className="text-sm text-gray-400">기록이 없습니다.</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {detail.entries.map(entry => (
                      <li key={entry.entryId} className="rounded border px-3 py-2">
                        <div className="flex justify-between">
                          <span>{entry.entryType}</span>
                          <span>{entry.amount.toLocaleString()}P</span>
                        </div>
                        <div className="text-xs text-gray-500">
                          {entry.createdAt.slice(0, 19).replace('T', ' ')} · {entry.createdBy}
                          {entry.memo && <span data-testid="entry-memo"> · {entry.memo}</span>}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </div>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">적립률 정책</h2>
        <p className="text-sm text-gray-500">
          행을 고치지 않고 종료 + 신규 행으로 바꾸는 구조라, 과거 적립이 왜 그 요율이었는지 설명할 수
          있습니다. <b>표가 비어 있으면 적립률은 0</b> 입니다(무행동 착지).
        </p>
        {policies.length === 0 ? (
          <p className="text-sm text-amber-700">
            등록된 적립률 정책이 없습니다 — 현재 주문 적립은 0P 로 계산됩니다.
          </p>
        ) : (
          <ul className="space-y-1 text-sm">
            {policies.map(p => (
              <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded border px-3 py-2">
                <span>
                  {p.scope}{p.scopeKey && p.scopeKey !== '-' ? `/${p.scopeKey}` : ''} ·
                  {' '}{(p.earnRate * 100).toFixed(3)}% · 유효 {p.validityDays}일
                </span>
                <span className="text-xs text-gray-500">
                  {p.effectiveFrom} ~ {p.effectiveTo ?? '무기한'} ·{' '}
                  <span data-testid={p.active ? 'policy-active' : 'policy-closed'}>
                    {p.active ? '적용 중' : '종료'}
                  </span>
                  {p.closedAt && p.active && (
                    <span data-testid="policy-close-scheduled" className="ml-1 text-amber-700">
                      (종료 예약됨 — {p.effectiveTo} 까지 적용)
                    </span>
                  )}
                  {' '}· {p.reason}
                </span>
                {p.effectiveTo === null && (
                  <button type="button" onClick={() => setCloseTarget(p)}
                    className="rounded border px-2 py-1 text-xs">
                    종료일 지정
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}

        {closeTarget && (
          <div className="space-y-2 rounded border border-amber-300 bg-amber-50 p-3">
            <p className="text-sm">
              <b>#{closeTarget.id} {closeTarget.scope}</b> 정책을 언제부터 적용하지 않을지 정합니다.
              그날 <b>부터</b> 적용되지 않습니다(반열림). 과거 날짜는 지정할 수 없습니다 —
              그 구간의 적립은 이미 일어났습니다.
            </p>
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1">
                <span className="text-sm">종료일</span>
                <input aria-label="정책 종료일" type="date" value={closeDate}
                  onChange={e => setCloseDate(e.target.value)}
                  className="rounded border px-3 py-2" />
              </label>
              <button type="button" onClick={() => void closePolicy()} disabled={policyBusy || !closeDate}
                className="rounded bg-amber-600 px-4 py-2 text-white disabled:opacity-50">
                {policyBusy ? '처리 중…' : '종료 확정'}
              </button>
              <button type="button" onClick={() => setCloseTarget(null)}
                className="rounded border px-4 py-2">
                취소
              </button>
            </div>
          </div>
        )}

        <details className="rounded border p-3">
          <summary className="cursor-pointer text-sm font-semibold">새 정책 등록</summary>
          <p className="mt-2 text-sm text-gray-500">
            요율을 바꾸려면 <b>현재 정책을 먼저 종료</b>해 자리를 비운 뒤 등록합니다. 겹치면 서버가
            거절합니다(409). 소급 발효도 등록할 수 없습니다 — 이미 적립된 로트는 그때 요율의
            스냅샷이라 재계산되지 않습니다.
          </p>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1">
              <span className="text-sm">범위</span>
              <select aria-label="정책 범위" value={policyScope} className="rounded border px-3 py-2"
                onChange={e => setPolicyScope(e.target.value as PointEarnScope)}>
                <option value="GLOBAL">GLOBAL — 전체</option>
                <option value="GRADE">GRADE — 회원 등급</option>
                <option value="CATEGORY">CATEGORY — 카테고리</option>
              </select>
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">범위 키 (GLOBAL 은 -)</span>
              <input aria-label="정책 범위 키" value={policyScopeKey}
                onChange={e => setPolicyScopeKey(e.target.value)}
                className="rounded border px-3 py-2" />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">적립률 (%)</span>
              <input aria-label="적립률" value={policyRatePercent} inputMode="decimal"
                onChange={e => setPolicyRatePercent(e.target.value)}
                placeholder="1.0" className="rounded border px-3 py-2" />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">적립 포인트 유효기간(일)</span>
              <input aria-label="적립 유효기간" value={policyValidityDays} inputMode="numeric"
                onChange={e => setPolicyValidityDays(e.target.value)}
                className="rounded border px-3 py-2" />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">발효일 (오늘 이상)</span>
              <input aria-label="정책 발효일" type="date" value={policyFrom}
                onChange={e => setPolicyFrom(e.target.value)}
                className="rounded border px-3 py-2" />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">종료일 — 비우면 무기한</span>
              <input aria-label="정책 종료일(선택)" type="date" value={policyTo}
                onChange={e => setPolicyTo(e.target.value)}
                className="rounded border px-3 py-2" />
            </label>
            <label className="flex flex-col gap-1 sm:col-span-2">
              <span className="text-sm">근거 (필수)</span>
              <input aria-label="정책 근거" value={policyReason}
                onChange={e => setPolicyReason(e.target.value)}
                placeholder="2026 하반기 기본 적립률" className="rounded border px-3 py-2" />
            </label>
          </div>
          <button type="button" onClick={() => void registerPolicy()}
            disabled={policyBusy || !policyRatePercent.trim() || !policyFrom || !policyReason.trim()}
            className="mt-3 rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
            {policyBusy ? '등록 중…' : '정책 등록'}
          </button>
        </details>

        {policyError && <p data-testid="policy-error" className="text-red-600">{policyError}</p>}
        {policyNotice && <p data-testid="policy-notice" className="text-green-700">{policyNotice}</p>}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">소멸 예정 {withinDays}일 이내</h2>
        <p className="text-sm text-gray-500">
          만료 임박 순입니다. 무기한 로트는 대상이 아닙니다.
        </p>
        {expiring.length === 0 ? (
          <p className="text-sm text-gray-400">이 기간에 소멸 예정인 로트가 없습니다.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {expiring.map(lot => (
              <li key={lot.lotId} className="flex flex-wrap justify-between gap-2 rounded border px-3 py-2">
                <span>회원 #{lot.userId} · 로트 #{lot.lotId} · {lot.origin}</span>
                <span>{lot.remainingAmount.toLocaleString()}P · {lot.expiresAt.slice(0, 10)} 만료</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">수기 지급</h2>
        <p className="text-sm text-gray-500">
          CS 보상 등으로 직접 지급합니다. 같은 참조 ID 로 다시 지급하면 한 번만 반영됩니다.
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-sm">회원 ID</span>
            <input aria-label="회원 ID" value={userId} onChange={e => setUserId(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">지급 포인트</span>
            <input aria-label="지급 포인트" value={amount} onChange={e => setAmount(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">참조 ID (멱등 키)</span>
            <input aria-label="참조 ID" value={referenceId} onChange={e => setReferenceId(e.target.value)}
              placeholder="cs-20260818-001" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">유효기간(일) — 비우면 무기한</span>
            <input aria-label="유효기간" value={validityDays} onChange={e => setValidityDays(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-sm">지급 사유 (필수)</span>
            <input aria-label="지급 사유" value={reason} onChange={e => setReason(e.target.value)}
              placeholder="배송 지연 보상" className="rounded border px-3 py-2" />
          </label>
        </div>

        <button type="button" onClick={() => void grant()} disabled={grantDisabled}
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
          {granting ? '지급 중…' : '포인트 지급'}
        </button>

        {grantError && <p role="alert" className="text-red-600">{grantError}</p>}
        {grantResult && (
          <p role="status" className="text-sm text-green-700">
            {grantResult.entryId === null
              ? '이미 지급된 참조 ID 입니다 — 중복 지급되지 않았습니다.'
              : `지급 완료: ${grantResult.grantedAmount.toLocaleString()}P (지급 후 잔액 ${grantResult.remainingBalance.toLocaleString()}P)`}
          </p>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">수기 차감</h2>
        <p className="text-sm text-gray-500">
          오지급·부정 적립을 거둬들입니다. 지급과 대칭으로 <b>사유가 필수</b>이며, 같은 참조 ID 로
          다시 눌러도 한 번만 빠집니다. 만료가 임박한 로트부터 소비하고, <b>잔액을 넘는 차감은
          거절</b>됩니다. 정지된 계정에서도 회수할 수 있습니다.
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-sm">차감 대상 회원 ID</span>
            <input aria-label="차감 대상 회원 ID" value={deductUserId}
              onChange={e => setDeductUserId(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">차감 포인트</span>
            <input aria-label="차감 포인트" value={deductAmount}
              onChange={e => setDeductAmount(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">차감 참조 ID (멱등 키)</span>
            <input aria-label="차감 참조 ID" value={deductReferenceId}
              onChange={e => setDeductReferenceId(e.target.value)}
              placeholder="recall-20260820-001" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">차감 사유 (필수)</span>
            <input aria-label="차감 사유" value={deductReason}
              onChange={e => setDeductReason(e.target.value)}
              placeholder="오지급 회수" className="rounded border px-3 py-2" />
          </label>
        </div>

        <button type="button" onClick={() => void deduct()} disabled={deductDisabled}
          className="rounded bg-red-600 px-4 py-2 text-white disabled:opacity-50">
          {deducting ? '차감 중…' : '포인트 차감'}
        </button>

        {deductError && <p data-testid="deduct-error" className="text-red-600">{deductError}</p>}
        {deductResult && (
          <p data-testid="deduct-result" className="text-sm text-red-700">
            {deductResult.entryId === null
              ? '이미 차감된 참조 ID 입니다 — 중복 차감되지 않았습니다.'
              : `차감 완료: ${deductResult.deductedAmount.toLocaleString()}P (차감 후 잔액 ${deductResult.remainingBalance.toLocaleString()}P)`}
          </p>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">유효기간 소멸</h2>
        <p className="text-sm text-gray-500">
          고객 재산을 지우는 작업이라 미리보기를 먼저 실행해야 합니다. 스케줄러가 매일 자동으로
          돌지만, 장애 후 즉시 처리하거나 대상을 확인할 때 사용합니다.
        </p>

        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => void runPreview()} disabled={expiryBusy}
            className="rounded border px-4 py-2 disabled:opacity-50">
            {expiryBusy ? '실행 중…' : '미리보기'}
          </button>
          <button type="button" onClick={() => void runExpiry()} disabled={expiryBusy || preview === null}
            className="rounded bg-red-600 px-4 py-2 text-white disabled:opacity-50">
            소멸 실행
          </button>
        </div>
        {preview === null && expiryResult === null && (
          <p className="text-xs text-gray-500">미리보기를 먼저 실행하면 소멸 버튼이 열립니다.</p>
        )}

        {expiryError && <p role="alert" className="text-red-600">{expiryError}</p>}
        {preview && (
          <p role="status" className="text-sm">
            미리보기: 로트 {preview.lotCount}건 · 계정 {preview.accountCount}개 ·
            소멸 예정 {preview.forfeitedTotal.toLocaleString()}P
          </p>
        )}
        {expiryResult && (
          <p role="status" className="text-sm text-red-700">
            소멸 완료: 로트 {expiryResult.lotCount}건 ·
            소멸액 {expiryResult.forfeitedTotal.toLocaleString()}P
          </p>
        )}
      </section>
    </main>
  );
}
