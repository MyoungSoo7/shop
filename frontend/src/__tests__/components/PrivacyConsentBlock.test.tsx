import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PrivacyConsentBlock from '@/components/consent/PrivacyConsentBlock';
import type { PrivacyConsentTerms } from '@/api/privacyConsent';

const terms = (over: Partial<PrivacyConsentTerms> = {}): PrivacyConsentTerms => ({
  code: 'THIRD_PARTY_DELIVERY',
  version: 2,
  consentType: 'THIRD_PARTY_PROVISION',
  title: '배송을 위한 개인정보 제3자 제공 동의',
  recipient: '배송업체',
  purpose: '주문 상품의 배송',
  providedItems: '받는 분 이름, 휴대전화번호, 주소',
  retention: '배송 완료 후 90일',
  body: '전문입니다',
  required: true,
  effectiveFrom: '2026-07-28T00:00:00',
  ...over,
});

/**
 * 결제 화면의 동의 구획.
 *
 * <p>체크박스가 있는지가 아니라 <b>무엇에 동의하는지가 화면에 있는지</b>를 본다. 개인정보 보호법
 * 제17조가 알리라고 하는 넷(제공받는 자·목적·항목·보유 기간)이 빠지면, 체크를 받아 둬도 그것은
 * 동의로 성립하지 않는다. 그리고 선택 항목이 필수처럼 보이면 제22조가 막으려는 바로 그 형태가
 * 된다 — 그래서 라벨의 [필수]/[선택] 표시도 함께 고정한다.
 */
describe('PrivacyConsentBlock — 무엇에 동의하는지가 화면에 있다', () => {
  it('필수와 선택을 라벨로 갈라 보여 준다', () => {
    render(
      <PrivacyConsentBlock
        terms={[terms(), terms({ code: 'MARKETING_MESSAGE', title: '광고성 정보 수신 동의', required: false })]}
        agreed={{}}
        onToggle={vi.fn()}
      />,
    );

    expect(screen.getByText('[필수]')).toBeInTheDocument();
    expect(screen.getByText('[선택]')).toBeInTheDocument();
    expect(screen.getByText(/배송을 위한 개인정보 제3자 제공 동의/)).toBeInTheDocument();
  });

  it('접혀 있는 동안에는 전문이 없고, 펼치면 제17조의 넷과 전문이 함께 나온다', () => {
    render(<PrivacyConsentBlock terms={[terms()]} agreed={{}} onToggle={vi.fn()} />);

    expect(screen.queryByText('전문입니다')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('자세히'));

    expect(screen.getByText('제공받는 자')).toBeInTheDocument();
    expect(screen.getByText('배송업체')).toBeInTheDocument();
    expect(screen.getByText('주문 상품의 배송')).toBeInTheDocument();
    expect(screen.getByText('받는 분 이름, 휴대전화번호, 주소')).toBeInTheDocument();
    expect(screen.getByText('배송 완료 후 90일')).toBeInTheDocument();
    expect(screen.getByText('전문입니다')).toBeInTheDocument();
  });

  it('제3자 제공이 아닌 문안에는 "제공받는 자" 줄이 없다 — 빈 칸을 만들지 않는다', () => {
    render(
      <PrivacyConsentBlock
        terms={[terms({ code: 'COLLECTION_USE_ORDER', consentType: 'COLLECTION_USE', recipient: null })]}
        agreed={{}}
        onToggle={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('자세히'));

    expect(screen.queryByText('제공받는 자')).not.toBeInTheDocument();
    expect(screen.getByText('목적')).toBeInTheDocument();
  });

  it('체크하면 코드와 다음 상태를 그대로 올린다 — 상태는 훅이 들고 있다', () => {
    const onToggle = vi.fn();
    render(<PrivacyConsentBlock terms={[terms()]} agreed={{}} onToggle={onToggle} />);

    fireEvent.click(screen.getByLabelText(/배송을 위한 개인정보 제3자 제공 동의/));

    expect(onToggle).toHaveBeenCalledWith('THIRD_PARTY_DELIVERY', true);
  });

  it('없는 키는 "체크 안 함"이다', () => {
    render(<PrivacyConsentBlock terms={[terms()]} agreed={{}} onToggle={vi.fn()} />);

    expect(screen.getByRole('checkbox')).not.toBeChecked();
  });

  it('disabled 면 체크할 수 없다 — 주문 전송 중 상태가 바뀌면 보낸 것과 화면이 어긋난다', () => {
    render(<PrivacyConsentBlock terms={[terms()]} agreed={{}} onToggle={vi.fn()} disabled />);

    expect(screen.getByRole('checkbox')).toBeDisabled();
  });

  it('문안을 못 받아 왔으면 체크박스 대신 이유를 보여 준다 — 빈 화면이 "동의 항목 없음"으로 읽히지 않도록', () => {
    render(
      <PrivacyConsentBlock terms={[]} agreed={{}} onToggle={vi.fn()} error="동의 문안을 불러오지 못했습니다." />,
    );

    expect(screen.getByText('동의 문안을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('불러오는 중에도 구획 제목은 남는다 — 자리가 통째로 사라지면 없는 화면으로 보인다', () => {
    render(<PrivacyConsentBlock terms={[]} agreed={{}} onToggle={vi.fn()} loading />);

    expect(screen.getByText('개인정보 수집·제공 동의')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('거부할 수 있다는 사실과 그 결과를 함께 적는다 — 결과를 안 적으면 고지가 아니다', () => {
    render(<PrivacyConsentBlock terms={[terms()]} agreed={{}} onToggle={vi.fn()} />);

    expect(screen.getByText(/동의하지 않으면 주문할 수 없습니다/)).toBeInTheDocument();
    expect(screen.getByText(/선택 항목은 동의하지 않아도 주문에는 영향이 없습니다/)).toBeInTheDocument();
  });
});
