import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import ShippingPolicyAdminPage from '@/pages/ShippingPolicyAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { shippingPolicyApi, describeThreshold } from '@/api/shippingPolicy';

/**
 * 이 화면이 지켜야 하는 규율은 하나로 요약된다: <b>세 가지 상태를 뭉개지 않는다</b>.
 *   ① 정책 없음(행 자체가 없음) = 기본배송비 0 원
 *   ② 임계 없음(null) = 항상 부과
 *   ③ 임계 0 = 항상 무료  ← ②와 정반대
 * 폼이 ②와 ③을 같은 빈 칸으로 표현하면 운영자는 반대 뜻을 저장하게 되고, 그 결과는
 * 고객이 지불하는 금액이 바뀌는 것이다. 그래서 저장 payload 까지 테스트로 못박는다.
 */
vi.mock('@/api/shippingPolicy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/shippingPolicy')>();
  return {
    ...actual,
    shippingPolicyApi: { list: vi.fn(), get: vi.fn(), upsert: vi.fn() },
  };
});

const mocked = vi.mocked(shippingPolicyApi);

const renderPage = () => render(<ToastProvider><ShippingPolicyAdminPage /></ToastProvider>);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.list.mockResolvedValue([
    { sellerId: 7, baseFee: '3000', freeThreshold: '50000' },
    { sellerId: 8, baseFee: '2500', freeThreshold: null },
    { sellerId: 9, baseFee: '4000', freeThreshold: '0' },
  ]);
  mocked.upsert.mockResolvedValue({ sellerId: 77, baseFee: '3000', freeThreshold: null });
});

describe('describeThreshold — null 과 0 은 반대 뜻이다', () => {
  it('null 은 무료배송 없음, 0 은 항상 무료로 읽힌다', () => {
    expect(describeThreshold(null)).toBe('무료배송 없음');
    expect(describeThreshold('0')).toBe('항상 무료');
    expect(describeThreshold('50000')).toBe('50,000원 이상 무료');
  });
});

describe('ShippingPolicyAdminPage', () => {
  it('정책 목록을 셀러별로 그리고, 임계의 세 상태를 다른 문장으로 보여 준다', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('50,000원 이상 무료')).toBeInTheDocument());
    expect(screen.getByText('무료배송 없음')).toBeInTheDocument();
    expect(screen.getByText('항상 무료')).toBeInTheDocument();
    expect(screen.getByText('3,000원')).toBeInTheDocument();
  });

  it('정책이 하나도 없으면 "모든 셀러 0원" 이라고 알린다 — 빈 목록은 무해가 아니다', async () => {
    mocked.list.mockResolvedValue([]);
    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/모든 셀러의 기본배송비가 0원/)).toBeInTheDocument());
  });

  it('임계를 입력해 저장하면 그 값이 문자열로 전달된다', async () => {
    renderPage();

    fireEvent.change(await screen.findByLabelText('셀러 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('기본배송비'), { target: { value: '3000' } });
    fireEvent.change(screen.getByLabelText('무료배송 임계'), { target: { value: '50000' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mocked.upsert).toHaveBeenCalledWith(77, { baseFee: '3000', freeThreshold: '50000' }));
  });

  it('"무료배송 조건 없음" 을 고르면 임계는 0 이 아니라 null 로 전달된다', async () => {
    renderPage();

    fireEvent.change(await screen.findByLabelText('셀러 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('기본배송비'), { target: { value: '3000' } });
    fireEvent.click(screen.getByLabelText(/무료배송 조건 없음/));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mocked.upsert).toHaveBeenCalledWith(77, { baseFee: '3000', freeThreshold: null }));
  });

  it('임계를 비운 채로는 저장 버튼이 열리지 않는다 — 빈 칸을 null 로 넘겨짚지 않는다', async () => {
    renderPage();

    fireEvent.change(await screen.findByLabelText('셀러 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('기본배송비'), { target: { value: '3000' } });

    expect(await screen.findByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('변경을 누르면 기존 값이 폼에 실리고 셀러 ID 는 잠긴다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '변경' })).toHaveLength(3));

    fireEvent.click(screen.getAllByRole('button', { name: '변경' })[1]); // sellerId=8, 임계 null

    expect(screen.getByLabelText('셀러 ID')).toBeDisabled();
    expect(screen.getByLabelText('기본배송비')).toHaveValue('2500');
    expect(screen.getByLabelText(/무료배송 조건 없음/)).toBeChecked();
    expect(screen.getByText('셀러 8 정책 변경')).toBeInTheDocument();
  });

  it('저장에 실패해도 화면은 살아 있고 목록을 다시 읽지 않는다', async () => {
    mocked.upsert.mockRejectedValue(new Error('boom'));
    renderPage();

    fireEvent.change(await screen.findByLabelText('셀러 ID'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('기본배송비'), { target: { value: '3000' } });
    fireEvent.click(screen.getByLabelText(/무료배송 조건 없음/));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mocked.upsert).toHaveBeenCalled());
    expect(mocked.list).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('button', { name: '저장' })).toBeInTheDocument();
  });

  it('목록 조회가 실패하면 원인을 드러낸다 — 빈 화면으로 위장하지 않는다', async () => {
    // errorDetail 은 원인 메시지가 있으면 그것을 쓰고, 없을 때만 폴백 문구로 내려간다.
    // 여기서 확인하는 것은 "정책 0 건"과 "조회 실패"가 화면에서 구분된다는 사실이다 —
    // 둘이 같아 보이면 운영자는 정책이 지워진 줄 알고 다시 등록하게 된다.
    mocked.list.mockRejectedValue(new Error('boom'));
    renderPage();

    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
    expect(screen.queryByText(/모든 셀러의 기본배송비가 0원/)).not.toBeInTheDocument();
  });
});
