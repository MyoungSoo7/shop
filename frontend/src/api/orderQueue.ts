import api from './axios';

/**
 * 작업 큐 — order-service {@code AdminOrderQueueController}.
 *
 * <p><b>주문 목록과 무엇이 다른가.</b> 주문 목록은 "무슨 주문이 있나"를 상태별로 센다. 여기는
 * 같은 주문을 <b>운영자가 해야 할 일</b> 단위로 묶고 <b>기한</b>을 매긴다. 건수만으로는 급한지
 * 알 수 없기 때문이다 — 환불 신청 3건이 오늘 들어온 것과 나흘 묵은 것은 전혀 다른 상황인데
 * 상태별 카운트에서는 똑같이 "3"이다.
 *
 * <p>그래서 이 API 가 주는 값의 핵심은 {@code count} 가 아니라 {@code overdueCount} 와
 * {@code oldestWaitingHours} 다. 화면도 그 둘을 먼저 보여야 한다.
 *
 * <p><b>{@code ageFromOrderDateCount} 를 그냥 지나치면 안 된다.</b> 대기 시작 시각은 상태 변경
 * 이력에서 읽는데, 이력이 없는 옛 주문은 주문 일시로 대신 잰다. 그 건들은 실제보다 <b>오래
 * 기다린 것처럼</b> 보인다. 서버는 그 대체가 정확한 큐(미결제)에서는 이 값을 0 으로 내려보내니,
 * 화면은 0 이 아닌 큐에만 "일부 추정"을 붙이면 된다.
 */

export interface QueueBucket {
  /** 서버 enum 이름. 화면 분기는 이 값으로 한다(라벨은 서버가 정본). */
  key: string;
  label: string;
  /** 이 큐가 포함하는 주문 상태들. 한 상태는 한 큐에만 들어간다(서버가 기동 때 검증). */
  statuses: string[];
  count: number;
  /** 가장 오래 기다린 건의 대기 시작 시각. 0건이면 null. */
  oldestWaitingSince: string | null;
  /** 그 건이 기다린 시간(시간 단위). 0건이면 null. */
  oldestWaitingHours: number | null;
  /** 이 큐의 기한(시간). 배송 장기 체류만 168h 인 이유는 시계가 택배사 쪽에서 돌기 때문이다. */
  slaHours: number;
  /** 기한을 넘긴 건수 — 이 화면에서 가장 먼저 봐야 할 숫자다. */
  overdueCount: number;
  /**
   * 대기 시작을 이력이 아니라 주문 일시로 대신 잰 건수. 0 이 아니면 이 큐의 대기 시간은
   * <b>과대평가</b>돼 있다. 서버가 정확한 큐에서는 0 을 준다.
   */
  ageFromOrderDateCount: number;
}

export interface OrderQueues {
  /** 집계 기준 시각. 대기 시간은 전부 이 시각에서 뺀 값이다. */
  asOf: string;
  /** 순서가 화면 순서 — 돈이 묶여 있고 되돌리기 어려운 것부터다. 화면이 다시 정렬하지 않는다. */
  buckets: QueueBucket[];
}

export interface QueueExportResult {
  blob: Blob;
  fileName: string;
  /** 파일이 어느 시점의 스냅샷인지. 큐는 계속 움직이므로 파일만 봐서는 알 수 없다. */
  asOf: string | null;
}

/** 큐 전체 건수 — 서버 {@code OrderQueues.totalCount()} 와 같은 값을 화면에서 쓴다. */
export const totalCount = (queues: OrderQueues): number =>
  queues.buckets.reduce((sum, bucket) => sum + bucket.count, 0);

/** 기한 초과 총합. 이 값이 0 인지 아닌지가 "지금 손댈 일이 있나"의 답이다. */
export const totalOverdue = (queues: OrderQueues): number =>
  queues.buckets.reduce((sum, bucket) => sum + bucket.overdueCount, 0);

export const orderQueueApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  list: async (): Promise<OrderQueues> =>
    (await api.get<OrderQueues>('/admin/order-queues')).data,

  export: async (): Promise<QueueExportResult> => {
    const response = await api.get<Blob>('/admin/order-queues/export', { responseType: 'blob' });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'order_queues.csv',
      asOf: response.headers['x-export-as-of'] === undefined
        ? null
        : String(response.headers['x-export-as-of']),
    };
  },
};
