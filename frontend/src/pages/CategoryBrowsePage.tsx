import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  categoryBrowseApi,
  categoryTrail,
  flattenCategories,
  type BrowseCategory,
} from '@/api/categoryBrowse';
import { apiErrorMessage } from '@/lib/apiError';
import type { ProductResponse } from '@/types';

/**
 * 카테고리 탐색 — 구매자가 <b>둘러보는</b> 화면.
 *
 * <p><b>왜 생겼나.</b> 카테고리 트리를 만드는 콘솔은 오래전부터 있었지만
 * ({@code /admin/system/ecommerce-categories}) 그것을 구매자가 보는 길이 없었다. 구매자가
 * 상품을 찾는 방법은 검색창 하나 — 이름을 정확히 떠올려야만 닿았고, 무엇이 있는지
 * <b>둘러볼</b> 방법은 없었다. 분류를 정성껏 짜 놓고 그 결과를 아무도 못 보던 상태다.
 *
 * <p><b>화면 경로가 {@code /categories} 가 아닌 이유.</b> 그건 이 화면이 부르는 API 이고,
 * nginx 두 벌이 {@code categories} 세그먼트를 게이트웨이로 프록시한다. 화면 경로를 같게 두면
 * 새로고침에서 카테고리 목록 JSON 이 그대로 렌더된다 — '내 문의'({@code /my/inquiries})와
 * '여러 곳 배송'({@code /order/multi-destination})이 같은 이유로 지금 자리에 있다.
 *
 * <p><b>선택을 주소에 남긴다.</b> {@code ?category=슬러그} 로 두면 링크를 공유할 수 있고
 * 뒤로 가기가 동작한다. 컴포넌트 state 에만 두면 새로고침 한 번에 사라지고, 그러면 "이 분류
 * 좀 봐" 라고 보낼 주소가 없다.
 *
 * <p><b>슬러그가 트리에 없을 때 서버에 한 번 더 묻는 이유.</b> 트리에서 못 찾은 것과 서버가
 * 없다고 한 것은 다르다 — 앞의 것은 화면의 탐색 실패이고 뒤의 것만 사실이다. 비활성으로
 * 내려간 분류의 옛 링크가 그 차이를 만든다. 물어본 결과가 없으면 그때 "없는 분류"라고 적는다.
 */

const formatPrice = (value: number) => `${value.toLocaleString('ko-KR')}원`;

interface TreeProps {
  nodes: BrowseCategory[];
  selectedId: number | null;
  onSelect: (node: BrowseCategory) => void;
  level?: number;
}

/**
 * 트리를 들여쓰기로 그린다. 깊이는 서버가 준 {@code depth} 가 아니라 <b>재귀 깊이</b>로 센다 —
 * 트리를 부분만 받는 경우(슬러그 조회) {@code depth} 는 전역 깊이라 화면의 들여쓰기와 어긋난다.
 */
function CategoryTree({ nodes, selectedId, onSelect, level = 0 }: TreeProps) {
  return (
    <ul className="space-y-1">
      {nodes.map((node) => (
        <li key={node.id}>
          <button
            type="button"
            onClick={() => onSelect(node)}
            aria-current={node.id === selectedId ? 'true' : undefined}
            data-testid={`browse-category-${node.slug}`}
            style={{ marginLeft: level * 16 }}
            className={`w-full rounded px-2 py-1 text-left text-sm hover:bg-gray-100 ${
              node.id === selectedId ? 'bg-blue-50 font-semibold text-blue-700' : ''
            }`}
          >
            {node.name}
          </button>
          {node.children.length > 0 && (
            <CategoryTree
              nodes={node.children}
              selectedId={selectedId}
              onSelect={onSelect}
              level={level + 1}
            />
          )}
        </li>
      ))}
    </ul>
  );
}

export default function CategoryBrowsePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const slug = searchParams.get('category');

  const [tree, setTree] = useState<BrowseCategory[]>([]);
  const [selected, setSelected] = useState<BrowseCategory | null>(null);
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [productError, setProductError] = useState<string | null>(null);
  const [missingSlug, setMissingSlug] = useState<string | null>(null);
  const [loadingTree, setLoadingTree] = useState(true);
  const [loadingProducts, setLoadingProducts] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const nodes = await categoryBrowseApi.tree();
        if (!cancelled) setTree(nodes);
      } catch (err) {
        if (!cancelled) setTreeError(apiErrorMessage(err, '카테고리를 불러오지 못했습니다.'));
      } finally {
        if (!cancelled) setLoadingTree(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // 주소의 슬러그를 선택으로 되돌린다. 트리에 있으면 그것을 쓰고, 없을 때만 서버에 묻는다 —
  // 트리에 있는 분류까지 한 번 더 물으면 화면을 열 때마다 쓸데없는 왕복이 생긴다.
  useEffect(() => {
    let cancelled = false;
    if (slug === null) {
      setSelected(null);
      setMissingSlug(null);
      return () => { cancelled = true; };
    }
    const found = flattenCategories(tree).find((node) => node.slug === slug);
    if (found) {
      setSelected(found);
      setMissingSlug(null);
      return () => { cancelled = true; };
    }
    if (loadingTree) return () => { cancelled = true; };
    (async () => {
      try {
        const node = await categoryBrowseApi.bySlug(slug);
        if (!cancelled) { setSelected(node); setMissingSlug(null); }
      } catch {
        if (!cancelled) { setSelected(null); setMissingSlug(slug); }
      }
    })();
    return () => { cancelled = true; };
  }, [slug, tree, loadingTree]);

  useEffect(() => {
    let cancelled = false;
    if (selected === null) { setProducts([]); return () => { cancelled = true; }; }
    setLoadingProducts(true);
    setProductError(null);
    (async () => {
      try {
        const list = await categoryBrowseApi.products(selected.id);
        if (!cancelled) setProducts(list);
      } catch (err) {
        if (!cancelled) setProductError(apiErrorMessage(err, '상품을 불러오지 못했습니다.'));
      } finally {
        if (!cancelled) setLoadingProducts(false);
      }
    })();
    return () => { cancelled = true; };
  }, [selected]);

  const select = useCallback((node: BrowseCategory) => {
    setSearchParams({ category: node.slug });
  }, [setSearchParams]);

  const trail = selected === null ? [] : categoryTrail(tree, selected.id);

  return (
    <main className="mx-auto max-w-5xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">카테고리 탐색</h1>
        <p className="text-sm text-gray-500">
          분류를 골라 그 안의 상품을 봅니다. 고른 분류는 주소에 남아 링크로 공유됩니다.
        </p>
      </header>

      {treeError && <p role="alert" className="text-red-600">{treeError}</p>}

      <div className="grid gap-6 md:grid-cols-[16rem_1fr]">
        <nav aria-label="카테고리" className="rounded border p-3">
          {loadingTree ? (
            <p className="text-sm text-gray-500">불러오는 중…</p>
          ) : tree.length === 0 ? (
            <p className="text-sm text-gray-500" data-testid="browse-tree-empty">
              열려 있는 분류가 없습니다.
            </p>
          ) : (
            <CategoryTree nodes={tree} selectedId={selected?.id ?? null} onSelect={select} />
          )}
        </nav>

        <section className="space-y-3">
          {missingSlug !== null && (
            <p role="alert" className="text-amber-700" data-testid="browse-missing">
              &lsquo;{missingSlug}&rsquo; 분류를 찾을 수 없습니다. 닫혔거나 이름이 바뀐 링크일 수 있습니다.
            </p>
          )}

          {/* 같은 depth 의 형제가 여럿이라 depth 숫자만으로는 "무엇 밑의 무엇"인지 못 그린다. */}
          {trail.length > 0 && (
            <p className="text-sm text-gray-500" data-testid="browse-trail">
              {trail.map((node) => node.name).join(' › ')}
            </p>
          )}

          {selected === null ? (
            <p className="text-gray-500" data-testid="browse-no-selection">
              왼쪽에서 분류를 고르세요.
            </p>
          ) : loadingProducts ? (
            <p className="text-gray-500">상품을 불러오는 중…</p>
          ) : productError !== null ? (
            <p role="alert" className="text-red-600">{productError}</p>
          ) : products.length === 0 ? (
            <p className="text-gray-500" data-testid="browse-products-empty">
              이 분류에는 아직 상품이 없습니다.
            </p>
          ) : (
            <ul className="grid gap-3 sm:grid-cols-2" data-testid="browse-products">
              {products.map((product) => (
                <li key={product.id} className="rounded border p-3">
                  <p className="font-semibold">{product.name}</p>
                  <p className="text-sm text-gray-700">{formatPrice(product.price)}</p>
                  {/* 재고를 숫자로 그대로 적는다 — "품절 임박" 같은 문구는 화면이 만든 판단이라
                      서버의 판매 가능 판정과 갈릴 수 있다. */}
                  <p className="text-xs text-gray-500">
                    {product.availableForSale ? `재고 ${product.stockQuantity}개` : '판매 중지'}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </main>
  );
}
