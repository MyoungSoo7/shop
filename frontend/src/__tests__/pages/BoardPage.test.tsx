import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useParams } from 'react-router-dom';
import BoardPage from '@/pages/board/BoardPage';
import {
  boardApi, boardPostApi, BoardDefinition, BoardPost, BoardPageResponse,
} from '@/api/board';

/**
 * 이 화면의 핵심은 **라우트가 하나뿐**이라는 것이다 — `/boards/:boardKey` 가 정의를 읽어
 * 자기를 바꿔 그린다. 그래서 "게시판 키가 바뀌면 다른 게시판이 된다"가 테스트의 축이고,
 * 정의(비밀글 허용 여부 등)가 폼을 지배하는지도 함께 본다.
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, get: vi.fn() },
    boardPostApi: { ...actual.boardPostApi, list: vi.fn(), create: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardApi);
const mockedPost = vi.mocked(boardPostApi);

const definition = (over: Partial<BoardDefinition> = {}): BoardDefinition => ({
  id: 1, boardKey: 'notice', name: '공지사항', description: '전사 공지', skin: 'LIST',
  path: '/boards/notice',
  content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: false, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: [] },
  access: { readRoles: [], writeRoles: ['ADMIN'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'], publicRead: true },
  active: true, createdAt: '2026-08-15T00:00:00', updatedAt: '2026-08-15T00:00:00',
  ...over,
});

const post = (over: Partial<BoardPost> & Pick<BoardPost, 'id' | 'title'>): BoardPost => ({
  boardId: 1, categoryCode: null, content: null, contentFormat: 'TEXT',
  authorName: 'ad***', mine: false, editable: false, pinned: false, secret: false,
  status: 'PUBLISHED', viewCount: 3,
  createdAt: '2026-08-15T09:00:00', updatedAt: '2026-08-15T09:00:00',
  ...over,
});

const page = (over: Partial<BoardPageResponse<BoardPost>> = {}): BoardPageResponse<BoardPost> => ({
  content: [
    post({ id: 1, title: '점검 안내', pinned: true }),
    post({ id: 2, title: '비밀 문의', secret: true }),
    post({ id: 3, title: '숨긴 글', status: 'HIDDEN' }),
  ],
  page: 0, size: 20, totalElements: 3, totalPages: 1,
  ...over,
});

/** 상세 라우트는 실물 대신 표식만 — 여기서 검증할 것은 "어디로 갔는가" 뿐이다. */
const DetailStub: React.FC = () => {
  const { boardKey, postId } = useParams();
  return <div>상세: {boardKey}/{postId}</div>;
};

const renderPage = async (boardKey = 'notice') => {
  render(
    <MemoryRouter initialEntries={[`/boards/${boardKey}`]}>
      <Routes>
        <Route path="/boards/:boardKey" element={<BoardPage />} />
        <Route path="/boards/:boardKey/:postId" element={<DetailStub />} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(mockedPost.list).toHaveBeenCalled());
};

const titleInput = () => screen.getByPlaceholderText('제목');
const contentInput = () => screen.getByPlaceholderText('내용');
const searchInput = () => screen.getByPlaceholderText('제목 · 내용 검색');

beforeEach(() => {
  vi.clearAllMocks();
  mockedBoard.get.mockResolvedValue(definition());
  mockedPost.list.mockResolvedValue(page());
  mockedPost.create.mockResolvedValue(post({ id: 9, title: '새 글' }));
});

describe('BoardPage — 단일 라우트가 정의를 읽어 그린다', () => {
  it('정의와 글 목록을 함께 불러 머리말을 세운다', async () => {
    await renderPage();

    await waitFor(() => expect(screen.getByRole('heading', { name: '공지사항' })).toBeInTheDocument());
    expect(screen.getByText('전사 공지')).toBeInTheDocument();
    expect(mockedBoard.get).toHaveBeenCalledWith('notice');
    expect(mockedPost.list).toHaveBeenCalledWith('notice', { page: 0, size: 20, keyword: undefined });
  });

  it('URL 의 키가 바뀌면 그 게시판을 읽는다 — 화면은 하나뿐이다', async () => {
    mockedBoard.get.mockResolvedValue(definition({ boardKey: 'qna', name: '문의' }));
    await renderPage('qna');

    await waitFor(() => expect(screen.getByRole('heading', { name: '문의' })).toBeInTheDocument());
    expect(mockedBoard.get).toHaveBeenCalledWith('qna');
  });

  it('공지·비밀글·숨김을 배지로 구분해 보여 준다', async () => {
    await renderPage();

    await waitFor(() => expect(screen.getByText('점검 안내')).toBeInTheDocument());
    expect(within(screen.getByText('점검 안내').closest('li')!).getByText('공지')).toBeInTheDocument();
    expect(within(screen.getByText('비밀 문의').closest('li')!).getByTitle('비밀글')).toBeInTheDocument();
    expect(within(screen.getByText('숨긴 글').closest('li')!).getByText('숨김')).toBeInTheDocument();
  });

  it('글을 누르면 그 게시판의 상세로 간다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByText('점검 안내')).toBeInTheDocument());
    fireEvent.click(screen.getByText('점검 안내').closest('li')!);

    expect(screen.getByText('상세: notice/1')).toBeInTheDocument();
  });

  it('정의를 못 읽으면 목록 대신 사유만 남긴다 — 빈 게시판처럼 보이면 오해한다', async () => {
    mockedBoard.get.mockRejectedValue({ response: { data: { message: '접근 권한이 없습니다' } } });
    await renderPage();

    await waitFor(() => expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument());
    expect(screen.queryByPlaceholderText('제목 · 내용 검색')).not.toBeInTheDocument();
  });
});

describe('BoardPage — 검색', () => {
  it('검색 버튼은 첫 페이지부터 키워드로 다시 읽는다', async () => {
    await renderPage();
    fireEvent.change(searchInput(), { target: { value: '환불' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(mockedPost.list).toHaveBeenLastCalledWith('notice', {
      page: 0, size: 20, keyword: '환불',
    }));
  });

  it('엔터로도 검색된다 (다른 키는 아무 일도 하지 않는다)', async () => {
    await renderPage();
    fireEvent.change(searchInput(), { target: { value: '점검' } });
    fireEvent.keyDown(searchInput(), { key: 'a' });
    expect(mockedPost.list).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(searchInput(), { key: 'Enter' });
    await waitFor(() => expect(mockedPost.list).toHaveBeenLastCalledWith('notice', {
      page: 0, size: 20, keyword: '점검',
    }));
  });

  it('검색 결과가 없을 때와 글이 아예 없을 때를 구분해 안내한다', async () => {
    mockedPost.list.mockResolvedValue(page({ content: [], totalElements: 0 }));
    await renderPage();

    await waitFor(() => expect(screen.getByText('아직 글이 없습니다.')).toBeInTheDocument());

    fireEvent.change(searchInput(), { target: { value: '없는말' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument());
  });
});

describe('BoardPage — 페이지 이동', () => {
  it('한 페이지뿐이면 페이지네이션을 그리지 않는다', async () => {
    await renderPage();

    await waitFor(() => expect(screen.getByText('점검 안내')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument();
  });

  it('다음·이전은 양 끝에서 막히고, 이동하면 그 페이지를 읽는다', async () => {
    mockedPost.list.mockResolvedValue(page({ totalPages: 3 }));
    await renderPage();

    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() => expect(mockedPost.list).toHaveBeenLastCalledWith('notice', {
      page: 1, size: 20, keyword: undefined,
    }));
    expect(screen.getByText('2 / 3')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
  });
});

describe('BoardPage — 글쓰기', () => {
  const openWriter = async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '글쓰기' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '글쓰기' }));
  };

  it('글쓰기는 접혀 있다가 열리고 다시 닫힌다', async () => {
    await openWriter();
    expect(titleInput()).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByPlaceholderText('제목')).not.toBeInTheDocument();
  });

  it('비밀글 체크는 정의가 허용한 게시판에서만 나온다', async () => {
    await openWriter();
    expect(screen.queryByText('비밀글')).not.toBeInTheDocument();
  });

  it('비밀글을 허용한 게시판에서는 체크한 그대로 보낸다', async () => {
    mockedBoard.get.mockResolvedValue(definition({
      content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: true, categoryGroupCode: null },
    }));
    await openWriter();
    fireEvent.change(titleInput(), { target: { value: '문의드립니다' } });
    fireEvent.change(contentInput(), { target: { value: '본문입니다' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedPost.create).toHaveBeenCalledWith('notice', {
      title: '문의드립니다', content: '본문입니다', secret: true,
    }));
  });

  it('등록하면 폼을 접고 첫 페이지부터 다시 읽는다', async () => {
    mockedPost.list.mockResolvedValue(page({ totalPages: 3 }));
    await openWriter();
    fireEvent.change(titleInput(), { target: { value: '새 글' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(screen.queryByPlaceholderText('제목')).not.toBeInTheDocument());
    expect(mockedPost.list).toHaveBeenLastCalledWith('notice', { page: 0, size: 20, keyword: undefined });
  });

  it('등록 실패는 사유를 띄우고 입력을 살려 둔다', async () => {
    mockedPost.create.mockRejectedValue({ response: { data: { message: '쓰기 권한이 없습니다' } } });
    await openWriter();
    fireEvent.change(titleInput(), { target: { value: '새 글' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(screen.getByText('쓰기 권한이 없습니다')).toBeInTheDocument());
    expect(titleInput()).toHaveValue('새 글');
  });
});
