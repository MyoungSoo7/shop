import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>공개 API 만 부른다.</b> 관리 API(/admin/categories)는 비활성 분류까지 내려주므로,
 * 구매자 화면이 그것을 부르면 아직 열지 않은 분류가 그대로 노출된다.
 *
 * <p>② <b>선택은 주소에 남는다.</b> 컴포넌트 state 에만 두면 새로고침 한 번에 사라지고,
 * 그러면 "이 분류 좀 봐" 라고 보낼 주소가 없다.
 *
 * <p>③ <b>트리에 있는 분류는 다시 묻지 않는다.</b> 물으면 화면을 열 때마다 쓸데없는 왕복이 생긴다.
 *
 * <p>④ <b>트리에서 못 찾은 것과 서버가 없다고 한 것을 구분한다.</b> 앞의 것은 화면의 탐색
 * 실패이고 뒤의 것만 사실이다 — 비활성으로 내려간 분류의 옛 링크가 그 차이를 만든다.
 */

vi.mock('@/api/categoryBrowse', async () => {
  const actual = await vi.importActual<typeof import('@/api/categoryBrowse')>('@/api/categoryBrowse');
  return {
    ...actual,
    categoryBrowseApi: { tree: vi.fn(), bySlug: vi.fn(), products: vi.fn() },
  };
});

const { categoryBrowseApi } = await import('@/api/categoryBrowse');
const { default: CategoryBrowsePage } = await import('@/pages/CategoryBrowsePage');

const browse = vi.mocked(categoryBrowseApi);

const node = (over: Record<string, unknown> = {}) => ({
  id: 1,
  name: '전자',
  slug: 'electronics',
  parentId: null,
  depth: 0,
  sortOrder: 1,
  isActive: true,
  createdAt: '2026-08-01T00:00:00+09:00',
  updatedAt: '2026-08-01T00:00:00+09:00',
  children: [],
  ...over,
});

const product = (over: Record<string, unknown> = {}) => ({
  id: 10,
  name: '노트북 A',
  price: 1200000,
  stockQuantity: 5,
  status: 'ACTIVE',
  availableForSale: true,
  createdAt: '2026-08-01T00:00:00+09:00',
  updatedAt: '2026-08-01T00:00:00+09:00',
  ...over,
});

const TREE = [
  node({ id: 1, children: [node({ id: 2, name: '노트북', slug: 'laptops', parentId: 1, depth: 1 })] }),
  node({ id: 3, name: '의류', slug: 'apparel' }),
];

const renderPage = (entry = '/browse') =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <CategoryBrowsePage />
    </MemoryRouter>,
  );

describe('CategoryBrowsePage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    browse.tree.mockResolvedValue(TREE as never);
    browse.products.mockResolvedValue([product()] as never);
  });

  it('트리를 그리고, 고르기 전에는 상품을 부르지 않는다', async () => {
    renderPage();

    expect(await screen.findByTestId('browse-category-electronics')).toBeInTheDocument();
    expect(screen.getByTestId('browse-category-laptops')).toBeInTheDocument();
    expect(screen.getByTestId('browse-no-selection')).toBeInTheDocument();
    expect(browse.products).not.toHaveBeenCalled();
  });

  it('분류를 고르면 그 분류의 상품을 서버에서 받아 그린다', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByTestId('browse-category-laptops'));

    await waitFor(() => expect(browse.products).toHaveBeenCalledWith(2));
    expect(await screen.findByTestId('browse-products')).toHaveTextContent('노트북 A');
  });

  it('고른 분류의 조상 경로를 함께 적는다 — depth 숫자만으로는 못 그린다', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByTestId('browse-category-laptops'));

    expect(await screen.findByTestId('browse-trail')).toHaveTextContent('전자 › 노트북');
  });

  it('주소의 슬러그로 바로 들어와도 선택이 복원된다', async () => {
    renderPage('/browse?category=apparel');

    await waitFor(() => expect(browse.products).toHaveBeenCalledWith(3));
  });

  it('트리에 있는 슬러그는 서버에 다시 묻지 않는다', async () => {
    renderPage('/browse?category=apparel');

    await waitFor(() => expect(browse.products).toHaveBeenCalled());
    expect(browse.bySlug).not.toHaveBeenCalled();
  });

  it('트리에 없는 슬러그는 서버에 한 번 물어본다', async () => {
    browse.bySlug.mockResolvedValue(node({ id: 9, name: '단종', slug: 'legacy' }) as never);

    renderPage('/browse?category=legacy');

    await waitFor(() => expect(browse.bySlug).toHaveBeenCalledWith('legacy'));
    await waitFor(() => expect(browse.products).toHaveBeenCalledWith(9));
  });

  it('서버도 모르는 슬러그라야 "없는 분류"라고 적는다', async () => {
    browse.bySlug.mockRejectedValue(new Error('404'));

    renderPage('/browse?category=ghost');

    expect(await screen.findByTestId('browse-missing')).toHaveTextContent('ghost');
    expect(browse.products).not.toHaveBeenCalled();
  });

  it('상품이 없는 분류는 빈 목록이라고 적는다 — 로딩 중과 구분된다', async () => {
    browse.products.mockResolvedValue([] as never);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByTestId('browse-category-apparel'));

    expect(await screen.findByTestId('browse-products-empty')).toBeInTheDocument();
  });

  it('트리를 못 불러오면 사유를 띄운다', async () => {
    browse.tree.mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
