import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BoardPostPage from '@/pages/board/BoardPostPage';
import {
  boardApi, boardAttachmentApi, boardCommentApi, boardPostApi,
  BoardAttachment, BoardDefinition, BoardPost,
} from '@/api/board';

/**
 * 첨부 섹션(Phase 3).
 *
 * <p>축은 셋이다:
 * <ol>
 *   <li><b>종류는 서버가 정한다</b> — 화면은 확장자를 보지 않고 {@code kind} 로만 이미지/파일을 가른다.
 *       확장자로 판단하기 시작하면 서버의 매직바이트 판정과 두 개의 진실이 생긴다.</li>
 *   <li><b>실패 이유를 뭉개지 않는다</b> — "확장자와 다릅니다"·"최대 N개까지"는 사용자가 고칠 수 있는
 *       정보다. '업로드 실패'로 덮으면 그 정보가 사라진다.</li>
 *   <li><b>정책은 미래를 향한다</b> — 게시판이 첨부를 꺼도 이미 붙은 파일은 그대로 보이고
 *       '추가' 버튼만 사라진다. 감추면 데이터는 있는데 아무도 못 보고, 직링크로는 여전히
 *       받아져 화면과 서버가 어긋난다(G-6).</li>
 * </ol>
 *
 * <p>첨부는 상세 응답이 싣고 온다 — 목록을 따로 부르지 않아 왕복이 하나 줄고, 위 세 번째 항목이
 * 자연스럽게 성립한다(정의의 플래그가 아니라 <b>실제 데이터</b>가 렌더를 정한다).
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, get: vi.fn() },
    boardPostApi: { ...actual.boardPostApi, read: vi.fn() },
    boardCommentApi: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
    boardAttachmentApi: { list: vi.fn(), upload: vi.fn(), remove: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardApi);
const mockedPost = vi.mocked(boardPostApi);
const mockedComment = vi.mocked(boardCommentApi);
const mockedAttachment = vi.mocked(boardAttachmentApi);

const definition = (attachmentsEnabled = true): BoardDefinition => ({
  id: 2, boardKey: 'gallery', name: '포토 갤러리', description: null, skin: 'GALLERY',
  path: '/boards/gallery',
  content: { contentFormat: 'TEXT', commentsEnabled: false, secretEnabled: false, categoryGroupCode: null },
  attachment: {
    enabled: attachmentsEnabled, maxCount: 3, maxSizeKb: 5120, allowedExtensions: ['jpg', 'png', 'pdf'],
  },
  access: { readRoles: [], writeRoles: ['USER'], commentRoles: ['USER'], manageRoles: ['ADMIN'], publicRead: true },
  active: true, createdAt: '2026-08-15T00:00:00', updatedAt: '2026-08-15T00:00:00',
});

const detail = (over: Partial<BoardPost> = {}): BoardPost => ({
  id: 7, boardId: 2, categoryCode: null, title: '봄 사진', content: '벚꽃입니다',
  contentFormat: 'TEXT', authorName: 'us***', mine: true, editable: true,
  pinned: false, secret: false, status: 'PUBLISHED', viewCount: 5, thumbnailUrl: null,
  attachments: [],
  createdAt: '2026-08-15T09:00:00', updatedAt: '2026-08-15T09:00:00',
  ...over,
});

const attachment = (over: Partial<BoardAttachment> & Pick<BoardAttachment, 'id' | 'originalName' | 'kind'>):
  BoardAttachment => ({
  postId: 7, contentType: 'image/png', sizeBytes: 2048, sortOrder: 0,
  downloadUrl: `/api/boards/gallery/attachments/${over.id}/download`,
  createdAt: '2026-08-15T09:10:00',
  ...over,
});

const 사진 = attachment({ id: 9, originalName: '벚꽃.png', kind: 'IMAGE' });
const 자료 = attachment({
  id: 10, originalName: '안내문.pdf', kind: 'FILE', contentType: 'application/pdf', sizeBytes: 3000,
});

const renderPage = async () => {
  render(
    <MemoryRouter initialEntries={['/boards/gallery/7']}>
      <Routes>
        <Route path="/boards/:boardKey/:postId" element={<BoardPostPage />} />
        <Route path="/boards/:boardKey" element={<div>목록 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(mockedPost.read).toHaveBeenCalled());
};

describe('BoardPostPage — 첨부', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedBoard.get.mockResolvedValue(definition());
    mockedPost.read.mockResolvedValue(detail());
    mockedComment.list.mockResolvedValue([]);
    mockedAttachment.list.mockResolvedValue([]);
  });

  it('이미지는 펼쳐 보여 주고 파일은 링크로 내려 준다 — 가르는 기준은 서버가 정한 kind 다', async () => {
    mockedPost.read.mockResolvedValue(detail({ attachments: [사진, 자료] }));

    await renderPage();

    const image = await screen.findByRole('img', { name: '벚꽃.png' });
    expect(image).toHaveAttribute('src', '/api/boards/gallery/attachments/9/download');

    const link = screen.getByRole('link', { name: /안내문\.pdf/ });
    expect(link).toHaveAttribute('href', '/api/boards/gallery/attachments/10/download');
    expect(screen.getByText('3KB')).toBeInTheDocument();
  });

  it('첨부는 상세 응답이 싣고 온다 — 목록을 따로 부르지 않는다(왕복 절약)', async () => {
    mockedPost.read.mockResolvedValue(detail({ attachments: [사진] }));

    await renderPage();

    expect(await screen.findByRole('img', { name: '벚꽃.png' })).toBeInTheDocument();
    expect(mockedAttachment.list).not.toHaveBeenCalled();
  });

  it('첨부를 꺼도 이미 붙은 파일은 그대로 보이고 추가 버튼만 사라진다 — 정책은 미래를 향한다', async () => {
    // 껐다고 기존 파일을 감추면 데이터는 있는데 아무도 못 보고, 직링크로는 여전히 받아진다(G-6).
    mockedBoard.get.mockResolvedValue(definition(false));
    mockedPost.read.mockResolvedValue(detail({ attachments: [사진] }));

    await renderPage();

    expect(await screen.findByRole('img', { name: '벚꽃.png' })).toBeInTheDocument();
    expect(screen.queryByText('첨부 추가')).not.toBeInTheDocument();
  });

  it('첨부가 꺼졌고 파일도 없으면 섹션 자체가 없다', async () => {
    mockedBoard.get.mockResolvedValue(definition(false));

    await renderPage();

    expect(screen.queryByText('첨부 추가')).not.toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('정책 한도를 안내에 그대로 노출한다 — 올리기 전에 알 수 있게', async () => {
    await renderPage();

    expect(await screen.findByText(/최대 3개/)).toBeInTheDocument();
    expect(screen.getByText(/jpg, png, pdf/)).toBeInTheDocument();
  });

  it('파일을 고르면 업로드하고 목록을 다시 읽는다', async () => {
    mockedAttachment.upload.mockResolvedValue(사진);
    mockedAttachment.list.mockResolvedValue([사진]);

    await renderPage();

    const file = new File(['bytes'], '벚꽃.png', { type: 'image/png' });
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(mockedAttachment.upload).toHaveBeenCalledWith('gallery', 7, file));
    expect(await screen.findByRole('img', { name: '벚꽃.png' })).toBeInTheDocument();
  });

  it('업로드가 거절되면 서버 메시지를 그대로 보여 준다 — 고칠 수 있는 이유를 뭉개지 않는다', async () => {
    mockedAttachment.upload.mockRejectedValue({
      response: { data: { message: '파일 내용이 확장자와 다릅니다: shell.jpg (실제 pdf)' } },
    });

    await renderPage();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'shell.jpg', { type: 'image/jpeg' })] } });

    expect(await screen.findByText(/확장자와 다릅니다/)).toBeInTheDocument();
  });

  it('첨부 삭제 버튼은 글을 고칠 수 있을 때만 보인다', async () => {
    mockedPost.read.mockResolvedValue(detail({ mine: false, editable: false, attachments: [사진] }));

    await renderPage();

    expect(await screen.findByRole('img', { name: '벚꽃.png' })).toBeInTheDocument();
    expect(screen.queryByText('삭제')).not.toBeInTheDocument();
    expect(screen.queryByText('첨부 추가')).not.toBeInTheDocument();
  });

  it('삭제하면 목록을 다시 읽는다', async () => {
    mockedPost.read.mockResolvedValue(detail({ attachments: [사진] }));
    mockedAttachment.list.mockResolvedValue([]);
    mockedAttachment.remove.mockResolvedValue(undefined);

    await renderPage();
    // 화면에는 '삭제'가 둘이다(글 삭제 · 첨부 삭제) — 첨부 쪽 캡션 안에서만 고른다.
    const caption = (await screen.findByText('벚꽃.png')).closest('figcaption') as HTMLElement;
    fireEvent.click(within(caption).getByText('삭제'));

    await waitFor(() => expect(mockedAttachment.remove).toHaveBeenCalledWith('gallery', 9));
    await waitFor(() => expect(screen.queryByRole('img', { name: '벚꽃.png' })).not.toBeInTheDocument());
  });
});
