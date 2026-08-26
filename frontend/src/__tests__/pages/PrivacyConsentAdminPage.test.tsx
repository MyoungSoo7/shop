import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PrivacyConsentAdminPage from '@/pages/system/PrivacyConsentAdminPage';
import {
  adminPrivacyConsentApi,
  privacyConsentApi,
  type AdminOrderPrivacyConsent,
  type PrivacyConsentTerms,
} from '@/api/privacyConsent';

vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
  adminPrivacyConsentApi: { ofUser: vi.fn(), ofTermsVersion: vi.fn() },
}));

const mockedTerms = vi.mocked(privacyConsentApi.terms);
const mockedOfUser = vi.mocked(adminPrivacyConsentApi.ofUser);
const mockedOfTermsVersion = vi.mocked(adminPrivacyConsentApi.ofTermsVersion);

beforeEach(() => {
  vi.clearAllMocks();
  mockedTerms.mockResolvedValue([]);
});

const terms = (over: Partial<PrivacyConsentTerms> = {}): PrivacyConsentTerms => ({
  code: 'THIRD_PARTY_DELIVERY',
  version: 3,
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

const row = (over: Partial<AdminOrderPrivacyConsent> = {}): AdminOrderPrivacyConsent => ({
  orderId: 7,
  userId: 42,
  termsCode: 'THIRD_PARTY_DELIVERY',
  termsVersion: 2,
  consentType: 'THIRD_PARTY_PROVISION',
  agreed: true,
  recipient: '배송업체',
  purpose: '주문 상품의 배송',
  providedItems: '받는 분 이름, 휴대전화번호, 주소',
  retention: '배송 완료 후 90일',
  agreedAt: '2026-08-27T10:00:00',
  ipAddress: '203.0.113.7',
  bodyUnchanged: true,
  ...over,
});

const search = () => fireEvent.click(screen.getByRole('button', { name: '조회' }));

/**
 * 동의 이력 운영 콘솔.
 *
 * <p>이 화면의 값어치는 <b>축이 둘</b>이라는 데 있다. 사람으로 찾는 축은 정보주체의 열람 요구에
 * 답하고, 문안 버전으로 찾는 축은 문안을 고친 뒤 "옛 버전으로 동의한 사람이 남아 있는가"를
 * 센다. 한 축으로 합치면 뒤엣것을 할 수 없으므로, 축 전환이 실제로 다른 질의를 부르는지를 본다.
 *
 * <p>고치는 버튼이 없는 것도 검사 대상이다. 운영자가 동의 이력을 수정할 수 있으면 그 이력은 더
 * 이상 증거가 아니다.
 */
describe('PrivacyConsentAdminPage — 동의 이력 조회', () => {
  it('사용자 ID 가 없으면 조회 버튼이 닫혀 있다 — 조건 없는 전체 조회를 만들지 않는다', async () => {
    render(<PrivacyConsentAdminPage />);

    expect(screen.getByRole('button', { name: '조회' })).toBeDisabled();
    await waitFor(() => expect(mockedTerms).toHaveBeenCalled());
  });

  it('사용자 축은 ofUser 를 부르고 접속지까지 표에 싣는다', async () => {
    mockedOfUser.mockResolvedValue([row()]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();

    await waitFor(() => expect(mockedOfUser).toHaveBeenCalledWith(42, 100));
    expect(await screen.findByText('#7')).toBeInTheDocument();
    expect(screen.getByText('#42')).toBeInTheDocument();
    expect(screen.getByText('203.0.113.7')).toBeInTheDocument();
  });

  it('문안 축은 코드와 버전으로 다른 질의를 부른다 — 두 축을 합치지 않는 이유다', async () => {
    mockedOfTermsVersion.mockResolvedValue([]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.click(screen.getByRole('tab', { name: /문안 버전별/ }));
    fireEvent.change(screen.getByLabelText('문안 코드'), { target: { value: 'THIRD_PARTY_DELIVERY' } });
    fireEvent.change(screen.getByLabelText('버전'), { target: { value: '1' } });
    search();

    await waitFor(() =>
      expect(mockedOfTermsVersion).toHaveBeenCalledWith('THIRD_PARTY_DELIVERY', 1, 100),
    );
    expect(mockedOfUser).not.toHaveBeenCalled();
  });

  it('현행 문안 칩을 누르면 코드와 버전이 채워진다 — 찾는 것은 대개 현행이 아닌 버전이다', async () => {
    mockedTerms.mockResolvedValue([terms()]);
    render(<PrivacyConsentAdminPage />);
    fireEvent.click(screen.getByRole('tab', { name: /문안 버전별/ }));

    const chip = await screen.findByRole('button', { name: /THIRD_PARTY_DELIVERY/ });
    fireEvent.click(chip);

    expect(screen.getByLabelText('문안 코드')).toHaveValue('THIRD_PARTY_DELIVERY');
    expect(screen.getByLabelText('버전')).toHaveValue(3);
  });

  it('축을 바꾸면 앞선 결과를 지운다 — 다른 질문의 답이 남아 있으면 오독한다', async () => {
    mockedOfUser.mockResolvedValue([row()]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();
    expect(await screen.findByText('#7')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: /문안 버전별/ }));

    expect(screen.queryByText('#7')).not.toBeInTheDocument();
  });

  it('버전을 올리지 않고 문장을 고친 행은 표시가 붙는다', async () => {
    mockedOfUser.mockResolvedValue([row({ bodyUnchanged: false })]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();

    expect(await screen.findByText('문안 변경됨')).toBeInTheDocument();
  });

  it('결과가 없으면 "기록이 없다"로 적는다 — "동의하지 않았다"가 아니다', async () => {
    mockedOfUser.mockResolvedValue([]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();

    expect(await screen.findByText('조건에 맞는 동의 기록이 없습니다.')).toBeInTheDocument();
  });

  it('조회에 실패하면 표를 지우고 이유를 말한다 — 빈 표가 "0건"으로 읽히지 않도록', async () => {
    mockedOfUser
      .mockResolvedValueOnce([row()])
      .mockRejectedValueOnce({ response: { status: 500, data: { message: '조회 실패' } } });
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();
    expect(await screen.findByText('#7')).toBeInTheDocument();

    search();

    expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패');
    expect(screen.queryByText('#7')).not.toBeInTheDocument();
  });

  it('현행 문안을 못 받아도 조회는 막지 않는다 — 힌트일 뿐이다', async () => {
    mockedTerms.mockRejectedValue(new Error('boom'));
    mockedOfUser.mockResolvedValue([row()]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();

    expect(await screen.findByText('#7')).toBeInTheDocument();
  });

  it('고치는 버튼이 없다 — 운영자가 고칠 수 있는 이력은 증거가 아니다', async () => {
    mockedOfUser.mockResolvedValue([row()]);
    render(<PrivacyConsentAdminPage />);

    fireEvent.change(screen.getByLabelText('사용자 ID'), { target: { value: '42' } });
    search();
    await screen.findByText('#7');

    const labels = screen.getAllByRole('button').map((b) => b.textContent ?? '');
    expect(labels.some((t) => /수정|삭제|저장|변경하기/.test(t))).toBe(false);
  });
});
