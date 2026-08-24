import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusBadge from '@/components/StatusBadge';

describe('StatusBadge', () => {
  it('정산 상태를 기본 타입으로 한글 라벨로 그린다', () => {
    render(<StatusBadge status="DONE" />);

    expect(screen.getByText('완료')).toBeInTheDocument();
    expect(screen.getByText('완료').className).toContain('bg-green-100');
  });

  it.each([
    ['REQUESTED', '요청됨'],
    ['PROCESSING', '처리중'],
    ['FAILED', '실패'],
    ['CANCELED', '취소됨'],
  ])('정산 상태 %s → %s', (status, label) => {
    render(<StatusBadge status={status} />);

    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it.each([
    ['PENDING', '대기'],
    ['PAID', '결제완료'],
    ['CAPTURED', '승인완료'],
    ['PARTIAL_REFUNDED', '부분환불'],
    ['REFUNDED', '환불완료'],
  ])('결제 상태 %s → %s', (status, label) => {
    render(<StatusBadge status={status} type="payment" />);

    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it.each([
    ['PENDING', '대기'],
    ['CONFIRMED', '확정'],
    ['COMPLETED', '완료'],
    ['CANCELED', '취소'],
  ])('주문 상태 %s → %s', (status, label) => {
    render(<StatusBadge status={status} type="order" />);

    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it('모르는 상태코드는 원문을 그대로 보여 준다 (숨기지 않는다)', () => {
    render(<StatusBadge status="BRAND_NEW_STATE" />);

    expect(screen.getByText('BRAND_NEW_STATE')).toBeInTheDocument();
  });

  it('결제·주문 타입의 모르는 코드도 원문을 노출한다', () => {
    render(<StatusBadge status="WEIRD" type="payment" />);
    expect(screen.getByText('WEIRD')).toBeInTheDocument();
  });
});
