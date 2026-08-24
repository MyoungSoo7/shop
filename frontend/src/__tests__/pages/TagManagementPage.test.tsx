import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TagManagementPage from '@/pages/TagManagementPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { tagApi } from '@/api/tag';

vi.mock('@/api/tag', () => ({
  tagApi: { getAllTags: vi.fn(), createTag: vi.fn(), deleteTag: vi.fn() },
}));

const mocked = vi.mocked(tagApi);

const tag = (over: Partial<{ id: number; name: string; color: string }> = {}) => ({
  id: 1, name: '신상', color: '#EF4444', createdAt: '2026-08-01T10:00:00', ...over,
});

const renderPage = () => render(<ToastProvider><TagManagementPage /></ToastProvider>);

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.getAllTags.mockResolvedValue([tag()] as never);
  mocked.createTag.mockResolvedValue(tag({ id: 2, name: '세일' }) as never);
  mocked.deleteTag.mockResolvedValue(undefined as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => confirmSpy.mockRestore());

/**
 * 태그 관리 화면 — 생성·삭제 후 <b>목록을 다시 읽는지</b>가 핵심이다.
 *
 * <p>다시 읽지 않으면 화면이 옛 목록을 들고 있어, 운영자가 방금 만든 태그가 없다고 판단해
 * 같은 태그를 또 만든다(이름 중복 태그가 실제로 그렇게 생긴다).
 */
describe('TagManagementPage — 조회', () => {
  it('진입하면 태그를 읽어 카드로 보여 준다', async () => {
    renderPage();

    expect(await screen.findByText('신상')).toBeInTheDocument();
    expect(mocked.getAllTags).toHaveBeenCalledTimes(1);
  });

  it('태그가 없으면 빈 목록임을 문장으로 알린다 — 로딩과 구분된다', async () => {
    mocked.getAllTags.mockResolvedValue([] as never);
    renderPage();

    expect(await screen.findByText('등록된 태그가 없습니다.')).toBeInTheDocument();
  });

  it('조회가 실패하면 화면을 비워 두지 않고 실패를 알린다', async () => {
    mocked.getAllTags.mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText('태그 목록 조회 실패')).toBeInTheDocument();
  });
});

describe('TagManagementPage — 생성', () => {
  it('폼은 접혀 있고, 버튼으로 펼치고 다시 접는다', async () => {
    renderPage();
    await screen.findByText('신상');

    expect(screen.queryByText('새 태그 생성')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '+ 새 태그' }));
    expect(screen.getByText('새 태그 생성')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByText('새 태그 생성')).not.toBeInTheDocument();
  });

  it('생성하면 폼을 접고 목록을 다시 읽는다', async () => {
    renderPage();
    await screen.findByText('신상');
    fireEvent.click(screen.getByRole('button', { name: '+ 새 태그' }));

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '세일' } });
    fireEvent.click(screen.getByRole('button', { name: '초록' }));   // 프리셋 색 선택
    fireEvent.click(screen.getByRole('button', { name: '생성' }));

    await waitFor(() => expect(mocked.createTag).toHaveBeenCalledWith({ name: '세일', color: '#10B981' }));
    expect(await screen.findByText('태그가 생성되었습니다')).toBeInTheDocument();
    await waitFor(() => expect(mocked.getAllTags).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('새 태그 생성')).not.toBeInTheDocument();
  });

  it('생성이 실패하면 폼을 열어 둔 채 사유를 알린다 — 입력을 날리지 않는다', async () => {
    mocked.createTag.mockRejectedValue(new Error('duplicate'));
    renderPage();
    await screen.findByText('신상');
    fireEvent.click(screen.getByRole('button', { name: '+ 새 태그' }));

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '중복' } });
    fireEvent.click(screen.getByRole('button', { name: '생성' }));

    expect(await screen.findByText('태그 생성 실패')).toBeInTheDocument();
    expect(screen.getByText('새 태그 생성')).toBeInTheDocument();
    expect(screen.getByRole('textbox')).toHaveValue('중복');
  });
});

describe('TagManagementPage — 삭제', () => {
  it('확인을 취소하면 삭제하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    await screen.findByText('신상');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(mocked.deleteTag).not.toHaveBeenCalled();
  });

  it('확인하면 삭제하고 목록을 다시 읽는다', async () => {
    renderPage();
    await screen.findByText('신상');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.deleteTag).toHaveBeenCalledWith(1));
    expect(await screen.findByText('태그가 삭제되었습니다')).toBeInTheDocument();
    await waitFor(() => expect(mocked.getAllTags).toHaveBeenCalledTimes(2));
  });

  it('삭제가 실패하면 사유를 알린다', async () => {
    mocked.deleteTag.mockRejectedValue(new Error('in use'));
    renderPage();
    await screen.findByText('신상');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(await screen.findByText('태그 삭제 실패')).toBeInTheDocument();
  });
});
