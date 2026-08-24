import api from './axios';

/**
 * 다중 PG 라우터 상태 점검 — order-service {@code PgRoutingController} (`GET /admin/pg/health`).
 *
 * <p><b>읽기 전용이다.</b> 설정 엔드포인트가 없다 — 각 PG 어댑터의 CircuitBreaker 가 OPEN 이면
 * {@code false} 로 내려오고, 라우터는 false 인 PG 를 후보에서 제외한다. 즉 이 화면은
 * "지금 결제가 어느 PG 로 나가고 있는가"를 장애 중에 즉시 보는 자리다.
 *
 * <p>서버 주석이 Grafana 의 {@code pg.routing.requests} 카운터와 교차 분석하라고 적어 둔
 * 표면이라, 화면은 <b>스냅샷 한 장</b>이면 충분하다. 조작 버튼을 만들 자리가 애초에 없다.
 */

export interface PgHealth {
  /** PG 코드 → 사용 가능 여부. 서버 enum(TOSS·KCP·NICE·INICIS·MOCK)이 정본이라 화면이 목록을 짓지 않는다. */
  providers: Record<string, boolean>;
  /** 하나라도 OPEN 이면 false. */
  healthy: boolean;
}

export const pgRoutingApi = {
  health: async (): Promise<PgHealth> =>
    (await api.get<PgHealth>('/admin/pg/health')).data,
};
