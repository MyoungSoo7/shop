import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import TagManagementPage from '@/pages/TagManagementPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { tagApi } from '@/api/tag';

/**
 * useEffect 의존성을 채우면서(exhaustive-deps) 재조회 루프가 생기지 않는지 지키는 회귀 테스트.
 *
 * <p>의존성에 매 렌더 새로 만들어지는 함수를 넣으면 effect → setState → 렌더 → effect 로 무한 루프가
 * 돈다. 마운트 1회형 — 로더를 useCallback([showToast]) 로 고정 — 을 TagManagementPage 로 대표한다.
 */

vi.mock('@/api/tag', () => ({
  tagApi: { getAllTags: vi.fn(), createTag: vi.fn(), updateTag: vi.fn(), deleteTag: vi.fn() },
}));

describe('useEffect 의존성 — 재조회 루프 방지', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('마운트 1회형: 로더가 정확히 한 번만 호출된다 (TagManagementPage)', async () => {
    vi.mocked(tagApi.getAllTags).mockResolvedValue([]);

    render(
      <ToastProvider>
        <TagManagementPage />
      </ToastProvider>,
    );

    await waitFor(() => expect(tagApi.getAllTags).toHaveBeenCalled());
    // 응답 반영 후 리렌더가 끝난 뒤에도 추가 호출이 없어야 한다.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(tagApi.getAllTags).toHaveBeenCalledTimes(1);
  });
});
