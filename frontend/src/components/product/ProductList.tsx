import React, { useState, useEffect } from 'react';
import { ProductResponse, ProductStatus } from '@/types';
import { productApi } from '@/api/product';
import { apiErrorMessage } from '@/lib/apiError';

interface ProductListProps {
  onProductSelect?: (product: ProductResponse) => void;
  refreshTrigger?: number;
}

const ProductList: React.FC<ProductListProps> = ({ onProductSelect, refreshTrigger = 0 }) => {
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [filteredProducts, setFilteredProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<ProductStatus | 'ALL'>('ALL');
  const [searchTerm, setSearchTerm] = useState('');

  const fetchProducts = async () => {
    setLoading(true);
    setError('');

    try {
      const data = await productApi.getAllProducts();
      setProducts(data);
      setFilteredProducts(data);
    } catch (err) {
      const message = apiErrorMessage(err, '상품 목록을 불러오는데 실패했습니다.');
      setError(message);
      console.error('상품 목록 조회 실패:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchProducts();
  }, [refreshTrigger]);

  useEffect(() => {
    let result = [...products];

    // 상태 필터링
    if (statusFilter !== 'ALL') {
      result = result.filter(product => product.status === statusFilter);
    }

    // 검색어 필터링
    if (searchTerm.trim()) {
      const term = searchTerm.toLowerCase();
      result = result.filter(product =>
        product.name.toLowerCase().includes(term) ||
        product.description?.toLowerCase().includes(term)
      );
    }

    setFilteredProducts(result);
  }, [products, statusFilter, searchTerm]);

  const getStatusBadgeClass = (status: ProductStatus): string => {
    switch (status) {
      case 'ACTIVE':
        return 'bg-green-100 text-green-800 border-green-200';
      case 'INACTIVE':
        return 'bg-gray-100 text-gray-800 border-gray-200';
      case 'OUT_OF_STOCK':
        return 'bg-red-100 text-red-800 border-red-200';
      case 'DISCONTINUED':
        return 'bg-purple-100 text-purple-800 border-purple-200';
      default:
        return 'bg-gray-100 text-gray-800 border-gray-200';
    }
  };

  const getStatusLabel = (status: ProductStatus): string => {
    switch (status) {
      case 'ACTIVE':
        return '판매중';
      case 'INACTIVE':
        return '판매중지';
      case 'OUT_OF_STOCK':
        return '품절';
      case 'DISCONTINUED':
        return '단종';
      default:
        return status;
    }
  };

  const formatPrice = (price: number): string => {
    return new Intl.NumberFormat('ko-KR', {
      style: 'currency',
      currency: 'KRW',
    }).format(price);
  };

  const formatDate = (dateString: string): string => {
    return new Date(dateString).toLocaleString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center py-12">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-6 bg-red-50 border border-red-200 rounded-lg">
        <p className="text-red-800">{error}</p>
        <button
          onClick={fetchProducts}
          className="mt-4 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700"
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 필터 및 검색 */}
      <div className="bg-white p-4 rounded-lg shadow">
        <div className="flex flex-col md:flex-row gap-4">
          {/* 검색 */}
          <div className="flex-1">
            <input
              type="text"
              placeholder="상품명 또는 설명으로 검색..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>

          {/* 상태 필터 */}
          <div className="md:w-48">
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as ProductStatus | 'ALL')}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            >
              <option value="ALL">전체 상태</option>
              <option value="ACTIVE">판매중</option>
              <option value="INACTIVE">판매중지</option>
              <option value="OUT_OF_STOCK">품절</option>
              <option value="DISCONTINUED">단종</option>
            </select>
          </div>
        </div>

        {/* 결과 요약 */}
        <div className="mt-4 text-sm text-gray-600">
          전체 {products.length}개 상품 중 {filteredProducts.length}개 표시
        </div>
      </div>

      {/* 상품 목록 */}
      {filteredProducts.length === 0 ? (
        <div className="bg-white p-12 rounded-lg shadow text-center text-gray-500">
          {searchTerm || statusFilter !== 'ALL'
            ? '검색 조건에 맞는 상품이 없습니다.'
            : '등록된 상품이 없습니다.'}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {filteredProducts.map((product) => (
            <div
              key={product.id}
              className="bg-white rounded-lg shadow hover:shadow-lg transition-shadow duration-200 overflow-hidden cursor-pointer"
              onClick={() => onProductSelect?.(product)}
            >
              {/* 상품 이미지 */}
              {product.primaryImageUrl ? (
                <div className="w-full h-48 bg-gray-100">
                  <img
                    src={product.primaryImageUrl}
                    alt={product.name}
                    loading="lazy"
                    decoding="async"
                    className="w-full h-full object-cover"
                  />
                </div>
              ) : (
                <div className="w-full h-48 bg-gray-100 flex items-center justify-center">
                  <svg
                    className="w-16 h-16 text-gray-300"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={1}
                      d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
                    />
                  </svg>
                </div>
              )}

              <div className="p-6">
                {/* 상품명과 상태 */}
                <div className="flex justify-between items-start mb-3">
                  <h3 className="text-lg font-semibold text-gray-900 flex-1 mr-2">
                    {product.name}
                  </h3>
                  <span
                    className={`px-2 py-1 text-xs font-semibold rounded-full border ${getStatusBadgeClass(
                      product.status
                    )}`}
                  >
                    {getStatusLabel(product.status)}
                  </span>
                </div>

                {/* 설명 */}
                {product.description && (
                  <p className="text-sm text-gray-600 mb-4 line-clamp-2">
                    {product.description}
                  </p>
                )}

                {/* 가격과 재고 */}
                <div className="space-y-2 mb-4">
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-500">가격</span>
                    <span className="text-lg font-bold text-blue-600">
                      {formatPrice(product.price)}
                    </span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-500">재고</span>
                    <span
                      className={`text-sm font-semibold ${
                        product.stockQuantity === 0
                          ? 'text-red-600'
                          : product.stockQuantity < 10
                          ? 'text-orange-600'
                          : 'text-gray-900'
                      }`}
                    >
                      {product.stockQuantity}개
                    </span>
                  </div>
                </div>

                {/* 판매 가능 여부 */}
                <div className="mb-4">
                  {product.availableForSale ? (
                    <span className="text-xs text-green-600 font-semibold">
                      ✓ 판매 가능
                    </span>
                  ) : (
                    <span className="text-xs text-gray-500">
                      ✗ 판매 불가
                    </span>
                  )}
                </div>

                {/* 등록일 */}
                <div className="pt-4 border-t border-gray-100">
                  <p className="text-xs text-gray-500">
                    등록일: {formatDate(product.createdAt)}
                  </p>
                  {product.updatedAt !== product.createdAt && (
                    <p className="text-xs text-gray-500">
                      수정일: {formatDate(product.updatedAt)}
                    </p>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ProductList;
