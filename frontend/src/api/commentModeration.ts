import api from './axios';

/**
 * 댓글 통합 관리 API.
 *
 * <p>`@/api/board` 의 `boardCommentApi` 는 <b>글 하나 안</b>의 댓글만 다룬다. 문제 댓글을 내리려면
 * 그 댓글이 달린 글을 관리자가 먼저 찾아내야 했다는 뜻이다. 여기의 경로는 게시판·글을 건너뛰고
 * 댓글을 직접 훑는 유일한 경로다.
 */

export type ModeratedCommentStatus = 'PUBLISHED' | 'HIDDEN' | 'DELETED';
export type CommentReportReason = 'SPAM' | 'ABUSE' | 'ADULT' | 'PRIVACY' | 'ETC';
/** `RECEIVED` 는 아직 판정 전이고, 나머지 둘은 <b>어느 쪽으로 갈렸는지</b>를 남긴다. */
export type CommentReportStatus = 'RECEIVED' | 'HIDDEN' | 'KEPT';

export const REPORT_REASON_LABEL: Record<CommentReportReason, string> = {
  SPAM: '스팸·도배',
  ABUSE: '욕설·비방',
  ADULT: '음란물',
  PRIVACY: '개인정보 노출',
  ETC: '그 밖',
};

export const COMMENT_STATUS_LABEL: Record<ModeratedCommentStatus, string> = {
  PUBLISHED: '노출',
  HIDDEN: '가림',
  DELETED: '삭제',
};

export const REPORT_STATUS_LABEL: Record<CommentReportStatus, string> = {
  RECEIVED: '접수',
  HIDDEN: '가림 처리',
  KEPT: '유지 판정',
};

export interface ModeratedComment {
  id: number;
  postId: number;
  boardKey: string | null;
  boardName: string | null;
  /** 글이 지워졌으면 `null` 이다 — 빈 문자열로 채우지 않는다(제목 없는 글과 구분돼야 한다). */
  postTitle: string | null;
  authorId: number | null;
  authorName: string;
  /** 관리 콘솔은 <b>원문</b>을 받는다. 자리표시만 보이면 판정할 근거가 없다. */
  content: string;
  status: ModeratedCommentStatus;
  reportCount: number;
  createdAt: string;
}

export interface CommentReport {
  id: number;
  commentId: number;
  reporterName: string;
  reason: CommentReportReason;
  detail: string | null;
  status: CommentReportStatus;
  handledBy: string | null;
  handledAt: string | null;
  createdAt: string;
}

export interface ModerationPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CommentSearchParams {
  boardId?: number;
  status?: ModeratedCommentStatus;
  keyword?: string;
  authorId?: number;
  reportedOnly?: boolean;
  page?: number;
  size?: number;
}

const base = '/admin/boards/comments';

export const commentModerationApi = {
  search: async (params: CommentSearchParams = {}) =>
    (await api.get<ModerationPage<ModeratedComment>>(base, { params })).data,

  /** 되돌릴 수 있다. 삭제와 달리 원문이 남고, 되돌리면 다시 노출된다. */
  hide: async (commentId: number): Promise<void> => {
    await api.post(`${base}/${commentId}/hide`);
  },

  unhide: async (commentId: number): Promise<void> => {
    await api.post(`${base}/${commentId}/unhide`);
  },

  reportsOf: async (commentId: number) =>
    (await api.get<CommentReport[]>(`${base}/${commentId}/reports`)).data,

  /** 오래된 순으로 온다 — 최신순이면 밀린 건이 영영 뒤로 밀린다. */
  queue: async (params: { status?: CommentReportStatus; page?: number; size?: number } = {}) =>
    (await api.get<ModerationPage<CommentReport>>(`${base}/reports`, { params })).data,

  /** `HIDDEN` 판정은 대상 댓글도 같은 트랜잭션에서 내린다. `KEPT` 는 유지 판정만 남긴다. */
  resolve: async (reportId: number, decision: Exclude<CommentReportStatus, 'RECEIVED'>) =>
    (await api.post<CommentReport>(`${base}/reports/${reportId}/resolve`, { decision })).data,
};
