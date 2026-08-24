import React, { useState } from 'react';
import {
  organizationApi,
  LastOwnerError,
  type Organization,
  type Membership,
  type OrgRole,
  type OrgType,
  type MembershipStatus,
} from '@/api/organization';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';
import { useToast } from '@/contexts/useToast';

/**
 * 조직·멤버십 콘솔 — organization-service 최초의 화면.
 *
 * <p>이 서비스는 화면이 하나도 없었다. 조직을 만들고 사람을 붙이는 경로가 API 뿐이라 셀러/기업
 * 조직 구조를 실질적으로 운영할 수 없었다.
 *
 * <p><b>이 화면이 특히 조심하는 것: 수락 버튼.</b> 서버의 accept 는 <b>호출자 자신의</b> 초대를
 * 수락한다 — 관리자가 남의 초대를 대신 승인하는 API 가 아니다. 아무 행에나 "수락"을 달아 두면
 * 관리자가 승인 버튼으로 착각해 자기 멤버십을 만들어 버린다. 그래서 <b>로그인 주체의 행이면서
 * INVITED 인 경우에만</b> 그 버튼을 그린다.
 *
 * <p><b>활성 OWNER 는 최소 1명</b>이다. 마지막 OWNER 를 강등·제거하면 서버가 422 로 거절하는데,
 * 그건 실패가 아니라 도메인이 지키는 불변식이라 문구를 그대로 보여 준다. 화면도 미리 막아
 * 왕복을 줄이되, <b>서버 판정을 대신하지는 않는다</b> — 화면이 보는 것은 조회 시점의 사본이다.
 */

const ROLES: { value: OrgRole; label: string; note: string }[] = [
  { value: 'OWNER', label: 'OWNER', note: '조직 소유자 — 최소 1명이 있어야 한다' },
  { value: 'MANAGER', label: 'MANAGER', note: '운영 권한' },
  { value: 'STAFF', label: 'STAFF', note: '실무 권한' },
];

const STATUS_TONE: Record<MembershipStatus, string> = {
  INVITED: 'bg-amber-100 text-amber-900',
  ACTIVE: 'bg-green-100 text-green-900',
  SUSPENDED: 'bg-gray-200 text-gray-700',
  REMOVED: 'bg-gray-100 text-gray-500',
};

const STATUS_LABEL: Record<MembershipStatus, string> = {
  INVITED: '초대됨', ACTIVE: '활성', SUSPENDED: '정지', REMOVED: '제거됨',
};

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const buttonClass = 'rounded px-3 py-1.5 text-sm font-semibold disabled:opacity-50';

const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <div className="text-sm">
      <label className="block">
        <span className="text-gray-600">{label}</span>
        {children}
      </label>
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </div>
  );

/** 활성 OWNER 수 — 강등·제거를 미리 막는 근거. INVITED 는 아직 OWNER 자리를 채우지 않는다. */
const activeOwnerCount = (members: Membership[]) =>
  members.filter((m) => m.role === 'OWNER' && m.status === 'ACTIVE').length;

const OrganizationConsolePage: React.FC = () => {
  const { userId } = useAuth();
  const { showToast } = useToast();

  const [orgInput, setOrgInput] = useState('');
  const [org, setOrg] = useState<Organization | null>(null);
  const [looked, setLooked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState<OrgType>('SELLER');
  const [newRef, setNewRef] = useState('');

  const [inviteUserId, setInviteUserId] = useState('');
  const [inviteRole, setInviteRole] = useState<OrgRole>('STAFF');

  const parsed = Number(orgInput.trim());
  const validOrgId = orgInput.trim() !== '' && Number.isInteger(parsed) && parsed > 0;

  /** 조직 번호를 고치면 조회 결과를 버린다 — "A 를 보고 B 를 조작"을 만들지 않는다. */
  const changeOrg = (value: string) => {
    setOrgInput(value);
    setOrg(null);
    setLooked(false);
    setError(null);
  };

  const lookup = async (id = parsed) => {
    setBusy(true);
    setError(null);
    try {
      setOrg(await organizationApi.detail(id));
      setLooked(true);
    } catch (err) {
      setError(apiErrorMessage(err, '조직을 조회하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  /** 마지막 OWNER 위반은 실패가 아니라 불변식이라 문구를 그대로 쓴다. */
  const run = async (fallback: string, action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof LastOwnerError ? err.message : apiErrorMessage(err, fallback));
    } finally {
      setBusy(false);
    }
  };

  const create = () => void run('조직을 만들지 못했습니다.', async () => {
    const created = await organizationApi.create({
      name: newName.trim(), type: newType,
      ...(newRef.trim() ? { externalRef: newRef.trim() } : {}),
    });
    setNewName(''); setNewRef('');
    showToast(`조직 #${created.id} "${created.name}" 을 만들었습니다. 만든 분이 OWNER 입니다.`, 'success');
    // 생성 응답의 members 는 비어 있다(서버 주석) — 조회로 갈아끼워야 멤버가 보인다.
    setOrgInput(String(created.id));
    await lookup(created.id);
  });

  const invite = () => void run('초대하지 못했습니다.', async () => {
    await organizationApi.invite(org!.id, Number(inviteUserId), inviteRole);
    setInviteUserId('');
    showToast('초대했습니다. 상대가 직접 수락해야 활성이 됩니다.', 'success');
    await lookup(org!.id);
  });

  const changeRole = (member: Membership, newRole: OrgRole) => {
    if (newRole === member.role) return;
    if (!window.confirm(
      `사용자 ${member.userId} 의 역할을 ${member.role} → ${newRole} 로 바꿉니다.\n\n계속하시겠습니까?`)) return;
    void run('역할을 바꾸지 못했습니다.', async () => {
      await organizationApi.changeRole(org!.id, member.userId, newRole);
      showToast(`사용자 ${member.userId} → ${newRole}`, 'success');
      await lookup(org!.id);
    });
  };

  const remove = (member: Membership) => {
    if (!window.confirm(
      `사용자 ${member.userId} 를 조직에서 제거합니다.\n\n계속하시겠습니까?`)) return;
    void run('제거하지 못했습니다.', async () => {
      await organizationApi.remove(org!.id, member.userId);
      showToast(`사용자 ${member.userId} 를 제거했습니다.`, 'success');
      await lookup(org!.id);
    });
  };

  const acceptOwn = () => void run('초대를 수락하지 못했습니다.', async () => {
    await organizationApi.acceptOwnInvite(org!.id);
    showToast('초대를 수락했습니다.', 'success');
    await lookup(org!.id);
  });

  const owners = org ? activeOwnerCount(org.members) : 0;
  /** 활성 OWNER 가 1명뿐이면 그 사람의 강등·제거를 미리 막는다(서버도 422 로 막는다). */
  const isLastOwner = (m: Membership) =>
    m.role === 'OWNER' && m.status === 'ACTIVE' && owners <= 1;

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">조직 · 멤버십</h1>
          <p className="text-sm text-gray-500 mt-1">
            셀러/기업 조직과 구성원을 관리합니다. 초대는 상대가 <b>직접 수락</b>해야 활성이 되고,
            활성 OWNER 는 최소 1명이어야 합니다.
          </p>
        </div>

        {/* 생성 */}
        <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
          data-testid="create-panel">
          <h2 className="font-semibold text-gray-900">조직 만들기</h2>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="조직명">
              <input value={newName} onChange={(e) => setNewName(e.target.value)} className={inputClass} />
            </Field>
            <Field label="유형">
              <select value={newType} className={inputClass}
                onChange={(e) => setNewType(e.target.value as OrgType)}>
                <option value="SELLER">셀러</option>
                <option value="CORPORATE">기업</option>
              </select>
            </Field>
            <Field label="외부 참조" hint="선택 — 사업자번호 등 외부 시스템 키">
              <input value={newRef} onChange={(e) => setNewRef(e.target.value)}
                className={`${inputClass} font-mono`} />
            </Field>
          </div>
          <button type="button" onClick={create} disabled={busy || newName.trim() === ''}
            className={`${buttonClass} bg-blue-600 px-4 py-2 text-white`}>
            만들기
          </button>
          <p className="text-xs text-gray-500">만든 사람이 자동으로 OWNER 가 됩니다.</p>
        </section>

        {/* 조회 */}
        <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
          data-testid="lookup-panel">
          <div className="flex flex-wrap items-end gap-2">
            <Field label="조직 번호" hint="목록 조회 API 가 없어 번호를 직접 넣습니다(서버 제약)">
              <input value={orgInput} inputMode="numeric"
                onChange={(e) => changeOrg(e.target.value)}
                className="mt-1 block w-40 rounded border px-3 py-2 font-mono" />
            </Field>
            <button type="button" onClick={() => void lookup()} disabled={!validOrgId || busy}
              className={`${buttonClass} border border-gray-300 bg-white px-4 py-2 text-gray-700`}>
              조직 조회
            </button>
          </div>

          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

          {looked && org === null && (
            <p className="text-sm text-gray-600" data-testid="org-missing">
              조직 #{parsed} 을 찾을 수 없습니다.
            </p>
          )}
        </section>

        {org && (
          <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-4"
            data-testid="org-detail">
            <div className="flex flex-wrap items-baseline gap-3">
              <h2 className="font-semibold text-gray-900" data-testid="org-name">{org.name}</h2>
              <span className="text-sm text-gray-500">
                #{org.id} · {org.type === 'SELLER' ? '셀러' : '기업'} · {org.status}
                {org.externalRef && <> · <span className="font-mono">{org.externalRef}</span></>}
              </span>
            </div>

            {/* OWNER 수를 늘 보여 준다 — 강등이 막히는 이유가 화면에 있어야 한다. */}
            <p className="text-sm text-gray-600" data-testid="owner-count">
              활성 OWNER {owners}명
              {owners <= 1 && <b className="text-amber-800"> — 마지막 OWNER 는 강등·제거할 수 없습니다.</b>}
            </p>

            <table className="w-full text-sm" data-testid="member-table">
              <thead className="text-left text-gray-500">
                <tr><th className="py-2">사용자</th><th>역할</th><th>상태</th><th>초대자</th><th /></tr>
              </thead>
              <tbody>
                {org.members.map((m) => (
                  <tr key={m.userId} className="border-t" data-testid={`member-${m.userId}`}>
                    <td className="py-2 font-mono">{m.userId}</td>
                    <td>
                      <select value={m.role} disabled={busy || isLastOwner(m)}
                        aria-label={`사용자 ${m.userId} 역할`}
                        onChange={(e) => changeRole(m, e.target.value as OrgRole)}
                        className="rounded border px-2 py-1">
                        {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                      </select>
                    </td>
                    <td>
                      <span className={`rounded px-2 py-0.5 text-xs ${STATUS_TONE[m.status]}`}>
                        {STATUS_LABEL[m.status]}
                      </span>
                    </td>
                    <td className="text-gray-500">{m.invitedBy ?? '-'}</td>
                    <td className="space-x-2 text-right whitespace-nowrap">
                      {/* 수락은 본인 것만이다. 남의 행에 달면 관리자가 승인 버튼으로 착각한다. */}
                      {m.status === 'INVITED' && userId === m.userId && (
                        <button type="button" onClick={acceptOwn} disabled={busy}
                          className={`${buttonClass} bg-green-600 text-white`}
                          data-testid="accept-own">
                          내 초대 수락
                        </button>
                      )}
                      <button type="button" onClick={() => remove(m)}
                        disabled={busy || isLastOwner(m)}
                        className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>
                        제거
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="flex flex-wrap items-end gap-3 border-t pt-3">
              <Field label="초대할 사용자 번호">
                <input value={inviteUserId} inputMode="numeric"
                  onChange={(e) => setInviteUserId(e.target.value)}
                  className="mt-1 block w-40 rounded border px-3 py-2 font-mono" />
              </Field>
              <Field label="역할">
                <select value={inviteRole} className="mt-1 block rounded border px-3 py-2"
                  onChange={(e) => setInviteRole(e.target.value as OrgRole)}>
                  {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                </select>
              </Field>
              <button type="button" onClick={invite}
                disabled={busy || inviteUserId.trim() === '' || Number(inviteUserId) <= 0}
                className={`${buttonClass} bg-blue-600 px-4 py-2 text-white`}>
                초대
              </button>
            </div>
            <p className="text-xs text-gray-500">
              초대하면 상태가 <b>초대됨</b>이 됩니다. 활성이 되려면 <b>상대가 직접 수락</b>해야 하며,
              관리자가 대신 수락할 수 없습니다.
            </p>
          </section>
        )}
      </div>
    </div>
  );
};

export default OrganizationConsolePage;
