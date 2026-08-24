import React, { useCallback, useEffect, useState } from 'react';
import { pgRoutingApi, type PgHealth } from '@/api/pgRouting';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * PG 라우터 상태 카드 — 장애 중에 "지금 결제가 어느 PG 로 나가고 있는가"를 즉시 본다.
 *
 * <p>각 PG 어댑터의 CircuitBreaker 가 OPEN 이면 라우터가 그 PG 를 후보에서 제외한다. 그래서
 * 하나가 내려가도 결제는 계속되는데, <b>어느 것이 빠졌는지가 화면에 없으면</b> 결제 실패율이
 * 왜 움직였는지 설명할 수 없다.
 *
 * <p><b>조작이 없다.</b> 서버가 읽기 전용이다(설정 엔드포인트가 없다). 여기 버튼을 두면
 * 있지도 않은 제어를 암시하게 된다 — 복구는 서킷브레이커가 스스로 하거나 PG 사 쪽 일이다.
 *
 * <p><b>PG 목록을 화면이 짓지 않는다.</b> 서버 응답의 키를 그대로 그린다 — 목록을 화면이 들고
 * 있으면 PG 가 추가될 때 조용히 빠진다.
 */
const PgRoutingHealthCard: React.FC = () => {
  const [health, setHealth] = useState<PgHealth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setHealth(await pgRoutingApi.health());
    } catch (err) {
      // 조회 실패를 "전부 정상"으로 그리면 장애 중에 정반대 신호를 준다.
      setHealth(null);
      setError(apiErrorMessage(err, 'PG 라우터 상태를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const entries = Object.entries(health?.providers ?? {});
  const down = entries.filter(([, ok]) => !ok).map(([name]) => name);

  return (
    <section className="rounded-xl border border-gray-200 bg-white p-4 mb-6 space-y-3"
      data-testid="pg-routing-card">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900">PG 라우터 상태</h2>
          <p className="mt-1 text-sm text-gray-500">
            서킷브레이커가 열린 PG 는 라우터가 후보에서 제외합니다 — 결제는 계속되지만
            어느 경로로 나가는지가 달라집니다.
          </p>
        </div>
        {/* 이름이 그냥 '새로고침'이면 운영 관제의 전체 새로고침 버튼과 구분되지 않는다 —
            보이는 화면에서는 위치로 알 수 있지만 스크린리더에는 같은 버튼 둘이다.
            (게다가 로딩 중엔 문구가 바뀌어 개수가 순간마다 달라지므로 조회가 불안정해진다.) */}
        <button type="button" onClick={() => void load()} disabled={loading}
          className="shrink-0 rounded border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 disabled:opacity-50">
          {loading ? '조회 중…' : 'PG 상태 새로고침'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {health && (
        <>
          <p className={`rounded p-2 text-sm ${health.healthy ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'}`}
            data-testid="pg-overall">
            {health.healthy
              ? '모든 PG 가 정상입니다.'
              : <>중단된 PG <b>{down.length}곳</b>: {down.join(', ')} — 결제가 나머지로만 나갑니다.</>}
          </p>

          <ul className="flex flex-wrap gap-2" data-testid="pg-list">
            {entries.map(([name, ok]) => (
              <li key={name}
                data-testid={`pg-${name}`}
                className={`rounded px-3 py-1.5 text-sm font-mono ${
                  ok ? 'bg-gray-100 text-gray-800' : 'bg-red-100 font-bold text-red-800'}`}>
                {name} {ok ? '정상' : '중단'}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
};

export default PgRoutingHealthCard;
