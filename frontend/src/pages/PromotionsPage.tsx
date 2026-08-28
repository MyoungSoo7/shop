import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  promotionApi,
  type AttendanceBoard,
  type CheckInResult,
  type DrawResult,
  type LuckyboxBoard,
  type PromotionSummary,
} from '@/api/promotion';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 이벤트 — 출석체크·럭키박스를 구매자가 <b>참여하는</b> 화면.
 *
 * <p><b>왜 생겼나.</b> marketing-service 를 떼어 오면서 API 는 다 옮겼지만 그것을 구매자가
 * 여는 길이 없었다. 화면 없는 API 는 저장소의 화면-API 대조 게이트가 잡아 주는데, 그 게이트의
 * 대상 목록에 새 서비스가 아직 없어 <b>빚이 보이지도 않는</b> 상태였다. 화면을 먼저 만들고
 * 게이트 대상에 넣는다 — 반대로 하면 예산만 늘리는 셈이다.
 *
 * <p><b>경로가 {@code /events} 나 {@code /promotions/…} 가 아닌 이유.</b> nginx 두 벌이
 * 게이트웨이로 넘기는 API 세그먼트 목록에 {@code promotions} 는 없다. 그래서 화면 경로
 * {@code /promotions} 는 새로고침해도 JSON 이 뜨지 않는다 — 반대로 {@code categories} 나
 * {@code orders} 였다면 떴다('내 문의'·'카테고리 탐색'이 그 이유로 지금 자리에 있다).
 *
 * <p><b>고른 이벤트는 주소에 남긴다</b>({@code ?promotion=아이디}). 출석은 하루 한 번뿐이라
 * "이 이벤트 지금 열어 봐" 라고 보낼 주소가 실제로 필요하다.
 *
 * <p><b>적립을 "완료"라고 적지 않는다.</b> 포인트 원장은 order-service 에 있고 marketing 은
 * 적립을 <i>요청</i>한다(ADR 0045). 응답의 {@code rewardPending} 이 참이면 잔액은 아직 오르지
 * 않았다. 레거시는 화면에서 바로 "지급 완료"라고 적었고, 지급이 실패해도 그 문구는 그대로였다.
 */

const formatPoints = (value: string | null) =>
  (value === null ? null : `${Number(value).toLocaleString('ko-KR')}P`);

const REWARD_PENDING_NOTE = '포인트는 잠시 뒤 잔액에 반영됩니다.';

function AttendanceView({ board, onCheckIn, result, busy }: {
  board: AttendanceBoard;
  onCheckIn: () => void;
  result: CheckInResult | null;
  busy: boolean;
}) {
  const daily = formatPoints(board.dailyRewardPoints);
  const goal = formatPoints(board.goalRewardPoints);
  return (
    <section className="space-y-4" data-testid="attendance-board">
      <header>
        <h2 className="text-xl font-semibold">{board.name}</h2>
        <p className="text-sm text-gray-500">
          {board.startsOn} ~ {board.endsOn} · {board.requiredCount}일 달성
          {daily !== null && ` · 하루 ${daily}`}
          {goal !== null && ` · 달성 ${goal}`}
        </p>
      </header>

      {board.message !== null && (
        <p className="rounded bg-gray-50 p-3 text-sm text-gray-700" data-testid="attendance-message">
          {board.message}
        </p>
      )}

      <p className="text-sm text-gray-700" data-testid="attendance-counts">
        누적 {board.attendedTotal}일 · 연속 {board.attendedStreak}일 · 달성 {board.achievedCount}회
      </p>

      {/* 달력은 집계 창(windowStart~windowEnd)만 그린다. 그 바깥 날짜는 이번 회차의 누적에
          들어가지 않아서, 함께 그리면 "찍었는데 안 세네" 로 보인다. */}
      <ul className="flex flex-wrap gap-1" data-testid="attendance-days">
        {board.days.map((day) => (
          <li
            key={day.date}
            data-testid={`attendance-day-${day.date}`}
            aria-label={`${day.date} ${day.attended ? '출석' : day.eligible ? '미출석' : '인정 안 함'}`}
            className={`w-10 rounded border py-1 text-center text-xs ${
              day.attended ? 'border-blue-500 bg-blue-50 font-semibold text-blue-700'
                : day.eligible ? 'text-gray-700' : 'bg-gray-100 text-gray-400'
            }`}
          >
            {day.date.slice(8)}
          </li>
        ))}
      </ul>

      <button
        type="button"
        onClick={onCheckIn}
        disabled={busy || board.checkedInToday || !board.eligibleToday}
        data-testid="attendance-check-in"
        className="rounded bg-blue-600 px-4 py-2 text-white disabled:bg-gray-300"
      >
        {board.checkedInToday ? '오늘 출석 완료'
          : !board.eligibleToday ? '오늘은 출석 인정일이 아닙니다'
            : '출석 체크'}
      </button>

      {result !== null && (
        <div className="rounded border border-blue-200 bg-blue-50 p-3 text-sm" data-testid="check-in-result">
          <p>{result.attendedOn} 출석했습니다. 누적 {result.attendedTotal}일 · 연속 {result.attendedStreak}일.</p>
          {result.goalReached && <p data-testid="check-in-goal">목표 달성! {formatPoints(result.goalRewardPoints)}</p>}
          {/* 적립은 비동기다 — 여기서 "완료"라고 적으면 거짓말이 될 수 있다. */}
          {result.rewardPending && <p className="text-gray-600">{REWARD_PENDING_NOTE}</p>}
        </div>
      )}
    </section>
  );
}

function LuckyboxView({ board, onDraw, result, busy }: {
  board: LuckyboxBoard;
  onDraw: () => void;
  result: DrawResult | null;
  busy: boolean;
}) {
  const prizeLabel = (points: string | null, text: string | null) =>
    formatPoints(points) ?? text ?? '꽝';
  return (
    <section className="space-y-4" data-testid="luckybox-board">
      <header>
        <h2 className="text-xl font-semibold">{board.name}</h2>
        <p className="text-sm text-gray-500">{board.startsOn} ~ {board.endsOn}</p>
      </header>

      {board.note !== null && (
        <p className="rounded bg-gray-50 p-3 text-sm text-gray-700" data-testid="luckybox-note">
          {board.note}
        </p>
      )}

      {/* 경품은 이름만 보여 준다. 당첨 가중치는 내려오지도 않는다 — 내려오면 "확률 낮은 걸
          왜 넣었냐" 는 문의가 아니라 그 수치를 근거로 한 분쟁이 된다. */}
      <ul className="grid gap-2 sm:grid-cols-2" data-testid="luckybox-prizes">
        {board.prizes.map((prize) => (
          <li key={prize.id} className="rounded border p-2 text-sm">
            {prizeLabel(prize.rewardPoints, prize.textReward)}
          </li>
        ))}
      </ul>

      <button
        type="button"
        onClick={onDraw}
        disabled={busy || !board.drawableNow || board.alreadyDrawnInSlot}
        data-testid="luckybox-draw"
        className="rounded bg-blue-600 px-4 py-2 text-white disabled:bg-gray-300"
      >
        {board.alreadyDrawnInSlot ? '이번 회차에 이미 참여했습니다'
          : !board.drawableNow ? '지금은 참여할 수 없습니다'
            : '뽑기'}
      </button>

      {result !== null && (
        <div className="rounded border border-blue-200 bg-blue-50 p-3 text-sm" data-testid="draw-result">
          <p>{prizeLabel(result.rewardPoints, result.textReward)} 당첨!</p>
          {/* 일괄 지급 캠페인은 뽑은 날과 주는 날이 다르다. 안 적으면 "안 들어왔다" 문의가 된다. */}
          {result.scheduledOn !== null && (
            <p className="text-gray-600" data-testid="draw-scheduled">
              {result.scheduledOn} 에 일괄 지급됩니다.
            </p>
          )}
          {result.rewardPending && result.scheduledOn === null && (
            <p className="text-gray-600">{REWARD_PENDING_NOTE}</p>
          )}
        </div>
      )}

      {board.myDraws.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-gray-700">내 참여 기록</h3>
          <ul className="text-sm text-gray-600" data-testid="luckybox-my-draws">
            {board.myDraws.map((draw) => (
              <li key={draw.drawId}>
                {draw.drawnOn} · {prizeLabel(draw.rewardPoints, draw.textReward)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

export default function PromotionsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedId = searchParams.get('promotion');

  const [running, setRunning] = useState<PromotionSummary[]>([]);
  const [attendance, setAttendance] = useState<AttendanceBoard | null>(null);
  const [luckybox, setLuckybox] = useState<LuckyboxBoard | null>(null);
  const [checkIn, setCheckIn] = useState<CheckInResult | null>(null);
  const [draw, setDraw] = useState<DrawResult | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [boardError, setBoardError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [busy, setBusy] = useState(false);

  const selected = running.find((item) => item.id === selectedId) ?? null;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await promotionApi.running();
        if (!cancelled) setRunning(list);
      } catch (err) {
        if (!cancelled) setListError(apiErrorMessage(err, '이벤트를 불러오지 못했습니다.'));
      } finally {
        if (!cancelled) setLoadingList(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // 고른 이벤트의 판을 불러온다. 종류에 따라 부르는 API 가 다르고, 둘을 동시에 띄우지 않는다 —
  // 한 화면에 출석판과 뽑기판이 같이 있으면 어느 쪽 버튼을 눌렀는지가 흐려진다.
  const loadBoard = useCallback(async (target: PromotionSummary | null) => {
    setBoardError(null);
    setActionError(null);
    setCheckIn(null);
    setDraw(null);
    if (target === null) { setAttendance(null); setLuckybox(null); return; }
    try {
      if (target.kind === 'ATTENDANCE') {
        setLuckybox(null);
        setAttendance(await promotionApi.attendanceBoard(target.id));
      } else {
        setAttendance(null);
        setLuckybox(await promotionApi.luckyboxBoard(target.id));
      }
    } catch (err) {
      setAttendance(null);
      setLuckybox(null);
      setBoardError(apiErrorMessage(err, '이벤트를 불러오지 못했습니다.'));
    }
  }, []);

  useEffect(() => {
    if (loadingList) return;
    void loadBoard(selected);
    // selected 는 목록과 주소에서 파생된다. 객체 참조가 아니라 아이디로 의존을 걸어야
    // 목록이 다시 그려질 때마다 판을 새로 부르지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, loadingList, running.length]);

  const select = (item: PromotionSummary) => setSearchParams({ promotion: item.id });

  const runAction = async (action: () => Promise<void>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
    } catch (err) {
      setActionError(apiErrorMessage(err, '참여에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  // 참여 뒤 판을 다시 받는다. 화면이 계산해서 고치면(누적 +1 같은) 서버의 판정과 갈린다 —
  // 이미 참여한 상태에서 한 번 더 눌렀을 때가 특히 그렇다.
  const doCheckIn = () => runAction(async () => {
    setCheckIn(await promotionApi.checkIn(selected?.id));
    if (selected !== null) setAttendance(await promotionApi.attendanceBoard(selected.id));
  });

  const doDraw = () => runAction(async () => {
    setDraw(await promotionApi.draw(selected?.id));
    if (selected !== null) setLuckybox(await promotionApi.luckyboxBoard(selected.id));
  });

  return (
    <main className="mx-auto max-w-4xl space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">이벤트</h1>
        <p className="text-sm text-gray-500">
          진행 중인 출석체크·럭키박스입니다. 고른 이벤트는 주소에 남아 링크로 공유됩니다.
        </p>
      </header>

      {listError !== null && <p role="alert" className="text-red-600">{listError}</p>}

      {loadingList ? (
        <p className="text-gray-500">불러오는 중…</p>
      ) : running.length === 0 ? (
        <p className="text-gray-500" data-testid="promotions-empty">진행 중인 이벤트가 없습니다.</p>
      ) : (
        <ul className="flex flex-wrap gap-2" data-testid="promotion-list">
          {running.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => select(item)}
                aria-current={item.id === selectedId ? 'true' : undefined}
                data-testid={`promotion-${item.id}`}
                className={`rounded border px-3 py-2 text-sm ${
                  item.id === selectedId ? 'border-blue-500 bg-blue-50 font-semibold text-blue-700' : ''
                }`}
              >
                {item.kind === 'ATTENDANCE' ? '출석' : '럭키박스'} · {item.name}
              </button>
            </li>
          ))}
        </ul>
      )}

      {boardError !== null && <p role="alert" className="text-red-600">{boardError}</p>}
      {actionError !== null && <p role="alert" className="text-red-600" data-testid="action-error">{actionError}</p>}

      {selected === null && running.length > 0 && (
        <p className="text-gray-500" data-testid="promotion-no-selection">위에서 이벤트를 고르세요.</p>
      )}

      {attendance !== null && (
        <AttendanceView board={attendance} onCheckIn={doCheckIn} result={checkIn} busy={busy} />
      )}
      {luckybox !== null && (
        <LuckyboxView board={luckybox} onDraw={doDraw} result={draw} busy={busy} />
      )}
    </main>
  );
}
