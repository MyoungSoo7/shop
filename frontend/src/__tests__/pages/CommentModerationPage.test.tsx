import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CommentModerationPage from '@/pages/system/CommentModerationPage';
import { boardAdminApi, type BoardDefinition } from '@/api/board';
import {
  commentModerationApi,
  type CommentReport,
  type ModeratedComment,
  type ModerationPage,
} from '@/api/commentModeration';

vi.mock('@/api/board', () => ({
  boardAdminApi: { list: vi.fn() },
}));

vi.mock('@/api/commentModeration', async (importOriginal) => {
  // 라벨 상수(REPORT_REASON_LABEL 등)는 화면이 그대로 쓰므로 진짜를 남긴다 — 목으로 덮으면
  // 표에 undefined 가 찍히고 테스트가 통과해 버린다.
  const actual = await importOriginal<typeof import('@/api/commentModeration')>();
  return {
    ...actual,
    commentModerationApi: {
      search: vi.fn(),
      hide: vi.fn(),
      unhide: vi.fn(),
      reportsOf: vi.fn(),
      queue: vi.fn(),
      resolve: vi.fn(),
    },
  };
});

const boards = vi.mocked(boardAdminApi);
const mocked = vi.mocked(commentModerationApi);

const board = (): BoardDefinition => ({ id: 1, boardKey: 'notice', name: '공지사항' } as BoardDefinition);

const comment = (overrides: Partial<ModeratedComment> = {}): ModeratedComment => ({
  id: 7,
  postId: 5,
  boardKey: 'notice',
  boardName: '공지사항',
  postTitle: '문제의 글',
  authorId: 10,
  authorName: 'co***',
  content: '문제의 댓글',
  status: 'PUBLISHED',
  reportCount: 0,
  createdAt: '2026-08-27T01:00:00Z',
  ...overrides,
});

const report = (overrides: Partial<CommentReport> = {}): CommentReport => ({
  id: 3,
  commentId: 7,
  reporterName: 're***',
  reason: 'ABUSE',
  detail: '욕설입니다',
  status: 'RECEIVED',
  handledBy: null,
  handledAt: null,
  createdAt: '2026-08-27T02:00:00Z',
  ...overrides,
});

const page = <T,>(rows: T[]): ModerationPage<T> => ({
  content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1,
});

describe('CommentModerationPage — 조회', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boards.list.mockResolvedValue([board()]);
    mocked.queue.mockResolvedValue(page([]));
    mocked.search.mockResolvedValue(page([]));
  });

  it('첫 진입에 접수 상태의 신고 큐를 읽는다 — 판정 대기가 기본 화면이다', async () => {
    render(<CommentModerationPage />);

    await waitFor(() => expect(mocked.queue).toHaveBeenCalledWith({ status: 'RECEIVED' }));
  });

  it('글을 거치지 않고 댓글을 바로 읽는다 — 이 화면의 존재 이유다', async () => {
    mocked.search.mockResolvedValue(page([comment()]));
    render(<CommentModerationPage />);

    expect(await screen.findByText('문제의 댓글')).toBeInTheDocument();
    expect(screen.getByText('문제의 글')).toBeInTheDocument();
    expect(screen.getByTestId('comment-status-7')).toHaveTextContent('노출');
  });

  it('원문을 그대로 보여 준다 — 자리표시만 보이면 판정할 근거가 없다', async () => {
    mocked.search.mockResolvedValue(page([comment({ status: 'HIDDEN', content: '가려도 원문은 남는다' })]));
    render(<CommentModerationPage />);

    expect(await screen.findByText('가려도 원문은 남는다')).toBeInTheDocument();
    expect(screen.queryByText('신고 처리로 가려진 댓글입니다.')).not.toBeInTheDocument();
  });

  it('삭제된 글의 댓글은 제목 칸을 비우지 않는다 — 제목 없는 글과 구분돼야 한다', async () => {
    mocked.search.mockResolvedValue(page([comment({ postTitle: null })]));
    render(<CommentModerationPage />);

    expect(await screen.findByText('(삭제된 글)')).toBeInTheDocument();
  });

  it('상태 필터는 검색어 없이도 나간다 — 원본은 이 조건이 검색어 안에 중첩돼 조용히 무시됐다', async () => {
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.selectOptions(await screen.findByLabelText('노출 상태'), 'HIDDEN');

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({
      boardId: undefined, status: 'HIDDEN', keyword: undefined, reportedOnly: undefined,
    }));
  });

  it('신고된 댓글만 보기는 독립적으로 걸린다', async () => {
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByLabelText('신고된 댓글만'));

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({
      boardId: undefined, status: undefined, keyword: undefined, reportedOnly: true,
    }));
  });

  it('검색어는 조회를 눌러야 나간다 — 타이핑마다 부르면 늦은 응답이 최신 결과를 덮는다', async () => {
    const user = userEvent.setup();
    render(<CommentModerationPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(1));

    await user.type(await screen.findByLabelText('검색어'), '욕설');
    expect(mocked.search).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({
      boardId: undefined, status: undefined, keyword: '욕설', reportedOnly: undefined,
    }));
  });

  it('조회 실패는 드러내고 표 자체를 그리지 않는다 — 빈 표는 "문제 댓글 없음"으로 위장한다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<CommentModerationPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('댓글을 불러오지 못했습니다.');
    expect(screen.queryByTestId('empty')).not.toBeInTheDocument();
  });
});

describe('CommentModerationPage — 가림과 되돌리기', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boards.list.mockResolvedValue([board()]);
    mocked.queue.mockResolvedValue(page([]));
  });

  it('노출 중인 댓글에는 가림만, 가려진 댓글에는 해제만 있다', async () => {
    mocked.search.mockResolvedValue(page([
      comment({ id: 7, status: 'PUBLISHED' }),
      comment({ id: 8, status: 'HIDDEN' }),
      comment({ id: 9, status: 'DELETED' }),
    ]));
    render(<CommentModerationPage />);

    await screen.findByTestId('comment-7');
    expect(screen.getAllByRole('button', { name: '가림' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '가림 해제' })).toHaveLength(1);
  });

  it('가림은 되돌릴 수 있다 — 되돌릴 수 없는 조치는 운영이 쓰기를 꺼린다', async () => {
    mocked.search.mockResolvedValue(page([comment({ status: 'HIDDEN' })]));
    mocked.unhide.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByRole('button', { name: '가림 해제' }));

    await waitFor(() => expect(mocked.unhide).toHaveBeenCalledWith(7));
  });

  it('가리면 큐와 목록을 함께 다시 읽는다 — 한쪽만 갱신되면 같은 건을 두 번 처리한다', async () => {
    mocked.search.mockResolvedValue(page([comment()]));
    mocked.hide.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByRole('button', { name: '가림' }));

    await waitFor(() => expect(mocked.queue).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
  });

  it('가림 실패는 드러낸다', async () => {
    mocked.search.mockResolvedValue(page([comment()]));
    mocked.hide.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByRole('button', { name: '가림' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('가림에 실패했습니다.');
  });
});

describe('CommentModerationPage — 신고 판정', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boards.list.mockResolvedValue([board()]);
    mocked.search.mockResolvedValue(page([]));
    mocked.queue.mockResolvedValue(page([report()]));
  });

  it('접수 건에는 가림 처리와 유지 두 갈래가 모두 있다 — 유지도 판정이다', async () => {
    render(<CommentModerationPage />);

    await screen.findByTestId('report-3');
    expect(screen.getByRole('button', { name: '가림 처리' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '유지' })).toBeInTheDocument();
  });

  it('가림 처리는 판정과 조치를 한 번에 보낸다 — 원본은 이 둘이 갈라져 큐만 비었다', async () => {
    mocked.resolve.mockResolvedValue(report({ status: 'HIDDEN', handledBy: 'ad***' }));
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByRole('button', { name: '가림 처리' }));

    await waitFor(() => expect(mocked.resolve).toHaveBeenCalledWith(3, 'HIDDEN'));
    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
  });

  it('유지 판정도 서버로 남긴다 — 판정하지 않은 것과 유지하기로 한 것은 다르다', async () => {
    mocked.resolve.mockResolvedValue(report({ status: 'KEPT', handledBy: 'ad***' }));
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByRole('button', { name: '유지' }));

    await waitFor(() => expect(mocked.resolve).toHaveBeenCalledWith(3, 'KEPT'));
  });

  it('이미 처리된 건에는 판정 버튼이 없고 누가 처리했는지가 남는다', async () => {
    mocked.queue.mockResolvedValue(page([report({ status: 'KEPT', handledBy: 'ad***' })]));
    render(<CommentModerationPage />);

    expect(await screen.findByTestId('report-status-3')).toHaveTextContent('유지 판정 (ad***)');
    expect(screen.queryByRole('button', { name: '가림 처리' })).not.toBeInTheDocument();
  });

  it('큐 조회 실패는 드러내고 표 자체를 그리지 않는다', async () => {
    mocked.queue.mockRejectedValue(new Error('boom'));
    render(<CommentModerationPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('신고 큐를 불러오지 못했습니다.');
    expect(screen.queryByTestId('queue-empty')).not.toBeInTheDocument();
  });

  it('신고 건수를 누르면 사유별 내역을 펼친다 — 판정 근거를 보는 자리다', async () => {
    mocked.search.mockResolvedValue(page([comment({ reportCount: 2 })]));
    mocked.reportsOf.mockResolvedValue([
      report({ id: 3, reason: 'ABUSE', detail: '욕설입니다' }),
      report({ id: 4, reason: 'SPAM', detail: null }),
    ]);
    const user = userEvent.setup();
    render(<CommentModerationPage />);

    await user.click(await screen.findByTestId('reports-7'));

    const details = await screen.findByTestId('report-details');
    expect(details).toHaveTextContent('욕설·비방');
    expect(details).toHaveTextContent('스팸·도배');
  });

  it('신고가 없는 댓글에는 펼칠 것이 없다', async () => {
    mocked.search.mockResolvedValue(page([comment({ reportCount: 0 })]));
    render(<CommentModerationPage />);

    await screen.findByTestId('comment-7');
    expect(screen.queryByTestId('reports-7')).not.toBeInTheDocument();
  });
});
