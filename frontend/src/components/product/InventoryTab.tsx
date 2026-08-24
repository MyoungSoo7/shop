import React, { useState, useEffect, useCallback } from 'react';
import { ProductResponse, ProductStatus } from '@/types';
import { productApi } from '@/api/product';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

/* ─── 재고 색상 ─── */
const stockColor = (qty: number) => {
  if (qty === 0) return 'text-red-600 bg-red-50';
  if (qty <= 10) return 'text-orange-600 bg-orange-50';
  return 'text-green-700 bg-green-50';
};

/* ─── 상태 배지 ─── */
const STATUS_META: Record<ProductStatus, { label: string; cls: string }> = {
  ACTIVE:       { label: '판매 중',    cls: 'bg-green-100 text-green-800' },
  INACTIVE:     { label: '비활성',     cls: 'bg-gray-100  text-gray-600'  },
  OUT_OF_STOCK: { label: '품절',       cls: 'bg-red-100   text-red-700'   },
  DISCONTINUED: { label: '단종',       cls: 'bg-purple-100 text-purple-700' },
};

/* ─── 요약 카드 ─── */
interface SummaryCardProps {
  label: string;
  value: number;
  sub?: string;
  color: string;
  icon: React.ReactNode;
}
const SummaryCard: React.FC<SummaryCardProps> = ({ label, value, sub, color, icon }) => (
  <div className={`bg-white rounded-xl border border-gray-200 p-5 shadow-sm flex items-center gap-4`}>
    <div className={`w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 ${color}`}>
      {icon}
    </div>
    <div>
      <p className="text-sm text-gray-500">{label}</p>
      <p className="text-2xl font-bold text-gray-900">{value}<span className="text-sm font-normal text-gray-400 ml-1">개</span></p>
      {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
    </div>
  </div>
);

/* ─── 재고 조정 인라인 패널 ─── */
interface AdjustPanelProps {
  product: ProductResponse;
  onDone: (updated: ProductResponse) => void;
}
const AdjustPanel: React.FC<AdjustPanelProps> = ({ product, onDone }) => {
  const [qty, setQty] = useState('');
  const [loading, setLoading] = useState(false);
  const { showToast } = useToast();

  const handleAdjust = async (operation: 'INCREASE' | 'DECREASE') => {
    const amount = parseInt(qty, 10);
    if (!amount || amount <= 0) { showToast('수량을 1 이상 입력해주세요.', 'error'); return; }
    if (operation === 'DECREASE' && amount > product.stockQuantity) {
      showToast(`최대 출고 가능 수량은 ${product.stockQuantity}개입니다.`, 'error'); return;
    }
    setLoading(true);
    try {
      const updated = await productApi.updateProductStock(product.id, { quantity: amount, operation });
      const label = operation === 'INCREASE' ? '입고' : '출고';
      showToast(`${product.name}: ${amount}개 ${label} 완료`, 'success');
      setQty('');
      onDone(updated);
    } catch (err) {
      showToast(apiErrorMessage(err, '재고 조정에 실패했습니다.'), 'error');
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <Spinner size="sm" />;

  return (
    <div className="flex items-center gap-1.5">
      <input
        type="number"
        min="1"
        value={qty}
        onChange={(e) => setQty(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') handleAdjust('INCREASE'); }}
        placeholder="수량"
        className="w-20 px-2 py-1.5 border border-gray-300 rounded-lg text-sm text-gray-900 text-center focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
      />
      <button
        onClick={() => handleAdjust('INCREASE')}
        disabled={!qty}
        className="px-2.5 py-1.5 bg-blue-600 text-white text-xs font-semibold rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
      >
        + 입고
      </button>
      <button
        onClick={() => handleAdjust('DECREASE')}
        disabled={!qty || product.stockQuantity === 0}
        className="px-2.5 py-1.5 bg-orange-500 text-white text-xs font-semibold rounded-lg hover:bg-orange-600 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
      >
        - 출고
      </button>
    </div>
  );
};

/* ─── 상태 변경 버튼 ─── */
interface StatusActionsProps {
  product: ProductResponse;
  onDone: (updated: ProductResponse) => void;
}
const StatusActions: React.FC<StatusActionsProps> = ({ product, onDone }) => {
  const [loading, setLoading] = useState(false);
  const { showToast } = useToast();

  const handle = async (action: 'activate' | 'deactivate' | 'discontinue') => {
    if (action === 'discontinue' && !window.confirm(`"${product.name}"을(를) 단종 처리하시겠습니까?`)) return;
    setLoading(true);
    try {
      let updated: ProductResponse;
      if (action === 'activate')    updated = await productApi.activateProduct(product.id);
      else if (action === 'deactivate') updated = await productApi.deactivateProduct(product.id);
      else                           updated = await productApi.discontinueProduct(product.id);
      const labels = { activate: '활성화', deactivate: '비활성화', discontinue: '단종' };
      showToast(`${product.name}: ${labels[action]} 완료`, 'success');
      onDone(updated);
    } catch (err) {
      showToast(apiErrorMessage(err, '상태 변경에 실패했습니다.'), 'error');
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <Spinner size="sm" />;

  const { status } = product;
  return (
    <div className="flex flex-wrap gap-1">
      {status !== 'ACTIVE' && status !== 'DISCONTINUED' && (
        <button onClick={() => handle('activate')}
          className="px-2 py-1 text-xs font-medium rounded bg-green-100 text-green-700 hover:bg-green-200 transition-colors">
          활성화
        </button>
      )}
      {status === 'ACTIVE' && (
        <button onClick={() => handle('deactivate')}
          className="px-2 py-1 text-xs font-medium rounded bg-gray-100 text-gray-600 hover:bg-gray-200 transition-colors">
          비활성화
        </button>
      )}
      {status === 'DISCONTINUED' && (
        <button onClick={() => handle('activate')}
          className="px-2 py-1 text-xs font-medium rounded bg-green-100 text-green-700 hover:bg-green-200 transition-colors">
          재판매
        </button>
      )}
      {status !== 'DISCONTINUED' && (
        <button onClick={() => handle('discontinue')}
          className="px-2 py-1 text-xs font-medium rounded bg-red-100 text-red-600 hover:bg-red-200 transition-colors">
          단종
        </button>
      )}
    </div>
  );
};

/* ──────────────────────────────────────────
   메인 InventoryTab 컴포넌트
────────────────────────────────────────── */
type FilterType = 'ALL' | 'ACTIVE' | 'LOW' | 'OUT' | 'INACTIVE';

const FILTERS: { id: FilterType; label: string }[] = [
  { id: 'ALL',      label: '전체' },
  { id: 'ACTIVE',   label: '판매 중' },
  { id: 'LOW',      label: '저재고 (≤10)' },
  { id: 'OUT',      label: '품절' },
  { id: 'INACTIVE', label: '비활성/단종' },
];

type SortField = 'name' | 'stock' | 'price' | 'status';

const InventoryTab: React.FC = () => {
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<FilterType>('ALL');
  const [sortField, setSortField] = useState<SortField>('stock');
  const [sortAsc, setSortAsc] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await productApi.getAllProducts();
      setProducts(data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* 상태 업데이트 (API 성공 후 리스트 항목만 교체) */
  const handleProductUpdated = (updated: ProductResponse) => {
    setProducts((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
  };

  /* 요약 통계 */
  const total      = products.length;
  const active     = products.filter((p) => p.status === 'ACTIVE' && p.stockQuantity > 0).length;
  const lowStock   = products.filter((p) => p.stockQuantity > 0 && p.stockQuantity <= 10).length;
  const outOfStock = products.filter((p) => p.stockQuantity === 0).length;

  /* 필터링 */
  const filtered = products.filter((p) => {
    const matchSearch = p.name.toLowerCase().includes(search.toLowerCase()) ||
      (p.description ?? '').toLowerCase().includes(search.toLowerCase());
    if (!matchSearch) return false;
    switch (filter) {
      case 'ACTIVE':   return p.status === 'ACTIVE' && p.stockQuantity > 0;
      case 'LOW':      return p.stockQuantity > 0 && p.stockQuantity <= 10;
      case 'OUT':      return p.stockQuantity === 0;
      case 'INACTIVE': return p.status === 'INACTIVE' || p.status === 'DISCONTINUED';
      default:         return true;
    }
  });

  /* 정렬 */
  const sorted = [...filtered].sort((a, b) => {
    let cmp = 0;
    if (sortField === 'name')   cmp = a.name.localeCompare(b.name);
    if (sortField === 'stock')  cmp = a.stockQuantity - b.stockQuantity;
    if (sortField === 'price')  cmp = a.price - b.price;
    if (sortField === 'status') cmp = a.status.localeCompare(b.status);
    return sortAsc ? cmp : -cmp;
  });

  const handleSort = (field: SortField) => {
    if (sortField === field) setSortAsc((v) => !v);
    else { setSortField(field); setSortAsc(true); }
  };

  const SortIcon: React.FC<{ field: SortField }> = ({ field }) => {
    if (sortField !== field) return (
      <svg className="w-3.5 h-3.5 text-gray-400 inline ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M8 9l4-4 4 4M16 15l-4 4-4-4" />
      </svg>
    );
    return sortAsc ? (
      <svg className="w-3.5 h-3.5 text-blue-500 inline ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 15l7-7 7 7" />
      </svg>
    ) : (
      <svg className="w-3.5 h-3.5 text-blue-500 inline ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 9l-7 7-7-7" />
      </svg>
    );
  };

  if (loading) return (
    <div className="py-20"><Spinner size="lg" message="재고 현황 불러오는 중..." /></div>
  );

  return (
    <div className="space-y-6">

      {/* ── 요약 카드 ── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <SummaryCard
          label="전체 상품"
          value={total}
          color="bg-blue-100"
          icon={
            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
            </svg>
          }
        />
        <SummaryCard
          label="판매 중"
          value={active}
          sub="재고 있는 ACTIVE 상품"
          color="bg-green-100"
          icon={
            <svg className="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          }
        />
        <SummaryCard
          label="저재고 위험"
          value={lowStock}
          sub="재고 1~10개"
          color="bg-orange-100"
          icon={
            <svg className="w-6 h-6 text-orange-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
            </svg>
          }
        />
        <SummaryCard
          label="품절"
          value={outOfStock}
          sub="재고 0개"
          color="bg-red-100"
          icon={
            <svg className="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          }
        />
      </div>

      {/* ── 검색 + 필터 ── */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-4">
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="relative flex-1">
            <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400"
              fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="상품명 또는 설명으로 검색..."
              className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
          </div>
          <div className="flex gap-1.5 flex-wrap">
            {FILTERS.map((f) => (
              <button
                key={f.id}
                onClick={() => setFilter(f.id)}
                className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors whitespace-nowrap ${
                  filter === f.id
                    ? 'bg-blue-600 text-white'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {f.label}
                {f.id === 'LOW'  && lowStock > 0   && <span className="ml-1 bg-orange-500 text-white rounded-full px-1.5 text-[10px]">{lowStock}</span>}
                {f.id === 'OUT'  && outOfStock > 0  && <span className="ml-1 bg-red-500 text-white rounded-full px-1.5 text-[10px]">{outOfStock}</span>}
              </button>
            ))}
          </div>
          <button
            onClick={load}
            className="p-2 text-gray-500 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
            title="새로고침"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </button>
        </div>
      </div>

      {/* ── 재고 테이블 ── */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        {sorted.length === 0 ? (
          <div className="py-16 text-center text-gray-400">
            <svg className="mx-auto h-12 w-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
            </svg>
            <p className="text-sm">조건에 맞는 상품이 없습니다.</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 border-b border-gray-200">
                  <th className="text-left px-5 py-3 font-semibold text-gray-600 cursor-pointer select-none"
                    onClick={() => handleSort('name')}>
                    상품 <SortIcon field="name" />
                  </th>
                  <th className="text-center px-4 py-3 font-semibold text-gray-600 cursor-pointer select-none whitespace-nowrap"
                    onClick={() => handleSort('stock')}>
                    현재 재고 <SortIcon field="stock" />
                  </th>
                  <th className="text-center px-4 py-3 font-semibold text-gray-600 cursor-pointer select-none"
                    onClick={() => handleSort('price')}>
                    가격 <SortIcon field="price" />
                  </th>
                  <th className="text-center px-4 py-3 font-semibold text-gray-600 cursor-pointer select-none"
                    onClick={() => handleSort('status')}>
                    상태 <SortIcon field="status" />
                  </th>
                  <th className="text-center px-5 py-3 font-semibold text-gray-600 whitespace-nowrap">
                    재고 조정 (수량 입력 후 입고/출고)
                  </th>
                  <th className="text-center px-4 py-3 font-semibold text-gray-600 whitespace-nowrap">
                    상태 변경
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {sorted.map((product) => {
                  const meta = STATUS_META[product.status];
                  return (
                    <tr key={product.id} className="hover:bg-gray-50 transition-colors">

                      {/* 상품 정보 */}
                      <td className="px-5 py-4">
                        <div className="flex items-center gap-3">
                          {product.primaryImageUrl ? (
                            <img src={product.primaryImageUrl} alt={product.name}
                              className="w-10 h-10 rounded-lg object-cover flex-shrink-0 border border-gray-100" />
                          ) : (
                            <div className="w-10 h-10 rounded-lg bg-gray-100 flex items-center justify-center flex-shrink-0">
                              <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                                  d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
                              </svg>
                            </div>
                          )}
                          <div className="min-w-0">
                            <p className="font-semibold text-gray-900 truncate max-w-[200px]">{product.name}</p>
                            {product.description && (
                              <p className="text-xs text-gray-400 truncate max-w-[200px]">{product.description}</p>
                            )}
                            <p className="text-xs text-gray-400">ID #{product.id}</p>
                          </div>
                        </div>
                      </td>

                      {/* 현재 재고 */}
                      <td className="px-4 py-4 text-center">
                        <span className={`inline-flex items-center justify-center min-w-[56px] px-3 py-1.5 rounded-lg text-base font-bold ${stockColor(product.stockQuantity)}`}>
                          {product.stockQuantity}
                        </span>
                        {product.stockQuantity <= 10 && product.stockQuantity > 0 && (
                          <p className="text-[10px] text-orange-500 mt-1 font-medium">저재고 주의</p>
                        )}
                        {product.stockQuantity === 0 && (
                          <p className="text-[10px] text-red-500 mt-1 font-medium">품절</p>
                        )}
                      </td>

                      {/* 가격 */}
                      <td className="px-4 py-4 text-center">
                        <span className="font-semibold text-gray-900 whitespace-nowrap">{fmt(product.price)}</span>
                      </td>

                      {/* 상태 */}
                      <td className="px-4 py-4 text-center">
                        <span className={`inline-block px-2.5 py-1 rounded-full text-xs font-semibold whitespace-nowrap ${meta.cls}`}>
                          {meta.label}
                        </span>
                      </td>

                      {/* 재고 조정 */}
                      <td className="px-5 py-4">
                        <AdjustPanel product={product} onDone={handleProductUpdated} />
                      </td>

                      {/* 상태 변경 */}
                      <td className="px-4 py-4">
                        <StatusActions product={product} onDone={handleProductUpdated} />
                      </td>

                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* 하단 카운트 */}
        {sorted.length > 0 && (
          <div className="px-5 py-3 border-t border-gray-100 bg-gray-50 text-xs text-gray-500 flex justify-between">
            <span>{sorted.length}개 상품 표시 중 (전체 {total}개)</span>
            <span>정렬: {sortField} {sortAsc ? '▲' : '▼'}</span>
          </div>
        )}
      </div>
    </div>
  );
};

export default InventoryTab;