import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ImageUpload from '@/components/product/ImageUpload';
import api from '@/api/axios';
import { ToastProvider } from '@/contexts/ToastContext';

const onImagesChange = vi.fn();

const images = [
  {
    id: 1,
    url: '/one.jpg',
    originalFileName: 'one.jpg',
    isPrimary: true,
    orderIndex: 0,
    sizeBytes: 1000,
    width: 100,
    height: 80,
  },
  {
    id: 2,
    url: '/two.jpg',
    originalFileName: 'two.jpg',
    isPrimary: false,
    orderIndex: 1,
    sizeBytes: 2000,
    width: 120,
    height: 90,
  },
];

const renderUpload = (items = images) =>
  render(
    <ToastProvider>
      <ImageUpload productId={10} images={items} onImagesChange={onImagesChange} />
    </ToastProvider>
  );

describe('ImageUpload', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'post').mockResolvedValue({});
    vi.spyOn(api, 'patch').mockResolvedValue({});
    vi.spyOn(api, 'delete').mockResolvedValue({});
    onImagesChange.mockReset();
  });

  it('업로드 영역과 이미지 목록을 렌더링한다', () => {
    renderUpload();

    expect(screen.getByText('이미지를 드래그하거나 클릭하여 업로드')).toBeInTheDocument();
    expect(screen.getByAltText('one.jpg')).toBeInTheDocument();
    expect(screen.getByText('100×80')).toBeInTheDocument();
    expect(screen.getAllByText('대표')).toHaveLength(2);
  });

  it('유효한 파일을 선택하면 업로드하고 콜백을 호출한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({});
    renderUpload([]);

    const file = new File(['image'], 'good.png', { type: 'image/png' });
    fireEvent.change(document.querySelector('input[type="file"]')!, {
      target: { files: [file] },
    });

    expect(screen.getByText('업로드 중...')).toBeInTheDocument();
    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/admin/products/10/images',
        expect.any(FormData),
        { headers: { 'Content-Type': 'multipart/form-data' } }
      );
    });
    expect(screen.getByText('이미지가 업로드되었습니다.')).toBeInTheDocument();
    expect(onImagesChange).toHaveBeenCalledTimes(1);
  });

  it('지원하지 않는 파일 타입은 에러 토스트를 표시한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({});
    renderUpload([]);

    const file = new File(['text'], 'bad.txt', { type: 'text/plain' });
    fireEvent.change(document.querySelector('input[type="file"]')!, {
      target: { files: [file] },
    });

    await waitFor(() => {
      expect(screen.getByText('bad.txt은(는) 지원하지 않는 파일 형식입니다.')).toBeInTheDocument();
    });
  });

  it('5MB 초과 파일은 에러 토스트를 표시한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({});
    renderUpload([]);

    const file = new File(['x'], 'large.png', { type: 'image/png' });
    Object.defineProperty(file, 'size', { value: 5 * 1024 * 1024 + 1 });
    fireEvent.change(document.querySelector('input[type="file"]')!, {
      target: { files: [file] },
    });

    await waitFor(() => {
      expect(screen.getByText('large.png의 크기가 5MB를 초과합니다.')).toBeInTheDocument();
    });
  });

  it('업로드 실패 시 에러 토스트를 표시한다', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error('fail'));
    renderUpload([]);

    fireEvent.change(document.querySelector('input[type="file"]')!, {
      target: { files: [new File(['image'], 'fail.png', { type: 'image/png' })] },
    });

    await waitFor(() => {
      expect(screen.getByText('이미지 업로드 실패')).toBeInTheDocument();
    });
  });

  it('드래그 상태를 토글하고 drop 파일을 업로드한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({});
    renderUpload([]);
    const dropZone = screen.getByText('이미지를 드래그하거나 클릭하여 업로드').parentElement!;

    fireEvent.dragEnter(dropZone);
    expect(dropZone).toHaveClass('border-blue-500');

    fireEvent.dragLeave(dropZone);
    expect(dropZone).not.toHaveClass('border-blue-500');

    fireEvent.drop(dropZone, {
      dataTransfer: { files: [new File(['image'], 'drop.webp', { type: 'image/webp' })] },
    });

    await waitFor(() => {
      expect(api.post).toHaveBeenCalled();
    });
  });

  it('대표 이미지 설정 성공과 실패를 처리한다', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({});
    renderUpload();

    fireEvent.click(screen.getAllByText('대표')[1]);

    await waitFor(() => {
      expect(api.patch).toHaveBeenCalledWith('/admin/products/10/images/2/primary');
    });
    expect(screen.getByText('대표 이미지가 설정되었습니다.')).toBeInTheDocument();
    expect(onImagesChange).toHaveBeenCalledTimes(1);

    vi.mocked(api.patch).mockRejectedValueOnce(new Error('fail'));
    fireEvent.click(screen.getAllByText('대표')[1]);

    await waitFor(() => {
      expect(screen.getByText('대표 이미지 설정 실패')).toBeInTheDocument();
    });
  });

  it('이미지 삭제 확인, 취소, 실패를 처리한다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValueOnce(false));
    renderUpload();

    fireEvent.click(screen.getAllByText('삭제')[0]);
    expect(api.delete).not.toHaveBeenCalled();

    vi.mocked(window.confirm).mockReturnValueOnce(true);
    vi.mocked(api.delete).mockResolvedValueOnce({});
    fireEvent.click(screen.getAllByText('삭제')[0]);

    await waitFor(() => {
      expect(api.delete).toHaveBeenCalledWith('/admin/products/10/images/1');
    });
    expect(screen.getByText('이미지가 삭제되었습니다.')).toBeInTheDocument();

    vi.mocked(window.confirm).mockReturnValueOnce(true);
    vi.mocked(api.delete).mockRejectedValueOnce(new Error('fail'));
    fireEvent.click(screen.getAllByText('삭제')[0]);

    await waitFor(() => {
      expect(screen.getByText('이미지 삭제 실패')).toBeInTheDocument();
    });
    vi.unstubAllGlobals();
  });

  it('이미지 순서 변경 성공과 실패를 처리한다', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({});
    renderUpload();

    fireEvent.click(screen.getAllByText('↑')[1]);

    await waitFor(() => {
      expect(api.patch).toHaveBeenCalledWith('/admin/products/10/images/reorder', { imageIds: [2, 1] });
    });
    expect(screen.getByText('이미지 순서가 변경되었습니다.')).toBeInTheDocument();

    vi.mocked(api.patch).mockRejectedValueOnce(new Error('fail'));
    fireEvent.click(screen.getAllByText('↓')[0]);

    await waitFor(() => {
      expect(screen.getByText('이미지 순서 변경 실패')).toBeInTheDocument();
    });
  });
});
