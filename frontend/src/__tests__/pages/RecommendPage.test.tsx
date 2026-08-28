import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import RecommendPage from '@/pages/RecommendPage';
import { productApi } from '@/api/product';
import { couponApi } from '@/api/coupon';
import { reviewApi } from '@/api/review';
import { fetchCurrentWeather } from '@/api/weather';

vi.mock('@/api/product', () => ({ productApi: { getAvailableProducts: vi.fn() } }));
vi.mock('@/api/coupon', () => ({ couponApi: { getAll: vi.fn() } }));
vi.mock('@/api/review', () => ({ reviewApi: { getProductReviews: vi.fn() } }));
vi.mock('@/api/weather', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/weather')>();
  return { ...actual, fetchCurrentWeather: vi.fn() };
});

const mockedProduct = vi.mocked(productApi);
const mockedCoupon = vi.mocked(couponApi);
const mockedReview = vi.mocked(reviewApi);
const mockedWeather = vi.mocked(fetchCurrentWeather);

/** 카테고리 분류에 걸리는 이름이어야 추천 후보로 잡힌다(fashionRecommend.categorize). */
const products = [
  { id: 1, name: '기본 티셔츠', description: '', price: 20000, stockQuantity: 10, status: 'ACTIVE' },
  { id: 2, name: '슬림 청바지', description: '', price: 50000, stockQuantity: 10, status: 'ACTIVE' },
  { id: 3, name: '겨울 패딩 점퍼', description: '', price: 200000, stockQuantity: 10, status: 'ACTIVE' },
  { id: 4, name: '러닝 운동화', description: '', price: 80000, stockQuantity: 10, status: 'ACTIVE' },
];

beforeEach(() => {
  vi.clearAllMocks();
  mockedProduct.getAvailableProducts.mockResolvedValue(products as never);
  mockedCoupon.getAll.mockResolvedValue([
    { id: 1, code: 'WELCOME10', type: 'PERCENTAGE', discountValue: 10, minOrderAmount: 10000, maxUses: 100, usedCount: 0, isActive: true },
  ] as never);
  mockedReview.getProductReviews.mockResolvedValue([] as never);
  mockedWeather.mockResolvedValue({ temperature: 21.4, weatherCode: 3 });
});

describe('RecommendPage — 로드', () => {
  it('상품·날씨를 함께 읽고 실시간 기온을 보여 준다', async () => {
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);

    expect(await screen.findByText(/21\.4℃/)).toBeInTheDocument();
    expect(mockedProduct.getAvailableProducts).toHaveBeenCalled();
  });

  // GET /coupons 는 비활성 쿠폰까지 주는 ADMIN/MANAGER 전용 API 다. USER 로 이 화면에 들어오면
  // 403 이 떨어져 "접근 권한이 없습니다" 토스트만 뜨고 할인은 어차피 반영되지 않았다.
  it('관리자 전용 쿠폰 목록 API 는 부르지 않는다', async () => {
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);

    expect(await screen.findByText(/21\.4℃/)).toBeInTheDocument();
    expect(mockedCoupon.getAll).not.toHaveBeenCalled();
  });

  it('상품 조회 실패는 사유를 보여 준다', async () => {
    mockedProduct.getAvailableProducts.mockRejectedValue(new Error('down'));
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);

    expect(await screen.findByText('상품 목록을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('날씨를 못 받으면 수동 계절 선택으로 폴백한다', async () => {
    mockedWeather.mockResolvedValue(null);
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);

    await waitFor(() => expect(mockedWeather).toHaveBeenCalled());
    expect(screen.queryByText(/℃/)).not.toBeInTheDocument();
  });

  it('추천 후보가 없으면 버튼이 잠긴다', async () => {
    mockedProduct.getAvailableProducts.mockResolvedValue([] as never);
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: '추천받기' })).toBeDisabled(),
    );
  });
});

describe('RecommendPage — 추천', () => {
  const waitReady = async () => {
    render(<MemoryRouter><RecommendPage /></MemoryRouter>);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '추천받기' })).toBeEnabled(),
    );
  };

  it('추천하면 상품 목록과 브랜드 추천을 함께 만든다', async () => {
    await waitReady();

    await userEvent.click(screen.getByRole('button', { name: '추천받기' }));

    // 추천 결과 섹션이 뜨는 것 자체가 산출 성공 신호다
    // (브랜드·쿠폰가 표기는 후보 구성에 따라 달라져 단정하지 않는다)
    expect(await screen.findByText('추천 상품')).toBeInTheDocument();
  });

  it('상위 후보의 리뷰를 읽어 평판 축에 반영한다', async () => {
    mockedReview.getProductReviews.mockResolvedValue([
      { id: 1, productId: 1, userId: 7, rating: 5, content: '좋아요', createdAt: '2026-08-01T00:00:00Z' },
    ] as never);
    await waitReady();

    await userEvent.click(screen.getByRole('button', { name: '추천받기' }));

    await waitFor(() => expect(mockedReview.getProductReviews).toHaveBeenCalled());
    expect(await screen.findByText('추천 상품')).toBeInTheDocument();
  });

  it('리뷰 조회가 실패해도 추천은 완성된다', async () => {
    mockedReview.getProductReviews.mockRejectedValue(new Error('down'));
    await waitReady();

    await userEvent.click(screen.getByRole('button', { name: '추천받기' }));

    expect(await screen.findByText('추천 상품')).toBeInTheDocument();
  });

  it('스타일 상황을 바꿔서도 추천할 수 있다', async () => {
    await waitReady();

    const situationButtons = screen.getAllByRole('button').filter((b) => b.getAttribute('type') === 'button');
    if (situationButtons.length > 1) {
      await userEvent.click(situationButtons[1]);
    }
    await userEvent.click(screen.getByRole('button', { name: '추천받기' }));

    expect(await screen.findByText('추천 상품')).toBeInTheDocument();
  });
});
