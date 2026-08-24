import api from './axios';

/**
 * 게시판(board-service) API.
 *
 * <p>메타 주도 게시판 — 정의 1행이 게시판 1개이고, 프론트는 단일 라우트가 정의를 읽어 스킨을
 * 바꿔 그린다. 그래서 게시판을 늘리는 데 배포가 필요 없다.
 *
 * <p><b>메뉴 등록은 여기 없다.</b> 메뉴는 order-service 소유라 board-service 가 쓰지 않는다 —
 * 관리 화면이 게시판 생성 후 `@/api/system` 의 `menuApi.create` 를 한 번 더 호출한다
 * (docs/plan/board-service.md §6). 게시판 생성이 곧 전사 네비게이션 변경이 되지 않도록
 * 두 조작을 일부러 갈라 둔 것이다.
 */

export type BoardSkin = 'LIST' | 'GALLERY' | 'FAQ' | 'QNA';
export type BoardContentFormat = 'TEXT' | 'MARKDOWN' | 'HTML';

export const BOARD_SKINS: BoardSkin[] = ['LIST', 'GALLERY', 'FAQ', 'QNA'];
export const BOARD_CONTENT_FORMATS: BoardContentFormat[] = ['TEXT', 'MARKDOWN', 'HTML'];

export const BOARD_SKIN_LABEL: Record<BoardSkin, string> = {
  LIST: '목록형 (공지·자료실)',
  GALLERY: '갤러리형 (이미지 게시판)',
  FAQ: 'FAQ (아코디언)',
  QNA: '문의 (질문·답변)',
};

/** 스킨이 첨부를 전제하는가 — 서버 도메인 불변식의 사본. 폼에서 미리 막아 400 을 덜 보게 한다. */
export const skinRequiresAttachments = (skin: BoardSkin): boolean => skin === 'GALLERY';
/** 스킨이 댓글을 전제하는가 — 위와 같은 이유. 최종 판정은 언제나 서버가 한다. */
export const skinRequiresComments = (skin: BoardSkin): boolean => skin === 'QNA';

export interface BoardContentPayload {
  contentFormat: BoardContentFormat;
  commentsEnabled: boolean;
  secretEnabled: boolean;
  categoryGroupCode?: string | null;
}

export interface BoardAttachmentPayload {
  enabled: boolean;
  maxCount: number;
  maxSizeKb: number;
  allowedExtensions: string[];
}

export interface BoardAccessPayload {
  readRoles: string[];
  writeRoles: string[];
  commentRoles: string[];
  manageRoles: string[];
}

export interface BoardDefinition {
  id: number;
  boardKey: string;
  name: string;
  description?: string | null;
  skin: BoardSkin;
  /** 서버가 계산해 준 착지 경로(`/boards/{key}`). 메뉴 등록이 이 값을 그대로 쓴다. */
  path: string;
  content: BoardContentPayload;
  attachment: BoardAttachmentPayload;
  access: BoardAccessPayload & { publicRead: boolean };
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface BoardCreateRequest {
  boardKey: string;
  name: string;
  description?: string;
  skin: BoardSkin;
  content: BoardContentPayload;
  attachment: BoardAttachmentPayload;
  access: BoardAccessPayload;
}

export type BoardUpdateRequest = Omit<BoardCreateRequest, 'boardKey'>;

export const boardAdminApi = {
  /** GET /admin/boards — 비활성 포함 전체 */
  list: async (): Promise<BoardDefinition[]> =>
    (await api.get<BoardDefinition[]>('/admin/boards')).data,

  create: async (body: BoardCreateRequest): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>('/admin/boards', body)).data,

  update: async (id: number, body: BoardUpdateRequest): Promise<BoardDefinition> =>
    (await api.put<BoardDefinition>(`/admin/boards/${id}`, body)).data,

  deactivate: async (id: number): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>(`/admin/boards/${id}/deactivate`)).data,

  activate: async (id: number): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>(`/admin/boards/${id}/activate`)).data,

  remove: async (id: number): Promise<void> => {
    await api.delete(`/admin/boards/${id}`);
  },
};

export const boardApi = {
  /** GET /api/boards — 활성 + 내가 읽을 수 있는 게시판만 */
  listVisible: async (): Promise<BoardDefinition[]> =>
    (await api.get<BoardDefinition[]>('/api/boards')).data,

  get: async (boardKey: string): Promise<BoardDefinition> =>
    (await api.get<BoardDefinition>(`/api/boards/${boardKey}`)).data,
};

// ════════════════════════════════════════════════════════════════════════════
// 게시글 · 댓글 (Phase 2)
// ════════════════════════════════════════════════════════════════════════════

export type BoardPostStatus = 'PUBLISHED' | 'HIDDEN' | 'DELETED';
export type BoardCommentStatus = 'PUBLISHED' | 'DELETED';

export interface BoardPost {
  id: number;
  boardId: number;
  categoryCode?: string | null;
  title: string;
  /** 목록 응답에는 없다 — 본문은 상세에서만 내려온다 */
  content?: string | null;
  contentFormat: BoardContentFormat;
  /** 마스킹된 표시명(예: 'ad***'). 원문 이메일은 서버에 저장되지 않는다 */
  authorName: string;
  mine: boolean;
  /** 화면이 버튼을 그릴지 정하는 힌트일 뿐 — 실제 인가는 서버가 다시 한다 */
  editable: boolean;
  pinned: boolean;
  secret: boolean;
  status: BoardPostStatus;
  viewCount: number;
  /** 갤러리 목록용 대표 이미지. 이미지 첨부가 없으면 null — 화면이 자리표시를 그린다 */
  thumbnailUrl?: string | null;
  /** 살아 있는 댓글 수. QNA 목록의 '답변 대기/완료' 배지가 이 값을 쓴다(목록 응답에만 채워진다) */
  commentCount?: number;
  /**
   * 상세 응답에만 실린다.
   *
   * 게시판이 첨부를 꺼도 **이미 붙은 첨부는 실려 온다** — 정책은 미래를 향하므로 새 업로드만
   * 막히고 기존 파일은 남는다. 화면이 이걸 안 그리면 데이터는 있는데 아무도 못 보는 상태가 된다.
   */
  attachments?: BoardAttachment[];
  createdAt: string;
  updatedAt: string;
}

export type BoardAttachmentKind = 'IMAGE' | 'FILE';

export interface BoardAttachment {
  id: number;
  postId: number;
  /** 서버가 파일 내용을 보고 정한 종류 — 확장자나 업로드 헤더가 아니다 */
  kind: BoardAttachmentKind;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  sortOrder: number;
  downloadUrl: string;
  createdAt: string;
}

export interface BoardComment {
  id: number;
  postId: number;
  parentId?: number | null;
  authorName: string;
  /** 삭제된 댓글은 '삭제된 댓글입니다.' 자리표시로 내려온다 */
  content: string;
  mine: boolean;
  deletable: boolean;
  status: BoardCommentStatus;
  createdAt: string;
}

export interface BoardPageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BoardPostRequest {
  title: string;
  content: string;
  categoryCode?: string | null;
  secret: boolean;
}

export const boardPostApi = {
  list: async (
    boardKey: string,
    params: { page?: number; size?: number; category?: string; keyword?: string } = {},
  ): Promise<BoardPageResponse<BoardPost>> =>
    (await api.get<BoardPageResponse<BoardPost>>(`/api/boards/${boardKey}/posts`, { params })).data,

  /** 조회수가 증가한다. 볼 수 없는 글은 404 */
  read: async (boardKey: string, postId: number): Promise<BoardPost> =>
    (await api.get<BoardPost>(`/api/boards/${boardKey}/posts/${postId}`)).data,

  create: async (boardKey: string, body: BoardPostRequest): Promise<BoardPost> =>
    (await api.post<BoardPost>(`/api/boards/${boardKey}/posts`, body)).data,

  update: async (boardKey: string, postId: number, body: BoardPostRequest): Promise<BoardPost> =>
    (await api.put<BoardPost>(`/api/boards/${boardKey}/posts/${postId}`, body)).data,

  remove: async (boardKey: string, postId: number): Promise<void> => {
    await api.delete(`/api/boards/${boardKey}/posts/${postId}`);
  },

  pin: async (boardKey: string, postId: number, pinned: boolean): Promise<BoardPost> =>
    (await api.post<BoardPost>(`/api/boards/${boardKey}/posts/${postId}/pin?pinned=${pinned}`)).data,

  hide: async (boardKey: string, postId: number): Promise<BoardPost> =>
    (await api.post<BoardPost>(`/api/boards/${boardKey}/posts/${postId}/hide`)).data,

  restore: async (boardKey: string, postId: number): Promise<BoardPost> =>
    (await api.post<BoardPost>(`/api/boards/${boardKey}/posts/${postId}/restore`)).data,
};

export const boardAttachmentApi = {
  list: async (boardKey: string, postId: number): Promise<BoardAttachment[]> =>
    (await api.get<BoardAttachment[]>(`/api/boards/${boardKey}/posts/${postId}/attachments`)).data,

  /**
   * 업로드. Content-Type 은 브라우저가 boundary 와 함께 붙이도록 두고 손대지 않는다.
   *
   * 서버는 어차피 이 헤더를 믿지 않는다 — 파일 내용(매직바이트)으로 형식을 다시 판정하고,
   * 확장자와 다르면 400 으로 거절한다.
   */
  upload: async (boardKey: string, postId: number, file: File): Promise<BoardAttachment> => {
    const form = new FormData();
    form.append('file', file);
    return (await api.post<BoardAttachment>(
      `/api/boards/${boardKey}/posts/${postId}/attachments`, form)).data;
  },

  remove: async (boardKey: string, attachmentId: number): Promise<void> => {
    await api.delete(`/api/boards/${boardKey}/attachments/${attachmentId}`);
  },
};

export const boardCommentApi = {
  list: async (boardKey: string, postId: number): Promise<BoardComment[]> =>
    (await api.get<BoardComment[]>(`/api/boards/${boardKey}/posts/${postId}/comments`)).data,

  create: async (boardKey: string, postId: number, content: string, parentId?: number | null):
    Promise<BoardComment> =>
    (await api.post<BoardComment>(`/api/boards/${boardKey}/posts/${postId}/comments`,
      { content, parentId: parentId ?? null })).data,

  remove: async (boardKey: string, commentId: number): Promise<void> => {
    await api.delete(`/api/boards/${boardKey}/comments/${commentId}`);
  },
};
