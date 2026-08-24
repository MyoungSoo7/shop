import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BoardPage from '@/pages/board/BoardPage';
import {
  boardApi, boardPostApi, BoardDefinition, BoardPost, BoardPageResponse,
} from '@/api/board';

/**
 * 갤러리 스킨(Phase 3).
 *
 * <p>같은 라우트·같은 데이터가 <b>정의의 skin 값 하나로</b> 다른 화면이 된다는 것이 이 스킨의
 * 요지다. 그래서 축은 두 개다: ① 목록형과 그리드형이 정말 갈라지는가 ② 대표 이미지가 없는 글이
 * 목록에서 <b>빠지지 않는가</b>.
 *
 * <p>②를 못박는 이유: "GALLERY 는 대표 이미지 필수"를 글 생성 시점에 강제하지 않기로 했으므로
 * (업로드가 글 생성 이후의 별도 요청이라서 — 설계문서 §14), 이미지 없는 글은 반드시 생긴다.
 * 그때 목록에서 빼 버리면 글은 존재하는데 아무 데서도 보이지 않는 상태가 된다.
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, get: vi.fn() },
    boardPostApi: { ...actual.boardPostApi, list: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardApi);
const mockedPost = vi.mocked(boardPostApi);

const gallery = (over: Partial<BoardDefinition> = {}): BoardDefinition => ({
  id: 2, boardKey: 'gallery', name: '포토 갤러리', description: '이미지 게시판', skin: 'GALLERY',
  path: '/boards/gallery',
  content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: false, categoryGroupCode: null },
  attachment: { enabled: true, maxCount: 10, maxSizeKb: 5120, allowedExtensions: ['jpg', 'png'] },
  access: { readRoles: [], writeRoles: ['ADMIN'], commentRoles: ['USER'], manageRoles: ['ADMIN'], publicRead: true },
  active: true, createdAt: '2026-08-15T00:00:00', updatedAt: '2026-08-15T00:00:00',
  ...over,
});

const post = (over: Partial<BoardPost> & Pick<BoardPost, 'id' | 'title'>): BoardPost => ({
  boardId: 2, categoryCode: null, content: null, contentFormat: 'TEXT',
  authorName: 'ad***', mine: false, editable: false, pinned: false, secret: false,
  status: 'PUBLISHED', viewCount: 7, thumbnailUrl: null,
  createdAt: '2026-08-15T09:00:00', updatedAt: '2026-08-15T09:00:00',
  ...over,
});

const page = (content: BoardPost[]): BoardPageResponse<BoardPost> => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: 1,
});

const renderGallery = async () => {
  render(
    <MemoryRouter initialEntries={['/boards/gallery']}>
      <Routes>
        <Route path="/boards/:boardKey" element={<BoardPage />} />
        <Route path="/boards/:boardKey/:postId" element={<div>상세 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(mockedPost.list).toHaveBeenCalled());
};

describe('BoardPage — 갤러리 스킨', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedBoard.get.mockResolvedValue(gallery());
  });

  it('대표 이미지가 있으면 썸네일을 그린다 — 주소는 서버가 목록 응답에 담아 준다', async () => {
    mockedPost.list.mockResolvedValue(page([
      post({ id: 1, title: '봄 사진', thumbnailUrl: '/api/boards/gallery/attachments/9/download' }),
    ]));

    await renderGallery();

    const image = await screen.findByRole('img', { name: '봄 사진' });
    expect(image).toHaveAttribute('src', '/api/boards/gallery/attachments/9/download');
    // 목록은 글마다 첨부를 따로 부르지 않는다 — 한 화면에 20왕복이 생기지 않게.
    expect(mockedPost.list).toHaveBeenCalledTimes(1);
  });

  it('대표 이미지가 없는 글도 목록에서 빠지지 않는다 — 자리표시로 그린다', async () => {
    mockedPost.list.mockResolvedValue(page([
      post({ id: 1, title: '사진 없는 글', thumbnailUrl: null }),
    ]));

    await renderGallery();

    expect(await screen.findByText('사진 없는 글')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('카드를 누르면 상세로 간다', async () => {
    mockedPost.list.mockResolvedValue(page([post({ id: 42, title: '여름 사진' })]));

    await renderGallery();
    fireEvent.click(await screen.findByText('여름 사진'));

    expect(await screen.findByText('상세 화면')).toBeInTheDocument();
  });

  it('글이 없으면 그리드 자리에 안내를 띄운다', async () => {
    mockedPost.list.mockResolvedValue(page([]));

    await renderGallery();

    expect(await screen.findByText('아직 글이 없습니다.')).toBeInTheDocument();
  });

  it('같은 데이터라도 LIST 스킨이면 그리드가 아니라 목록으로 그린다 — 갈라지는 것은 정의 하나다', async () => {
    mockedBoard.get.mockResolvedValue(gallery({ skin: 'LIST' }));
    mockedPost.list.mockResolvedValue(page([
      post({ id: 1, title: '봄 사진', thumbnailUrl: '/api/boards/gallery/attachments/9/download' }),
    ]));

    await renderGallery();

    expect(await screen.findByText('봄 사진')).toBeInTheDocument();
    // 목록형에는 썸네일이 없다 — 같은 thumbnailUrl 을 받아도 그리지 않는다.
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText(/조회 7/)).toBeInTheDocument();
  });
});
