import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BoardPage from '@/pages/board/BoardPage';
import {
  boardApi, boardPostApi, BoardDefinition, BoardPost, BoardPageResponse, BoardSkin,
} from '@/api/board';

/**
 * FAQ · QNA 스킨(Phase 4).
 *
 * <p>이 두 스킨의 존재 이유는 "같은 데이터를 다르게 읽는다"이다. FAQ 는 <b>목록에서 답을 읽고</b>,
 * QNA 는 <b>답이 달렸는지</b>가 제목보다 중요하다. 그래서 테스트도 그 두 가지만 못박는다.
 *
 * <p>FAQ 의 지연 로드도 함께 고정한다 — 본문을 미리 다 받아 오면 목록에서 본문을 뺀 이유가
 * 그대로 무너진다.
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, get: vi.fn() },
    boardPostApi: { ...actual.boardPostApi, list: vi.fn(), read: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardApi);
const mockedPost = vi.mocked(boardPostApi);

const definition = (skin: BoardSkin): BoardDefinition => ({
  id: 3, boardKey: 'help', name: '도움말', description: null, skin, path: '/boards/help',
  content: {
    contentFormat: 'TEXT', commentsEnabled: skin === 'QNA', secretEnabled: false, categoryGroupCode: null,
  },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: [] },
  access: { readRoles: [], writeRoles: ['USER'], commentRoles: ['USER'], manageRoles: ['ADMIN'], publicRead: true },
  active: true, createdAt: '2026-08-15T00:00:00', updatedAt: '2026-08-15T00:00:00',
});

const post = (over: Partial<BoardPost> & Pick<BoardPost, 'id' | 'title'>): BoardPost => ({
  boardId: 3, categoryCode: null, content: null, contentFormat: 'TEXT',
  authorName: 'ad***', mine: false, editable: false, pinned: false, secret: false,
  status: 'PUBLISHED', viewCount: 1, thumbnailUrl: null, commentCount: 0,
  createdAt: '2026-08-15T09:00:00', updatedAt: '2026-08-15T09:00:00',
  ...over,
});

const page = (content: BoardPost[]): BoardPageResponse<BoardPost> => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: 1,
});

const renderBoard = async () => {
  render(
    <MemoryRouter initialEntries={['/boards/help']}>
      <Routes>
        <Route path="/boards/:boardKey" element={<BoardPage />} />
        <Route path="/boards/:boardKey/:postId" element={<div>상세 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(mockedPost.list).toHaveBeenCalled());
};

describe('BoardPage — FAQ 스킨', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedBoard.get.mockResolvedValue(definition('FAQ'));
    mockedPost.list.mockResolvedValue(page([
      post({ id: 1, title: '배송은 얼마나 걸리나요?' }),
      post({ id: 2, title: '환불은 어떻게 하나요?' }),
    ]));
  });

  it('목록에서는 본문을 부르지 않는다 — 안 펼칠 항목까지 받아 오지 않게', async () => {
    await renderBoard();

    expect(await screen.findByText('배송은 얼마나 걸리나요?')).toBeInTheDocument();
    expect(mockedPost.read).not.toHaveBeenCalled();
  });

  it('펼치면 그때 본문을 불러 그 자리에 보여 준다 — 상세로 이동하지 않는다', async () => {
    mockedPost.read.mockResolvedValue(post({ id: 1, title: '배송은 얼마나 걸리나요?', content: '보통 2~3일' }));

    await renderBoard();
    fireEvent.click(await screen.findByText('배송은 얼마나 걸리나요?'));

    expect(await screen.findByText('보통 2~3일')).toBeInTheDocument();
    expect(screen.queryByText('상세 화면')).not.toBeInTheDocument();
    expect(mockedPost.read).toHaveBeenCalledWith('help', 1);
  });

  it('접었다 다시 펼쳐도 본문을 다시 부르지 않는다', async () => {
    mockedPost.read.mockResolvedValue(post({ id: 1, title: '배송은 얼마나 걸리나요?', content: '보통 2~3일' }));

    await renderBoard();
    const question = await screen.findByText('배송은 얼마나 걸리나요?');

    fireEvent.click(question);
    expect(await screen.findByText('보통 2~3일')).toBeInTheDocument();
    fireEvent.click(question);
    await waitFor(() => expect(screen.queryByText('보통 2~3일')).not.toBeInTheDocument());
    fireEvent.click(question);

    expect(await screen.findByText('보통 2~3일')).toBeInTheDocument();
    expect(mockedPost.read).toHaveBeenCalledTimes(1);
  });
});

describe('BoardPage — QNA 스킨', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedBoard.get.mockResolvedValue(definition('QNA'));
  });

  it('댓글이 있으면 답변완료, 없으면 답변대기 — 목록에서 가장 중요한 정보다', async () => {
    mockedPost.list.mockResolvedValue(page([
      post({ id: 1, title: '주문이 안 됩니다', commentCount: 2 }),
      post({ id: 2, title: '쿠폰이 적용되지 않아요', commentCount: 0 }),
    ]));

    await renderBoard();

    expect(await screen.findByText('답변완료')).toBeInTheDocument();
    expect(screen.getByText('답변대기')).toBeInTheDocument();
  });

  it('LIST 스킨에는 답변 배지를 그리지 않는다 — 갈라지는 것은 정의 하나다', async () => {
    mockedBoard.get.mockResolvedValue(definition('LIST'));
    mockedPost.list.mockResolvedValue(page([post({ id: 1, title: '공지', commentCount: 3 })]));

    await renderBoard();

    expect(await screen.findByText('공지')).toBeInTheDocument();
    expect(screen.queryByText('답변완료')).not.toBeInTheDocument();
  });
});
