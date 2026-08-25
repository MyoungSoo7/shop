import api from './axios';

/**
 * 판매 통계 — order-service {@code AdminSalesStatsController}.
 *
 * <p><b>매출({@code /admin/revenue})과 무엇이 다른가.</b> 저쪽은 결제 원장을 보고 "얼마가
 * 들어왔나"를 답한다. 여기는 주문 라인을 보고 "<b>무엇이</b> 팔렸나"를 답한다. 두 숫자는 일부러
 * 다르다 — 부분 환불, 결제수단별 수납 시차, 미결제 주문이 각각 한쪽에만 반영된다. 그래서 이
 * 화면의 순매출을 회계 숫자로 쓰면 안 되고, 화면이 그렇게 말해 준다.
 *
 * <p><b>랭킹은 본래 잘라낸 목록이다.</b> 상위 N 만 온다. 담긴 행의 합계를 "전체 매출"로 읽는
 * 오해가 이 화면의 유일한 위험이라, 서버가 <b>전 범위 합계</b>({@code total})를 따로 보내 준다 —
 * 화면은 반드시 둘을 같이 보여야 한다.
 *
 * <p>카테고리 분포는 반대로 <b>자르지 않는다</b>. 대표 분류가 없는 상품은 사라지지 않고
 * {@code categoryId === null} 인 '미분류' 한 줄로 나온다. 빼 버리면 분포의 합이 총액에 못 미치는
 * 것을 볼 사람이 없다.
 */

export interface SalesTotal {
  quantity: number;
  netAmount: number;
  /** 집계에 들어간 주문 라인 수. */
  lineCount: number;
  orderCount: number;
}

export interface ProductSales {
  productId: number;
  productName: string;
  quantity: number;
  netAmount: number;
  orderCount: number;
}

export interface ProductRanking {
  from: string;
  to: string;
  /** 실제로 집계에 쓴 주문 상태 — 요청과 다를 수 있다(서버가 정본). */
  statuses: string[];
  /** 상위 몇 개까지 담았는가. 전체 상품 수가 아니다 — 서버에 그 값이 없다. */
  limit: number;
  rows: ProductSales[];
  /** <b>담긴 행이 아니라 전 범위</b>의 합계. 행 합계와 다른 것이 정상이다. */
  total: SalesTotal;
}

export interface CategorySales {
  /** null 이면 대표 분류가 없는 상품 묶음 — 화면은 '미분류'로 그린다. */
  categoryId: number | null;
  categoryName: string | null;
  pathSlug: string | null;
  depth: number | null;
  quantity: number;
  netAmount: number;
  orderCount: number;
}

export interface CategoryBreakdown {
  from: string;
  to: string;
  statuses: string[];
  rows: CategorySales[];
  total: SalesTotal;
}

export interface SalesQuery {
  from?: string;
  to?: string;
  /** 비우면 서버가 "결제가 살아 있는 상태"를 쓴다. */
  statuses?: string[];
  /** 랭킹 전용. 카테고리 분포는 자르지 않는다. */
  limit?: number;
}

export interface SalesExportResult {
  blob: Blob;
  fileName: string;
  /** 랭킹 CSV 는 늘 잘려 있다 — 받은 사람이 그 사실을 알아야 한다. */
  truncated: boolean;
  /** 담은 상한(전체 건수가 아니다). 카테고리 CSV 에는 없다. */
  limit: number | null;
  /** 전 범위 순매출 — 파일 안의 행 합계와 비교하라고 서버가 함께 보낸다. */
  netAmount: number | null;
  /** 파일이 담은 기간. 파일명에는 만든 날짜만 들어가 구분이 안 된다. */
  range: string | null;
}

/** yyyy-MM-dd — 서버는 ISO DATE 만 받는다(시각을 붙이면 400). */
export const toIsoDate = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/**
 * 쿼리 파라미터. 상태 목록은 <b>쉼표로 이어</b> 보낸다 — axios 기본 직렬화는 배열을
 * {@code statuses[]=A} 로 펴는데 스프링의 {@code List<String> statuses} 는 그 이름을 못 읽어
 * 조용히 기본 상태 집합으로 되돌아간다. 필터가 안 걸린 것을 화면에서는 알아챌 수 없다.
 */
const params = (query: SalesQuery): Record<string, string | number> => {
  const out: Record<string, string | number> = {};
  if (query.from) out.from = query.from;
  if (query.to) out.to = query.to;
  if (query.statuses && query.statuses.length > 0) out.statuses = query.statuses.join(',');
  if (query.limit !== undefined) out.limit = query.limit;
  return out;
};

const toExportResult = (
  response: { data: Blob; headers: Record<string, unknown> },
  fallbackName: string,
): SalesExportResult => {
  const disposition = String(response.headers['content-disposition'] ?? '');
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  const num = (header: string): number | null => {
    const raw = response.headers[header];
    return raw === undefined || raw === null ? null : Number(raw);
  };

  return {
    blob: response.data,
    fileName: match ? decodeURIComponent(match[1]) : fallbackName,
    truncated: String(response.headers['x-export-truncated']) === 'true',
    limit: num('x-export-limit'),
    netAmount: num('x-export-net-amount'),
    range: response.headers['x-export-range'] === undefined
      ? null
      : String(response.headers['x-export-range']),
  };
};

export const salesStatsApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  topProducts: async (query: SalesQuery): Promise<ProductRanking> =>
    (await api.get<ProductRanking>('/admin/sales/products', { params: params(query) })).data,

  byCategory: async (query: SalesQuery): Promise<CategoryBreakdown> =>
    (await api.get<CategoryBreakdown>('/admin/sales/categories', { params: params(query) })).data,

  exportProducts: async (query: SalesQuery): Promise<SalesExportResult> =>
    toExportResult(
      await api.get<Blob>('/admin/sales/products/export', {
        params: params(query),
        responseType: 'blob',
      }),
      'sales_products.csv',
    ),

  exportCategories: async (query: SalesQuery): Promise<SalesExportResult> =>
    toExportResult(
      await api.get<Blob>('/admin/sales/categories/export', {
        params: params({ ...query, limit: undefined }),
        responseType: 'blob',
      }),
      'sales_categories.csv',
    ),
};
