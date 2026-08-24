import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BoardPostPage from '@/pages/board/BoardPostPage';
import {
  boardApi, boardPostApi, boardCommentApi,
  BoardComment, BoardDefinition, BoardPost,
} from '@/api/board';

/**
 * 상세 화면의 버튼은 서버가 준 `editable`·`deletable` 힌트로만 그린다. 힌트는 편의일 뿐
 * 인가가 아니므로, 테스트도 "힌트가 없으면 버튼이 없다"까지만 못박는다 — 진짜 차단은
 * 서버 도메인의 몫이고 여기서 흉내 내면 이중 진실이 된다.
 *
 * 댓글이 꺼진 게시판에서 목록 호출 자체가 나가지 않는 것도 고정한다(없는 기능에 왕복을 쓰지 않는다).
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, get: vi.fn() },
    boardPostApi: {
      ...actual.boardPostApi,
      read: vi.fn(), update: vi.fn(), remove: vi.fn(), pin: vi.fn(),
    },
    boardCommentApi: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardApi);
const mockedPost = vi.mocked(boardPostApi);
const mockedComment = vi.mocked(boardCommentApi);

const definition = (commentsEnabled = true): BoardDefinition => ({
  id: 1, boardKey: 'qna', name: '문의', description: null, skin: 'QNA', path: '/boards/qna',
  content: { contentFormat: 'TEXT', commentsEnabled, secretEnabled: true, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: [] },
  access: { readRoles: ['USER'], writeRoles: ['USER'], commentRoles: ['USER'], manageRoles: ['ADMIN'], publicRead: false },
  active: true, createdAt: '2026-08-15T00:00:00', updatedAt: '2026-08-15T00:00:00',
});

const detail = (over: Partial<BoardPost> = {}): BoardPost => ({
  id: 7, boardId: 1, categoryCode: null, title: '배송이 늦습니다', content: '주문 번호는 1234 입니다.',
  contentFormat: 'TEXT', authorName: 'us***', mine: true, editable: true,
  pinned: false, secret: false, status: 'PUBLISHED', viewCount: 12,
  createdAt: '2026-08-15T09:00:00', updatedAt: '2026-08-15T09:00:00',
  ...over,
});

const comment = (over: Partial<BoardComment> & Pick<BoardComment, 'id' | 'content'>): BoardComment => ({
  postId: 7, parentId: null, authorName: 'ad***', mine: false, deletable: false,
  status: 'PUBLISHED', createdAt: '2026-08-15T10:00:00',
  ...over,
});

const 답변 = comment({ id: 1, content: '확인 중입니다', deletable: true });
const 답글 = comment({ id: 2, content: '감사합니다', parentId: 1 });
const 삭제됨 = comment({ id: 3, content: '삭제된 댓글입니다.', status: 'DELETED' });

const renderPage = async () => {
  render(
    <MemoryRouter initialEntries={['/boards/qna/7']}>
      <Routes>
        <Route path="/boards/:boardKey/:postId" element={<BoardPostPage />} />
        <Route path="/boards/:boardKey" element={<div>목록 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(mockedPost.read).toHaveBeenCalled());
};

const commentBox = () => screen.getByPlaceholderText('댓글을 입력하세요');
/** '삭제' 는 글에도 댓글에도 있다 — 글 조작은 본문 영역 안에서만 집는다. */
const inArticle = () => within(screen.getByRole('article'));
let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedBoard.get.mockResolvedValue(definition());
  mockedPost.read.mockResolvedValue(detail());
  mockedPost.update.mockResolvedValue(detail());
  mockedPost.remove.mockResolvedValue(undefined);
  mockedPost.pin.mockResolvedValue(detail({ pinned: true }));
  mockedComment.list.mockResolvedValue([답변, 답글, 삭제됨]);
  mockedComment.create.mockResolvedValue(답변);
  mockedComment.remove.mockResolvedValue(undefined);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => {
  confirmSpy.mockRestore();
});

describe('BoardPostPage — 조회', () => {
  it('정의·본문·댓글을 함께 불러 그린다', async () => {
    await renderPage();

    await waitFor(() => expect(screen.getByRole('heading', { name: '배송이 늦습니다' })).toBeInTheDocument());
    expect(screen.getByText('주문 번호는 1234 입니다.')).toBeInTheDocument();
    expect(screen.getByText('조회 12')).toBeInTheDocument();
    expect(mockedPost.read).toHaveBeenCalledWith('qna', 7);   // URL 문자열이 숫자 id 로 간다
    expect(screen.getByRole('heading', { name: '댓글 3' })).toBeInTheDocument();
  });

  it('댓글이 꺼진 게시판이면 목록을 아예 부르지 않는다', async () => {
    mockedBoard.get.mockResolvedValue(definition(false));
    await renderPage();

    await waitFor(() => expect(screen.getByRole('heading', { name: '배송이 늦습니다' })).toBeInTheDocument());
    expect(mockedComment.list).not.toHaveBeenCalled();
    expect(screen.queryByPlaceholderText('댓글을 입력하세요')).not.toBeInTheDocument();
  });

  it('공지·비밀글 표시는 글의 속성 그대로 따른다', async () => {
    mockedPost.read.mockResolvedValue(detail({ pinned: true, secret: true }));
    await renderPage();

    await waitFor(() => expect(screen.getByText('공지')).toBeInTheDocument());
    expect(screen.getByTitle('비밀글')).toBeInTheDocument();
  });

  it('글을 못 읽으면 사유와 함께 목록으로 돌아갈 길을 준다', async () => {
    mockedPost.read.mockRejectedValue({ response: { data: { message: '비밀글은 작성자만 볼 수 있습니다' } } });
    await renderPage();

    await waitFor(() => expect(screen.getByText('비밀글은 작성자만 볼 수 있습니다')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '목록으로' }));
    expect(screen.getByText('목록 화면')).toBeInTheDocument();
  });
});

describe('BoardPostPage — 글 조작 (버튼은 서버 힌트로만 그린다)', () => {
  it('editable 이 아니면 수정·삭제·고정 버튼을 주지 않는다', async () => {
    mockedPost.read.mockResolvedValue(detail({ editable: false, mine: false }));
    await renderPage();

    await waitFor(() => expect(screen.getByRole('heading', { name: '배송이 늦습니다' })).toBeInTheDocument());
    expect(inArticle().queryByRole('button', { name: '수정' })).not.toBeInTheDocument();
    expect(inArticle().queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
    expect(inArticle().queryByRole('button', { name: '상단 고정' })).not.toBeInTheDocument();
  });

  it('수정은 현재 내용을 채운 폼에서 시작하고, 저장하면 다시 읽는다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '수정' }));

    expect(screen.getByDisplayValue('배송이 늦습니다')).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue('주문 번호는 1234 입니다.'), { target: { value: '해결됐습니다.' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedPost.update).toHaveBeenCalledWith('qna', 7, {
      title: '배송이 늦습니다', content: '해결됐습니다.', secret: false,
    }));
    expect(mockedPost.read).toHaveBeenCalledTimes(2);
  });

  it('수정 취소는 아무것도 보내지 않고 본문으로 돌아온다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '수정' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(mockedPost.update).not.toHaveBeenCalled();
    expect(screen.getByText('주문 번호는 1234 입니다.')).toBeInTheDocument();
  });

  it('수정 실패는 사유를 띄운다', async () => {
    mockedPost.update.mockRejectedValue(new Error('boom'));
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '수정' }));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(screen.getByText('수정하지 못했습니다.')).toBeInTheDocument());
  });

  it('삭제는 확인을 받고, 성공하면 목록으로 보낸다', async () => {
    await renderPage();
    await waitFor(() => expect(inArticle().getByRole('button', { name: '삭제' })).toBeInTheDocument());
    fireEvent.click(inArticle().getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.getByText('목록 화면')).toBeInTheDocument());
    expect(confirmSpy).toHaveBeenCalledWith('이 글을 삭제할까요?');
    expect(mockedPost.remove).toHaveBeenCalledWith('qna', 7);
  });

  it('삭제를 취소하면 서버를 부르지 않고 글에 머문다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderPage();
    await waitFor(() => expect(inArticle().getByRole('button', { name: '삭제' })).toBeInTheDocument());
    fireEvent.click(inArticle().getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mockedPost.remove).not.toHaveBeenCalled());
    expect(await screen.findByRole('heading', { name: '배송이 늦습니다' })).toBeInTheDocument();
  });

  it('삭제 실패는 목록으로 보내지 않고 사유를 남긴다', async () => {
    mockedPost.remove.mockRejectedValue(new Error('boom'));
    await renderPage();
    await waitFor(() => expect(inArticle().getByRole('button', { name: '삭제' })).toBeInTheDocument());
    fireEvent.click(inArticle().getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.getByText('삭제하지 못했습니다.')).toBeInTheDocument());
    expect(screen.queryByText('목록 화면')).not.toBeInTheDocument();
  });

  it('고정 버튼은 현재 상태의 반대를 보내고, 다시 읽은 상태로 라벨이 뒤집힌다', async () => {
    mockedPost.read
      .mockResolvedValueOnce(detail())                  // 첫 로드 — 고정 안 됨
      .mockResolvedValue(detail({ pinned: true }));     // 고정 후 재조회
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '상단 고정' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '상단 고정' }));

    await waitFor(() => expect(mockedPost.pin).toHaveBeenCalledWith('qna', 7, true));
    await waitFor(() => expect(screen.getByRole('button', { name: '고정 해제' })).toBeInTheDocument());
  });

  it('고정 실패는 사유를 띄운다', async () => {
    mockedPost.pin.mockRejectedValue(new Error('boom'));
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '상단 고정' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '상단 고정' }));

    await waitFor(() => expect(screen.getByText('고정 상태를 바꾸지 못했습니다.')).toBeInTheDocument());
  });
});

describe('BoardPostPage — 댓글', () => {
  it('빈 댓글은 보내지 않는다 (공백만 있어도 마찬가지)', async () => {
    await renderPage();
    await waitFor(() => expect(commentBox()).toBeInTheDocument());
    fireEvent.change(commentBox(), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedComment.create).not.toHaveBeenCalled());
  });

  it('일반 댓글은 부모 없이 보내고, 등록 뒤 입력창을 비우고 목록을 다시 읽는다', async () => {
    await renderPage();
    await waitFor(() => expect(commentBox()).toBeInTheDocument());
    fireEvent.change(commentBox(), { target: { value: '언제 오나요' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedComment.create).toHaveBeenCalledWith('qna', 7, '언제 오나요', null));
    expect(commentBox()).toHaveValue('');
    expect(mockedComment.list).toHaveBeenCalledTimes(2);
  });

  it('답글은 부모 댓글을 물고 가고, 등록하면 답글 모드가 풀린다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '답글' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '답글' }));
    expect(screen.getByText(/답글 작성 중/)).toBeInTheDocument();

    fireEvent.change(commentBox(), { target: { value: '확인했습니다' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedComment.create).toHaveBeenCalledWith('qna', 7, '확인했습니다', 1));
    await waitFor(() => expect(screen.queryByText(/답글 작성 중/)).not.toBeInTheDocument());
  });

  it('답글 모드는 취소할 수 있다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '답글' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '답글' }));
    fireEvent.click(within(screen.getByText(/답글 작성 중/)).getByRole('button', { name: '취소' }));

    expect(screen.queryByText(/답글 작성 중/)).not.toBeInTheDocument();
  });

  it('답글에는 다시 답글을 달 수 없고, 삭제된 댓글에도 답글 버튼이 없다', async () => {
    await renderPage();

    await waitFor(() => expect(screen.getByText('감사합니다')).toBeInTheDocument());
    expect(screen.getAllByRole('button', { name: '답글' })).toHaveLength(1);  // 최상위 PUBLISHED 하나뿐
    expect(within(screen.getByText('삭제된 댓글입니다.').closest('li')!)
      .queryByRole('button', { name: '답글' })).not.toBeInTheDocument();
  });

  it('삭제 가능한 댓글만 삭제 버튼을 갖고, 지우면 목록을 다시 읽는다', async () => {
    await renderPage();
    await waitFor(() => expect(screen.getByText('확인 중입니다')).toBeInTheDocument());
    expect(screen.getAllByRole('button', { name: '삭제' })).toHaveLength(2);  // 글 1 + 댓글 1

    fireEvent.click(within(screen.getByText('확인 중입니다').closest('li')!).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mockedComment.remove).toHaveBeenCalledWith('qna', 1));
    expect(mockedComment.list).toHaveBeenCalledTimes(2);
  });

  it('댓글 등록·삭제 실패는 각각의 사유를 띄운다', async () => {
    mockedComment.create.mockRejectedValue({ response: { data: { message: '댓글 권한이 없습니다' } } });
    mockedComment.remove.mockRejectedValue(new Error('boom'));
    await renderPage();
    await waitFor(() => expect(commentBox()).toBeInTheDocument());

    fireEvent.change(commentBox(), { target: { value: '한마디' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));
    await waitFor(() => expect(screen.getByText('댓글 권한이 없습니다')).toBeInTheDocument());

    fireEvent.click(within(screen.getByText('확인 중입니다').closest('li')!).getByRole('button', { name: '삭제' }));
    await waitFor(() => expect(screen.getByText('댓글을 삭제하지 못했습니다.')).toBeInTheDocument());
  });

  it('첫 댓글이 없으면 비어 있음을 알린다', async () => {
    mockedComment.list.mockResolvedValue([]);
    await renderPage();

    await waitFor(() => expect(screen.getByText('첫 댓글을 남겨 보세요.')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: '댓글 0' })).toBeInTheDocument();
  });
});
