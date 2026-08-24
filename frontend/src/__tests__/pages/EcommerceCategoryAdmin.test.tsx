import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EcommerceCategoryAdmin from '@/pages/EcommerceCategoryAdmin';
import api from '@/api/axios';

const showToast = vi.fn();

vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

// 상품수 정합성 패널은 별도 화면 조각이라 이 테스트의 관심사가 아니다.
vi.mock('@/components/category/CategoryCountIntegrityPanel', () => ({
  default: () => null,
}));

const mockedApi = vi.mocked(api);

interface Cat {
  id: number;
  name: string;
  slug: string;
  parentId?: number;
  depth: number;
  sortOrder: number;
  isActive: boolean;
  children: Cat[];
}

const cat = (over: Partial<Cat> = {}): Cat => ({
  id: 1,
  name: '전자제품',
  slug: 'electronics',
  depth: 0,
  sortOrder: 0,
  isActive: true,
  children: [],
  ...over,
});

const tree: Cat[] = [
  cat({
    id: 1,
    name: '전자제품',
    slug: 'electronics',
    children: [cat({ id: 3, name: '노트북', slug: 'laptop', parentId: 1, depth: 1, sortOrder: 0 })],
  }),
  cat({ id: 2, name: '의류', slug: 'clothing', sortOrder: 1, isActive: false }),
];

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedApi.get.mockResolvedValue({ data: tree } as never);
  mockedApi.post.mockResolvedValue({ data: {} } as never);
  mockedApi.put.mockResolvedValue({ data: {} } as never);
  mockedApi.patch.mockResolvedValue({ data: {} } as never);
  mockedApi.delete.mockResolvedValue({ data: {} } as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => confirmSpy.mockRestore());

const renderAndWait = async () => {
  render(<EcommerceCategoryAdmin />);
  await screen.findByText('전자제품');
};

const rowOf = (name: string) =>
  screen.getByText(name).closest('div.flex.items-center.justify-between') as HTMLElement;

describe('EcommerceCategoryAdmin — 트리', () => {
  it('트리를 계층으로 그리고 depth·정렬·상태를 함께 보여 준다', async () => {
    await renderAndWait();

    expect(screen.getByText('노트북')).toBeInTheDocument();
    expect(screen.getByText('depth: 1')).toBeInTheDocument();
    expect(screen.getAllByText('활성').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('비활성')).toBeInTheDocument();
    expect(mockedApi.get).toHaveBeenCalledWith('/admin/categories');
  });

  it('목록 조회 실패는 토스트로 알린다', async () => {
    mockedApi.get.mockRejectedValue(new Error('down'));
    render(<EcommerceCategoryAdmin />);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('카테고리 목록 조회 실패', 'error'));
  });

  it('depth 2 미만에서만 하위 추가 버튼을 노출한다 (최대 3단계)', async () => {
    mockedApi.get.mockResolvedValue({
      data: [cat({ id: 9, name: '3단계', depth: 2, children: [] })],
    } as never);
    await renderAndWait2('3단계');

    expect(screen.queryByRole('button', { name: '+ 하위 추가' })).not.toBeInTheDocument();
  });

  async function renderAndWait2(text: string) {
    render(<EcommerceCategoryAdmin />);
    await screen.findByText(text);
  }
});

describe('EcommerceCategoryAdmin — 생성', () => {
  it('최상위 생성은 parentId 없이 보낸다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '+ 최상위 카테고리 생성' }));
    await userEvent.type(screen.getByPlaceholderText('예: 전자제품'), '식품');
    await userEvent.click(screen.getByRole('button', { name: '생성' }));

    await waitFor(() =>
      expect(mockedApi.post).toHaveBeenCalledWith('/admin/categories', {
        name: '식품',
        slug: undefined,
        parentId: null,
        sortOrder: 0,
      }),
    );
    expect(showToast).toHaveBeenCalledWith('카테고리가 생성되었습니다', 'success');
  });

  it('하위 추가는 그 카테고리를 부모로 잡는다', async () => {
    await renderAndWait();

    await userEvent.click(within(rowOf('전자제품')).getByRole('button', { name: '+ 하위 추가' }));

    expect(screen.getByText('선택된 부모 카테고리 ID: 1')).toBeInTheDocument();

    await userEvent.type(screen.getByPlaceholderText('예: 전자제품'), '태블릿');
    await userEvent.click(screen.getByRole('button', { name: '생성' }));

    await waitFor(() =>
      expect(mockedApi.post).toHaveBeenCalledWith(
        '/admin/categories',
        expect.objectContaining({ name: '태블릿', parentId: 1 }),
      ),
    );
  });

  it('생성 실패는 서버 사유를 토스트로 알린다', async () => {
    mockedApi.post.mockRejectedValue({ response: { data: { message: '중복 slug' } } });
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '+ 최상위 카테고리 생성' }));
    await userEvent.type(screen.getByPlaceholderText('예: 전자제품'), 'X');
    await userEvent.click(screen.getByRole('button', { name: '생성' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('중복 slug', 'error'));
  });

  it('생성 폼은 토글로 닫힌다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '+ 최상위 카테고리 생성' }));
    expect(screen.getByPlaceholderText('예: 전자제품')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByPlaceholderText('예: 전자제품')).not.toBeInTheDocument();
  });
});

describe('EcommerceCategoryAdmin — 상태·삭제·정렬', () => {
  it('활성 카테고리는 비활성화 엔드포인트로 보낸다', async () => {
    await renderAndWait();

    await userEvent.click(within(rowOf('전자제품')).getByRole('button', { name: '비활성화' }));

    await waitFor(() =>
      expect(mockedApi.patch).toHaveBeenCalledWith('/admin/categories/1/deactivate'),
    );
    expect(showToast).toHaveBeenCalledWith('카테고리가 비활성화되었습니다', 'success');
  });

  it('비활성 카테고리는 활성화 엔드포인트로 보낸다', async () => {
    await renderAndWait();

    await userEvent.click(within(rowOf('의류')).getByRole('button', { name: '활성화' }));

    await waitFor(() => expect(mockedApi.patch).toHaveBeenCalledWith('/admin/categories/2/activate'));
  });

  it('상태 변경 실패는 토스트로 알린다', async () => {
    mockedApi.patch.mockRejectedValue(new Error('down'));
    await renderAndWait();

    await userEvent.click(within(rowOf('전자제품')).getByRole('button', { name: '비활성화' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('카테고리 상태 변경 실패', 'error'),
    );
  });

  it('삭제는 확인을 거치고 서버 사유를 그대로 전달한다', async () => {
    mockedApi.delete.mockRejectedValue({ response: { data: { message: '하위 카테고리 존재' } } });
    await renderAndWait();

    await userEvent.click(within(rowOf('전자제품')).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('하위 카테고리 존재', 'error'));
  });

  it('삭제 확인을 취소하면 호출하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderAndWait();

    await userEvent.click(within(rowOf('전자제품')).getByRole('button', { name: '삭제' }));

    expect(mockedApi.delete).not.toHaveBeenCalled();
  });

  it('형제 간 순서 이동은 자리가 바뀐 항목만 다시 저장한다', async () => {
    await renderAndWait();

    await userEvent.click(within(rowOf('의류')).getByTitle('위로'));

    await waitFor(() => expect(mockedApi.patch).toHaveBeenCalledWith(
      '/admin/categories/2/sort',
      { sortOrder: 0 },
    ));
    expect(mockedApi.patch).toHaveBeenCalledWith('/admin/categories/1/sort', { sortOrder: 1 });
  });

  it('첫 항목의 위로·마지막 항목의 아래로는 잠겨 있다', async () => {
    await renderAndWait();

    expect(within(rowOf('전자제품')).getByTitle('위로')).toBeDisabled();
    expect(within(rowOf('의류')).getByTitle('아래로')).toBeDisabled();
  });

  it('순서 변경 실패는 토스트로 알린다', async () => {
    mockedApi.patch.mockRejectedValue(new Error('down'));
    await renderAndWait();

    await userEvent.click(within(rowOf('의류')).getByTitle('위로'));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('순서 변경 실패', 'error'));
  });
});

describe('EcommerceCategoryAdmin — 수정·이동', () => {
  it('수정 폼은 기존 값을 채워 놓는다', async () => {
    await renderAndWait();

    await userEvent.click(within(rowOf('노트북')).getByRole('button', { name: '수정' }));

    expect((screen.getByPlaceholderText('소문자, 숫자, 하이픈만') as HTMLInputElement).value).toBe(
      'laptop',
    );
  });

  it('부모가 그대로면 이동 API 를 부르지 않는다', async () => {
    await renderAndWait();
    await userEvent.click(within(rowOf('노트북')).getByRole('button', { name: '수정' }));

    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedApi.put).toHaveBeenCalledWith('/admin/categories/3', {
      name: '노트북',
      slug: 'laptop',
    }));
    expect(mockedApi.patch).not.toHaveBeenCalled();
  });

  it('부모를 최상위로 바꾸면 이동 API 를 부른다', async () => {
    await renderAndWait();
    await userEvent.click(within(rowOf('노트북')).getByRole('button', { name: '수정' }));

    await userEvent.selectOptions(screen.getByRole('combobox'), '');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mockedApi.patch).toHaveBeenCalledWith('/admin/categories/3/move', {
        newParentId: null,
      }),
    );
  });

  it('수정 실패는 토스트로 알린다', async () => {
    mockedApi.put.mockRejectedValue(new Error('down'));
    await renderAndWait();
    await userEvent.click(within(rowOf('노트북')).getByRole('button', { name: '수정' }));

    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('카테고리 수정 실패', 'error'));
  });
});
