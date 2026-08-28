import { useCallback, useEffect, useState } from 'react';
import {
  promotionAdminApi,
  hasDrawablePrize,
  CAMPAIGN_STATUS_LABEL,
  type AttendanceCampaignResponse,
  type AttendanceCampaignRequest,
  type LuckyboxCampaignResponse,
  type LuckyboxCampaignRequest,
  type LuckyboxPrizeResponse,
  type LuckyboxPrizeRequest,
} from '@/api/promotion';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 이벤트 프로모션 관리 — 출석체크·럭키박스 캠페인을 <b>운영자가</b> 만들고 여닫는 화면.
 *
 * <p><b>경로가 {@code /admin/system/**} 아래인 이유.</b> nginx SPA 폴백은 {@code /admin} 하위
 * 중 정해진 네비 그룹(system·operation·shipping·approvals·login)만 index.html 로 돌려준다.
 * 그 밖의 경로는 새로고침에서 404 가 되고, 그 사실은 개발 서버에서는 드러나지 않는다.
 *
 * <p><b>만들면 바로 열리지 않는다.</b> 등록은 {@code DRAFT} 로 끝나고, 여는 것은 별도 버튼이다.
 * 레거시는 등록하는 순간 노출됐다 — 문구를 다듬는 동안 이미 구매자가 보고 있었다.
 *
 * <p><b>경품 없는 럭키박스는 열리지 않는다.</b> 서버가 거절하고, 이 화면도 버튼을 미리 잠근다.
 * 레거시에서는 열렸고, 참여하면 아무 일도 일어나지 않은 채 참여 횟수만 소진됐다.
 *
 * <p><b>경품은 지우지 않고 끈다.</b> 이미 당첨된 사람의 기록이 그 경품을 참조한다 — 지우면
 * 지난 당첨이 무엇이었는지 말할 수 없게 된다.
 */

const TENANT_REF = 'default';

const emptyAttendance = (): AttendanceCampaignRequest => ({
  tenantRef: TENANT_REF,
  name: '',
  periodType: 'MONTHLY',
  startsOn: '',
  endsOn: '',
  streakRule: 'CONSECUTIVE',
  requiredCount: 5,
  dayTypeRule: 'EVERY_DAY',
  dailyRewardPoints: null,
  goalRewardPoints: null,
  rewardExpiresFrom: null,
  rewardExpiresOn: null,
  pcImageUrl: null,
  mobileImageUrl: null,
  messageBeforeStart: null,
  messageRunning: null,
  messageAchieved: null,
  messageClosed: null,
});

const emptyLuckybox = (): LuckyboxCampaignRequest => ({
  tenantRef: TENANT_REF,
  name: '',
  startsOn: '',
  endsOn: '',
  benefitType: 'IMMEDIATE',
  benefitOn: null,
  entryCondition: 'ALL_MEMBERS',
  memberJoinedFrom: null,
  rewardExpiresOn: null,
  amountBasis: null,
  minOrderAmount: null,
  shippingStatusRequired: null,
  note: null,
  pcImageUrl: null,
  mobileImageUrl: null,
});

const emptyPrize = (): LuckyboxPrizeRequest => ({
  prizeType: 'POINT',
  rewardPoints: '100',
  textReward: null,
  totalQuota: null,
  dailyQuota: null,
  winRate: '1',
  displayOrder: 1,
});

const blankToNull = (value: string): string | null => (value.trim() === '' ? null : value);

const statusLabel = (status: string) => CAMPAIGN_STATUS_LABEL[status] ?? status;

export default function PromotionAdminPage() {
  const [attendance, setAttendance] = useState<AttendanceCampaignResponse[]>([]);
  const [luckybox, setLuckybox] = useState<LuckyboxCampaignResponse[]>([]);
  const [prizes, setPrizes] = useState<LuckyboxPrizeResponse[]>([]);
  const [selectedLuckybox, setSelectedLuckybox] = useState<string | null>(null);

  const [attendanceForm, setAttendanceForm] = useState<AttendanceCampaignRequest>(emptyAttendance);
  const [luckyboxForm, setLuckyboxForm] = useState<LuckyboxCampaignRequest>(emptyLuckybox);
  const [prizeForm, setPrizeForm] = useState<LuckyboxPrizeRequest>(emptyPrize);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    try {
      const [attendanceList, luckyboxList] = await Promise.all([
        promotionAdminApi.listAttendance(),
        promotionAdminApi.listLuckybox(),
      ]);
      setAttendance(attendanceList);
      setLuckybox(luckyboxList);
      setError(null);
    } catch (err) {
      setError(apiErrorMessage(err, '캠페인을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  // 경품은 캠페인을 고른 뒤에만 부른다. 목록을 그릴 때 전부 미리 부르면 캠페인 수만큼
  // 왕복이 생기고, 그중 대부분은 아무도 안 여는 캠페인이다.
  useEffect(() => {
    let cancelled = false;
    if (selectedLuckybox === null) { setPrizes([]); return () => { cancelled = true; }; }
    (async () => {
      try {
        const list = await promotionAdminApi.prizes(selectedLuckybox);
        if (!cancelled) setPrizes(list);
      } catch (err) {
        if (!cancelled) setError(apiErrorMessage(err, '경품을 불러오지 못했습니다.'));
      }
    })();
    return () => { cancelled = true; };
  }, [selectedLuckybox]);

  const run = async (action: () => Promise<string>) => {
    setError(null);
    try {
      setNotice(await action());
      await reload();
    } catch (err) {
      setError(apiErrorMessage(err, '요청에 실패했습니다.'));
    }
  };

  const createAttendance = () => run(async () => {
    await promotionAdminApi.createAttendance(attendanceForm);
    setAttendanceForm(emptyAttendance());
    return '출석 캠페인을 만들었습니다. 아직 작성 중이며, 열어야 노출됩니다.';
  });

  const createLuckybox = () => run(async () => {
    await promotionAdminApi.createLuckybox(luckyboxForm);
    setLuckyboxForm(emptyLuckybox());
    return '럭키박스 캠페인을 만들었습니다. 경품을 붙인 뒤에 열 수 있습니다.';
  });

  const addPrize = (campaignId: string) => run(async () => {
    await promotionAdminApi.addPrize(campaignId, prizeForm);
    setPrizeForm(emptyPrize());
    setPrizes(await promotionAdminApi.prizes(campaignId));
    return '경품을 추가했습니다.';
  });

  const deactivatePrize = (campaignId: string, prizeId: string) => run(async () => {
    await promotionAdminApi.deactivatePrize(prizeId);
    setPrizes(await promotionAdminApi.prizes(campaignId));
    return '경품을 껐습니다. 지난 당첨 기록은 그대로 남습니다.';
  });

  const selectedPrizesReady = hasDrawablePrize(prizes);

  return (
    <main className="space-y-8 p-6">
      <header>
        <h1 className="text-2xl font-bold">이벤트 프로모션 관리</h1>
        <p className="text-sm text-gray-500">
          출석체크·럭키박스 캠페인. 등록은 작성 중 상태로 끝나며, 열어야 구매자에게 보입니다.
        </p>
      </header>

      {error !== null && <p role="alert" className="text-red-600">{error}</p>}
      {notice !== null && <p className="text-green-700" data-testid="promotion-admin-notice">{notice}</p>}
      {loading && <p className="text-gray-500">불러오는 중…</p>}

      <section className="space-y-3">
        <h2 className="text-xl font-semibold">출석체크</h2>
        {attendance.length === 0 ? (
          <p className="text-gray-500" data-testid="attendance-empty">등록된 출석 캠페인이 없습니다.</p>
        ) : (
          <table className="w-full text-sm" data-testid="attendance-table">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">이름</th><th>상태</th><th>기간</th><th>달성</th><th />
              </tr>
            </thead>
            <tbody>
              {attendance.map((campaign) => (
                <tr key={campaign.id} className="border-b" data-testid={`attendance-row-${campaign.id}`}>
                  <td className="py-2">{campaign.name}</td>
                  <td>{statusLabel(campaign.status)}</td>
                  <td>{campaign.startsOn} ~ {campaign.endsOn}</td>
                  <td>{campaign.requiredCount}일</td>
                  <td className="space-x-2 text-right">
                    {campaign.status === 'DRAFT' && (
                      <button
                        type="button"
                        data-testid={`attendance-open-${campaign.id}`}
                        onClick={() => run(async () => {
                          await promotionAdminApi.openAttendance(campaign.id);
                          return `'${campaign.name}' 을 열었습니다.`;
                        })}
                        className="rounded bg-blue-600 px-2 py-1 text-white"
                      >
                        열기
                      </button>
                    )}
                    {campaign.status === 'RUNNING' && (
                      <button
                        type="button"
                        data-testid={`attendance-close-${campaign.id}`}
                        onClick={() => run(async () => {
                          await promotionAdminApi.closeAttendance(campaign.id);
                          return `'${campaign.name}' 을 닫았습니다.`;
                        })}
                        className="rounded border px-2 py-1"
                      >
                        닫기
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <form
          className="grid gap-2 rounded border p-3 sm:grid-cols-2"
          data-testid="attendance-form"
          onSubmit={(event) => { event.preventDefault(); void createAttendance(); }}
        >
          <label className="text-sm">
            이름
            <input
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.name}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, name: e.target.value })}
              aria-label="출석 캠페인 이름"
            />
          </label>
          <label className="text-sm">
            달성 일수
            <input
              type="number"
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.requiredCount}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, requiredCount: Number(e.target.value) })}
              aria-label="출석 달성 일수"
            />
          </label>
          <label className="text-sm">
            시작일
            <input
              type="date"
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.startsOn}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, startsOn: e.target.value })}
              aria-label="출석 시작일"
            />
          </label>
          <label className="text-sm">
            종료일
            <input
              type="date"
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.endsOn}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, endsOn: e.target.value })}
              aria-label="출석 종료일"
            />
          </label>
          <label className="text-sm">
            하루 보상(P)
            <input
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.dailyRewardPoints ?? ''}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, dailyRewardPoints: blankToNull(e.target.value) })}
              aria-label="하루 보상"
            />
          </label>
          <label className="text-sm">
            달성 보상(P)
            <input
              className="w-full rounded border px-2 py-1"
              value={attendanceForm.goalRewardPoints ?? ''}
              onChange={(e) => setAttendanceForm({ ...attendanceForm, goalRewardPoints: blankToNull(e.target.value) })}
              aria-label="달성 보상"
            />
          </label>
          <div className="sm:col-span-2">
            <button type="submit" className="rounded bg-blue-600 px-3 py-1 text-white" data-testid="attendance-create">
              출석 캠페인 등록
            </button>
          </div>
        </form>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold">럭키박스</h2>
        {luckybox.length === 0 ? (
          <p className="text-gray-500" data-testid="luckybox-empty">등록된 럭키박스 캠페인이 없습니다.</p>
        ) : (
          <table className="w-full text-sm" data-testid="luckybox-table">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">이름</th><th>상태</th><th>기간</th><th /><th />
              </tr>
            </thead>
            <tbody>
              {luckybox.map((campaign) => (
                <tr key={campaign.id} className="border-b" data-testid={`luckybox-row-${campaign.id}`}>
                  <td className="py-2">{campaign.name}</td>
                  <td>{statusLabel(campaign.status)}</td>
                  <td>{campaign.startsOn} ~ {campaign.endsOn}</td>
                  <td>
                    <button
                      type="button"
                      data-testid={`luckybox-select-${campaign.id}`}
                      onClick={() => setSelectedLuckybox(campaign.id)}
                      aria-current={campaign.id === selectedLuckybox ? 'true' : undefined}
                      className="rounded border px-2 py-1"
                    >
                      경품
                    </button>
                  </td>
                  <td className="space-x-2 text-right">
                    {campaign.status === 'DRAFT' && (
                      <button
                        type="button"
                        data-testid={`luckybox-open-${campaign.id}`}
                        // 고른 캠페인의 경품만 확인할 수 있다. 다른 캠페인은 서버가 거절한다.
                        disabled={campaign.id === selectedLuckybox && !selectedPrizesReady}
                        onClick={() => run(async () => {
                          await promotionAdminApi.openLuckybox(campaign.id);
                          return `'${campaign.name}' 을 열었습니다.`;
                        })}
                        className="rounded bg-blue-600 px-2 py-1 text-white disabled:bg-gray-300"
                      >
                        열기
                      </button>
                    )}
                    {campaign.status === 'RUNNING' && (
                      <button
                        type="button"
                        data-testid={`luckybox-close-${campaign.id}`}
                        onClick={() => run(async () => {
                          await promotionAdminApi.closeLuckybox(campaign.id);
                          return `'${campaign.name}' 을 닫았습니다.`;
                        })}
                        className="rounded border px-2 py-1"
                      >
                        닫기
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <form
          className="grid gap-2 rounded border p-3 sm:grid-cols-2"
          data-testid="luckybox-form"
          onSubmit={(event) => { event.preventDefault(); void createLuckybox(); }}
        >
          <label className="text-sm">
            이름
            <input
              className="w-full rounded border px-2 py-1"
              value={luckyboxForm.name}
              onChange={(e) => setLuckyboxForm({ ...luckyboxForm, name: e.target.value })}
              aria-label="럭키박스 캠페인 이름"
            />
          </label>
          <label className="text-sm">
            참여 조건
            <select
              className="w-full rounded border px-2 py-1"
              value={luckyboxForm.entryCondition}
              onChange={(e) => setLuckyboxForm({ ...luckyboxForm, entryCondition: e.target.value })}
              aria-label="참여 조건"
            >
              <option value="ALL_MEMBERS">전체 회원</option>
              <option value="NEW_MEMBER">신규 회원</option>
              <option value="PURCHASER">구매자</option>
            </select>
          </label>
          <label className="text-sm">
            시작일
            <input
              type="date"
              className="w-full rounded border px-2 py-1"
              value={luckyboxForm.startsOn}
              onChange={(e) => setLuckyboxForm({ ...luckyboxForm, startsOn: e.target.value })}
              aria-label="럭키박스 시작일"
            />
          </label>
          <label className="text-sm">
            종료일
            <input
              type="date"
              className="w-full rounded border px-2 py-1"
              value={luckyboxForm.endsOn}
              onChange={(e) => setLuckyboxForm({ ...luckyboxForm, endsOn: e.target.value })}
              aria-label="럭키박스 종료일"
            />
          </label>
          <div className="sm:col-span-2">
            <button type="submit" className="rounded bg-blue-600 px-3 py-1 text-white" data-testid="luckybox-create">
              럭키박스 캠페인 등록
            </button>
          </div>
        </form>
      </section>

      {selectedLuckybox !== null && (
        <section className="space-y-3" data-testid="prize-panel">
          <h2 className="text-xl font-semibold">경품</h2>
          {!selectedPrizesReady && (
            <p className="text-amber-700" data-testid="prize-not-ready">
              뽑을 수 있는 경품이 없습니다. 이 상태로는 캠페인을 열 수 없습니다.
            </p>
          )}
          {prizes.length > 0 && (
            <ul className="space-y-1 text-sm" data-testid="prize-list">
              {prizes.map((prize) => (
                <li key={prize.id} className="flex items-center gap-3 border-b py-1">
                  <span>{prize.rewardPoints ?? prize.textReward ?? '꽝'}</span>
                  {/* 가중치는 확률이 아니다 — 활성 경품들의 합으로 정규화된다. */}
                  <span className="text-gray-500">가중치 {prize.winRate}</span>
                  <span className="text-gray-500">
                    지급 {prize.issuedCount}{prize.totalQuota === null ? '' : ` / ${prize.totalQuota}`}
                  </span>
                  <span className="text-gray-500">{prize.active ? '사용' : '꺼짐'}</span>
                  {prize.active && (
                    <button
                      type="button"
                      data-testid={`prize-deactivate-${prize.id}`}
                      onClick={() => deactivatePrize(selectedLuckybox, prize.id)}
                      className="rounded border px-2 py-0.5"
                    >
                      끄기
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          <form
            className="grid gap-2 rounded border p-3 sm:grid-cols-3"
            data-testid="prize-form"
            onSubmit={(event) => { event.preventDefault(); void addPrize(selectedLuckybox); }}
          >
            <label className="text-sm">
              보상 포인트
              <input
                className="w-full rounded border px-2 py-1"
                value={prizeForm.rewardPoints ?? ''}
                onChange={(e) => setPrizeForm({ ...prizeForm, rewardPoints: blankToNull(e.target.value) })}
                aria-label="경품 보상 포인트"
              />
            </label>
            <label className="text-sm">
              가중치
              <input
                className="w-full rounded border px-2 py-1"
                value={prizeForm.winRate}
                onChange={(e) => setPrizeForm({ ...prizeForm, winRate: e.target.value })}
                aria-label="경품 가중치"
              />
            </label>
            <label className="text-sm">
              총 수량(비우면 무제한)
              <input
                className="w-full rounded border px-2 py-1"
                value={prizeForm.totalQuota ?? ''}
                onChange={(e) => setPrizeForm({
                  ...prizeForm,
                  totalQuota: e.target.value.trim() === '' ? null : Number(e.target.value),
                })}
                aria-label="경품 총 수량"
              />
            </label>
            <div className="sm:col-span-3">
              <button type="submit" className="rounded bg-blue-600 px-3 py-1 text-white" data-testid="prize-add">
                경품 추가
              </button>
            </div>
          </form>
        </section>
      )}
    </main>
  );
}
