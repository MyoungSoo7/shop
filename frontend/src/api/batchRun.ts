import api from './axios';

/**
 * 배치 실행 원장 — order-service {@code AdminBatchRunController}.
 *
 * <p>지금까지 "어제 정산 배치가 돌았나?" 에 답하는 방법은 없었다. ShedLock 의 {@code shedlock}
 * 테이블은 <b>락을 잡았다는 사실</b>만 남기지 결과를 남기지 않는다 — 잡고 나서 예외로 죽어도
 * 그 행은 성공한 실행과 구별되지 않는다. 그래서 실행 원장을 따로 둔다.
 *
 * <p>{@code RUNNING} 이 남아 있는 행은 성공도 실패도 아니라 <b>끝을 못 본</b> 실행이다(파드가
 * 중간에 죽었거나 {@code lockAtMostFor} 를 넘겨 락이 풀린 경우). 두 값만 두면 실행이 사라진
 * 사고가 아무 흔적도 남기지 않으므로, 화면도 이 셋을 뭉개지 않는다.
 */

export type BatchRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface BatchRunView {
  id: number;
  batchName: string;
  runId: string;
  /** 이 실행이 처리한 <b>날짜분</b>. 실행 시각과 다르다 — 재실행은 옛 날짜분을 지금 돌린다. */
  targetDate: string;
  status: BatchRunStatus;
  startedAt: string;
  completedAt: string | null;
  processedCount: number | null;
  errorMessage: string | null;
  /**
   * {@code scheduler} 면 정규 실행, {@code rerun:admin:...} 이면 사람이 돌린 것.
   * 같은 날짜가 두 번 계산된 이유를 나중에 여기서 읽는다.
   */
  triggeredBy: string;
}

export interface RerunnableBatchView {
  batchName: string;
  description: string;
  supportsDryRun: boolean;
}

export interface RerunResult {
  batchName: string;
  targetDate: string;
  dryRun: boolean;
  processedCount: number;
}

export interface BatchRunPage {
  content: BatchRunView[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BatchRunSearchParams {
  batchName?: string;
  status?: BatchRunStatus;
  /** ISO 날짜(YYYY-MM-DD). 실행 시각이 아니라 처리 대상 날짜다. */
  targetDate?: string;
  page?: number;
  size?: number;
}

export const batchRunApi = {
  /** 실행 이력 — 최근 실행 순. 서버가 size 를 200 으로 상한한다. */
  search: async (params: BatchRunSearchParams): Promise<BatchRunPage> =>
    (await api.get<BatchRunPage>('/admin/batch-runs', { params })).data,

  /** 배치별 <b>가장 최근</b> 실행 1건 — 이 화면의 핵심. 뒤처진 배치가 곧 구멍이다. */
  latest: async (): Promise<BatchRunView[]> =>
    (await api.get<BatchRunView[]>('/admin/batch-runs/latest')).data,

  /** 날짜를 지정해 다시 돌릴 수 있는 배치. 여기 없는 배치는 재실행 대상이 아니다. */
  rerunnable: async (): Promise<RerunnableBatchView[]> =>
    (await api.get<RerunnableBatchView[]>('/admin/batch-runs/rerunnable')).data,

  /**
   * 놓친 날짜분 재실행.
   *
   * <p>{@code targetDate} 는 서버가 {@code @NotNull} 로 막는다 — 널로 내려가면 재실행이
   * "어느 날짜분인지 모르는 채" 돌기 때문이다. 화면도 같은 이유로 빈 날짜를 보내지 않는다.
   */
  rerun: async (batchName: string, targetDate: string, dryRun: boolean): Promise<RerunResult> =>
    (await api.post<RerunResult>(
      `/admin/batch-runs/${encodeURIComponent(batchName)}/rerun`,
      { targetDate, dryRun },
    )).data,
};
