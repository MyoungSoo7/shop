import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrganizationConsolePage from '@/pages/system/OrganizationConsolePage';
import { organizationApi, LastOwnerError, type Organization } from '@/api/organization';

vi.mock('@/api/organization', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/organization')>();
  return {
    ...actual,
    organizationApi: {
      detail: vi.fn(), create: vi.fn(), invite: vi.fn(),
      acceptOwnInvite: vi.fn(), changeRole: vi.fn(), remove: vi.fn(),
    },
  };
});

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

let currentUserId: number | null = 100;
vi.mock('@/contexts/useAuth', () => ({
  useAuth: () => ({ user: null, userId: currentUserId, loading: false, refresh: vi.fn() }),
}));

const mocked = vi.mocked(organizationApi);

const org = (members: Organization['members']): Organization => ({
  id: 3, name: '가나상사', type: 'SELLER', externalRef: '123-45-67890', status: 'ACTIVE', members,
});

const owner = { userId: 11, role: 'OWNER' as const, status: 'ACTIVE' as const, invitedBy: null };
const staff = { userId: 12, role: 'STAFF' as const, status: 'ACTIVE' as const, invitedBy: 11 };
const myInvite = { userId: 100, role: 'MANAGER' as const, status: 'INVITED' as const, invitedBy: 11 };
const othersInvite = { userId: 13, role: 'STAFF' as const, status: 'INVITED' as const, invitedBy: 11 };

const lookup = (id = '3') => {
  fireEvent.change(screen.getByLabelText('조직 번호'), { target: { value: id } });
  fireEvent.click(screen.getByRole('button', { name: '조직 조회' }));
};

beforeEach(() => {
  vi.clearAllMocks();
  currentUserId = 100;
  vi.stubGlobal('confirm', vi.fn(() => true));
});
afterEach(() => vi.unstubAllGlobals());

/**
 * 이 콘솔이 지키는 규율.
 *
 * <p>① <b>수락 버튼은 본인 행에만</b> 있다. 서버의 accept 는 호출자 자신의 초대만 수락하므로,
 * 남의 행에 달면 관리자가 "승인"으로 착각해 자기 멤버십을 만들어 버린다.
 * <p>② <b>마지막 활성 OWNER 는 강등·제거를 막는다</b>. 서버도 422 로 막지만 화면이 미리 막아
 * 왕복을 줄이고, 막히는 이유(OWNER 수)를 늘 보여 준다.
 * <p>③ 422 는 실패 문구가 아니라 <b>서버가 준 불변식 설명</b>을 그대로 쓴다.
 */
describe('OrganizationConsolePage — 수락 버튼의 주인', () => {
  it('내 초대 행에만 수락 버튼이 있다', async () => {
    mocked.detail.mockResolvedValue(org([owner, myInvite, othersInvite]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('member-100')).toBeInTheDocument());

    // 내 행(100)에는 있고, 남의 초대 행(13)에는 없다.
    expect(screen.getByTestId('member-100').querySelector('[data-testid="accept-own"]')).not.toBeNull();
    expect(screen.getByTestId('member-13').querySelector('[data-testid="accept-own"]')).toBeNull();
  });

  it('내 멤버십이 이미 활성이면 수락 버튼이 없다', async () => {
    currentUserId = 12;
    mocked.detail.mockResolvedValue(org([owner, staff]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('member-12')).toBeInTheDocument());
    expect(screen.queryByTestId('accept-own')).not.toBeInTheDocument();
  });

  it('로그인 주체를 모르면(userId null) 수락 버튼을 그리지 않는다', async () => {
    currentUserId = null;
    mocked.detail.mockResolvedValue(org([owner, myInvite]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('member-100')).toBeInTheDocument());
    expect(screen.queryByTestId('accept-own')).not.toBeInTheDocument();
  });

  it('수락은 대상 없이 호출된다 — 조직 번호만 넘긴다', async () => {
    mocked.detail.mockResolvedValue(org([owner, myInvite]));
    mocked.acceptOwnInvite.mockResolvedValue({ ...myInvite, status: 'ACTIVE' });
    render(<OrganizationConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('accept-own')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('accept-own'));

    await waitFor(() => expect(mocked.acceptOwnInvite).toHaveBeenCalledWith(3));
  });
});

describe('OrganizationConsolePage — 마지막 OWNER', () => {
  it('활성 OWNER 가 1명이면 그 행의 역할 변경·제거를 막고 이유를 보여 준다', async () => {
    mocked.detail.mockResolvedValue(org([owner, staff]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('owner-count')).toBeInTheDocument());
    expect(screen.getByTestId('owner-count')).toHaveTextContent('마지막 OWNER 는 강등·제거할 수 없습니다');

    const ownerRow = screen.getByTestId('member-11');
    expect(ownerRow.querySelector('select')).toBeDisabled();
    expect(ownerRow.querySelector('button')).toBeDisabled();
  });

  it('OWNER 가 둘이면 막지 않는다', async () => {
    const second = { userId: 12, role: 'OWNER' as const, status: 'ACTIVE' as const, invitedBy: 11 };
    mocked.detail.mockResolvedValue(org([owner, second]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('owner-count')).toHaveTextContent('활성 OWNER 2명'));
    expect(screen.getByTestId('member-11').querySelector('select')).not.toBeDisabled();
  });

  it('INVITED OWNER 는 활성 OWNER 로 세지 않는다 — 수락 전에는 자리를 채우지 않는다', async () => {
    const invitedOwner = { userId: 12, role: 'OWNER' as const, status: 'INVITED' as const, invitedBy: 11 };
    mocked.detail.mockResolvedValue(org([owner, invitedOwner]));
    render(<OrganizationConsolePage />);
    lookup();

    await waitFor(() => expect(screen.getByTestId('owner-count')).toHaveTextContent('활성 OWNER 1명'));
    expect(screen.getByTestId('member-11').querySelector('select')).toBeDisabled();
  });

  it('서버가 422 로 막으면 그 문구를 그대로 보여 준다', async () => {
    // 화면의 사전 차단은 조회 시점 사본에 근거한다 — 그 사이 바뀌었으면 서버가 정답이다.
    const second = { userId: 12, role: 'OWNER' as const, status: 'ACTIVE' as const, invitedBy: 11 };
    mocked.detail.mockResolvedValue(org([owner, second]));
    mocked.changeRole.mockRejectedValue(new LastOwnerError('마지막 OWNER 는 강등할 수 없습니다'));
    render(<OrganizationConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('member-11')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('사용자 11 역할'), { target: { value: 'STAFF' } });

    expect(await screen.findByRole('alert')).toHaveTextContent('마지막 OWNER 는 강등할 수 없습니다');
  });
});

describe('OrganizationConsolePage — 생성·초대', () => {
  it('조직명이 비면 만들 수 없다', () => {
    render(<OrganizationConsolePage />);
    expect(screen.getByRole('button', { name: '만들기' })).toBeDisabled();
  });

  it('만든 뒤 그 조직을 다시 조회한다 — 생성 응답의 members 는 비어 있다', async () => {
    mocked.create.mockResolvedValue(org([]));
    mocked.detail.mockResolvedValue(org([owner]));
    render(<OrganizationConsolePage />);

    fireEvent.change(screen.getByLabelText('조직명'), { target: { value: '가나상사' } });
    fireEvent.click(screen.getByRole('button', { name: '만들기' }));

    await waitFor(() => expect(mocked.detail).toHaveBeenCalledWith(3));
    expect(await screen.findByTestId('member-11')).toBeInTheDocument();
  });

  it('초대 후 목록을 다시 읽는다', async () => {
    mocked.detail.mockResolvedValue(org([owner]));
    mocked.invite.mockResolvedValue({ ...staff, status: 'INVITED' });
    render(<OrganizationConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('org-detail')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('초대할 사용자 번호'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: '초대' }));

    await waitFor(() => expect(mocked.invite).toHaveBeenCalledWith(3, 12, 'STAFF'));
    await waitFor(() => expect(mocked.detail).toHaveBeenCalledTimes(2));
  });

  it('조직 번호를 고치면 조회 결과를 버린다', async () => {
    mocked.detail.mockResolvedValue(org([owner]));
    render(<OrganizationConsolePage />);
    lookup();
    await waitFor(() => expect(screen.getByTestId('org-detail')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('조직 번호'), { target: { value: '4' } });

    expect(screen.queryByTestId('org-detail')).not.toBeInTheDocument();
  });
});
