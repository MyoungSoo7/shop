import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  MEMBER_ROLE_LABEL,
  SELLER_TIER_LABEL,
  formatMoney,
  partnerApi,
  type PartnerDashboard,
  type PartnerMember,
  type PartnerProfile,
} from '@/api/partner';
import { apiErrorData, apiErrorMessage, apiErrorStatus } from '@/lib/apiError';

/**
 * 파트너 콘솔 — 우리 몰에 입점한 <b>기업이 자기 매출을 확인하는</b> 화면.
 *
 * <p><b>왜 생겼나.</b> 지금까지 입점사가 "우리 상품이 이번 달에 얼마나 팔렸나" 를 알 방법은
 * 운영자에게 물어보는 것뿐이었다. 운영자 콘솔(/admin/system/sales-stats)에는 그 숫자가 있지만
 * 그건 전사 숫자라 남의 매출까지 함께 보인다 — 입점사에게 열어 줄 수 없는 화면이다.
 *
 * <p><b>세 가지 상태가 있고 셋 다 다르게 그린다.</b> 이걸 뭉개면 문의가 들어온다.
 *   ① 입점 조직이 아닌 계정 — 403. "권한 없음" 이 아니라 "이 계정은 입점 조직이 아니다" 다.
 *   ② 입점했지만 판매 조직이 아닌 법인(CORPORATE) — 매출 개념 자체가 없다. 빈 표로 그리면
 *      법인 고객은 자기 데이터가 유실됐다고 읽는다.
 *   ③ 판매 조직인데 기간 안에 결제가 없음 — 진짜로 0원이다.
 *
 * <p><b>숫자에 각주가 붙는다.</b> 결제시각이 이벤트에 없어 수신 시각으로 대체된 건이 기간 안에
 * 있으면 서버가 {@code estimatedCaptureDates} 로 알린다. 금액은 정확하지만 자정 근처에서
 * 하루가 밀렸을 수 있다. 숨기면 "어제 매출이 왜 다르냐" 가 되고, 그때는 설명할 근거가 없다.
 *
 * <p><b>기간 기본값은 서버가 정한다</b>(최근 30일). 화면이 오늘을 계산해 보내면 사용자 기기의
 * 시계·시간대가 기준이 되어, 같은 화면이 사람마다 다른 기간을 부른다.
 */

const todayIso = () => new Date().toISOString().slice(0, 10);

const daysAgoIso = (days: number) => {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
};

/**
 * 403 중에서도 "입점 조직이 아님"({@code NOT_A_PARTNER}) 만 골라낸다.
 *
 * <p>상태코드만 보면 안 된다 — 서버는 인가 실패에도 403 을 준다({@code FORBIDDEN}). 둘을
 * 뭉뚱그리면 진짜 권한 사고가 "당신은 입점사가 아닙니다" 라는 안내로 덮여 조사되지 않는다.
 */
const isNotPartner = (err: unknown): boolean => {
  if (apiErrorStatus(err) !== 403) return false;
  const data = apiErrorData(err);
  return typeof data === 'object' && data !== null
    && (data as { code?: unknown }).code === 'NOT_A_PARTNER';
};

function ProfileCard({ profile }: { profile: PartnerProfile }) {
  const tier = profile.currentTier;
  return (
    <section className="rounded-lg bg-white p-4 shadow" data-testid="partner-profile">
      <h2 className="text-lg font-semibold text-gray-900">{profile.organizationName}</h2>
      <p className="mt-1 text-sm text-gray-600">
        {profile.orgType === 'SELLER' ? '판매 조직' : '법인 고객'}
        {' · '}내 역할 {MEMBER_ROLE_LABEL[profile.myRole]}
        {profile.sellerId !== null && ` · 셀러번호 ${profile.sellerId}`}
      </p>
      {/* 등급이 null 인 것은 NORMAL 이 아니라 "아직 모른다" 다. 기본값으로 채우면 화면이 거짓을
          말하고, 등급별 수수료를 그 표시로 짐작한 파트너가 틀린 계산을 하게 된다. */}
      <p className="mt-1 text-sm text-gray-600" data-testid="partner-tier">
        {tier === null
          ? '셀러 등급: 아직 확인되지 않았습니다'
          : `셀러 등급: ${SELLER_TIER_LABEL[tier]}${
            profile.tierEffectiveFrom === null ? '' : ` (${profile.tierEffectiveFrom}부터)`}`}
      </p>
      {/* 등급은 비소급이다 — 지금 등급으로 과거 매출을 다시 계산하면 이미 정산된 금액이 바뀐다. */}
      <p className="mt-2 text-xs text-gray-500">
        등급은 앞으로의 거래에 적용됩니다. 지난 결제의 조건은 그 시점 등급을 따릅니다.
      </p>
    </section>
  );
}

function SummaryCards({ dashboard }: { dashboard: PartnerDashboard }) {
  const { summary } = dashboard;
  const cards = [
    { key: 'gross', label: '총매출', value: summary.grossAmount },
    { key: 'refunded', label: '환불', value: summary.refundedAmount },
    { key: 'net', label: '실매출', value: summary.netAmount },
  ];
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3" data-testid="partner-summary">
      {cards.map((card) => (
        <div key={card.key} className="rounded-lg bg-white p-4 shadow">
          <p className="text-sm text-gray-500">{card.label}</p>
          {/* 실매출은 음수가 될 수 있다(지난달 결제분이 이번 달에 환불된 경우). 0 으로 깎지
              않는 이유는, 깎는 순간 화면 합계와 실제 정산액이 어긋나고 아무도 그 차이를
              설명하지 못하기 때문이다. */}
          <p
            data-testid={`partner-summary-${card.key}`}
            className={`mt-1 text-xl font-semibold ${
              Number(card.value) < 0 ? 'text-red-600' : 'text-gray-900'}`}
          >
            {formatMoney(card.value)}
          </p>
        </div>
      ))}
      <p className="text-sm text-gray-500 sm:col-span-3" data-testid="partner-order-count">
        결제 {summary.orderCount.toLocaleString('ko-KR')}건 · {dashboard.from} ~ {dashboard.to}
      </p>
    </div>
  );
}

function DailyTable({ daily }: { daily: PartnerDashboard['daily'] }) {
  if (daily.length === 0) {
    return <p className="text-sm text-gray-500" data-testid="partner-daily-empty">이 기간에는 결제가 없습니다.</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm" data-testid="partner-daily">
        <thead className="bg-gray-50 text-left text-gray-600">
          <tr>
            <th className="px-3 py-2">일자</th>
            <th className="px-3 py-2 text-right">총매출</th>
            <th className="px-3 py-2 text-right">환불</th>
            <th className="px-3 py-2 text-right">실매출</th>
            <th className="px-3 py-2 text-right">건수</th>
          </tr>
        </thead>
        {/* 매출이 0 인 날은 행이 없다 — 서버가 빈 날을 만들어 채우지 않는다. 아직 이벤트가
            도착하지 않은 날까지 "0원 확정" 으로 보이게 하지 않으려는 것이다. */}
        <tbody>
          {daily.map((row) => (
            <tr key={row.date} className="border-t border-gray-100">
              <td className="px-3 py-2">{row.date}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.grossAmount)}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.refundedAmount)}</td>
              <td className="px-3 py-2 text-right font-medium">{formatMoney(row.netAmount)}</td>
              <td className="px-3 py-2 text-right">{row.orderCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BestProducts({ products }: { products: PartnerDashboard['bestProducts'] }) {
  if (products.length === 0) {
    return <p className="text-sm text-gray-500" data-testid="partner-best-empty">집계된 상품이 없습니다.</p>;
  }
  return (
    <ul className="space-y-2" data-testid="partner-best">
      {products.map((product, index) => (
        <li
          key={product.productId ?? `unknown-${index}`}
          className="flex items-center justify-between rounded border border-gray-100 px-3 py-2 text-sm"
        >
          {/* 상품번호도 이름도 없는 행이 정상적으로 나온다: 결제는 왔는데 그 주문의
              order.created 가 아직 안 온 경우다. 버리면 상품별 합이 총매출과 안 맞는다. */}
          <span className="text-gray-800">
            {index + 1}. {product.productName ?? (product.productId === null
              ? '미확인 상품'
              : `상품 ${product.productId}`)}
          </span>
          <span className="text-gray-900">
            {formatMoney(product.netAmount)} · {product.orderCount}건
          </span>
        </li>
      ))}
    </ul>
  );
}

function MemberTable({ members }: { members: PartnerMember[] }) {
  return (
    <section className="rounded-lg bg-white p-4 shadow">
      <h2 className="text-lg font-semibold text-gray-900">구성원</h2>
      {/* 이름·이메일이 없는 것은 누락이 아니다 — 조직 이벤트가 숫자 userId 만 싣는다. 없는 값을
          회원 서비스에 물어 채우면 서비스 간 동기 호출이 생기고, 개인정보가 이 화면에 들어오면
          지금 없는 마스킹 통제가 필요해진다. 그 사실을 화면에도 적는다. */}
      <p className="mt-1 text-xs text-gray-500">
        이름·연락처는 이 콘솔에 저장하지 않습니다. 구성원 변경은 운영자에게 요청하세요.
      </p>
      {members.length === 0
        ? <p className="mt-3 text-sm text-gray-500" data-testid="partner-members-empty">등록된 구성원이 없습니다.</p>
        : (
          <ul className="mt-3 space-y-1 text-sm" data-testid="partner-members">
            {members.map((member) => (
              <li key={member.membershipId} className="flex justify-between border-t border-gray-100 py-1">
                <span className="text-gray-800">회원번호 {member.userId}</span>
                <span className="text-gray-600">
                  {MEMBER_ROLE_LABEL[member.role]} · {member.joinedAt.slice(0, 10)} 합류
                </span>
              </li>
            ))}
          </ul>
        )}
    </section>
  );
}

export default function PartnerConsolePage() {
  const [profile, setProfile] = useState<PartnerProfile | null>(null);
  const [dashboard, setDashboard] = useState<PartnerDashboard | null>(null);
  const [members, setMembers] = useState<PartnerMember[]>([]);
  const [notPartner, setNotPartner] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [from, setFrom] = useState(() => daysAgoIso(30));
  const [to, setTo] = useState(todayIso);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const me = await partnerApi.me();
      setProfile(me);
      setNotPartner(false);
      // 구성원은 매출과 무관하게 언제나 있다. 매출은 판매 조직일 때만 부른다 —
      // 아니면 서버가 422 NO_SALES_SCOPE 로 거절하고, 그건 오류가 아니라 상태다.
      const [memberList, board] = await Promise.all([
        partnerApi.members(),
        me.salesAvailable ? partnerApi.dashboard({ from, to }) : Promise.resolve(null),
      ]);
      setMembers(memberList);
      setDashboard(board);
    } catch (err) {
      if (isNotPartner(err)) {
        setNotPartner(true);
      } else {
        setError(apiErrorMessage(err, '파트너 정보를 불러오지 못했습니다.'));
      }
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => { void load(); }, [load]);

  if (loading && profile === null) {
    return <p className="p-6 text-sm text-gray-500" data-testid="partner-loading">불러오는 중…</p>;
  }

  if (notPartner) {
    return (
      <div className="mx-auto max-w-3xl p-6" data-testid="partner-not-a-partner">
        <h1 className="text-xl font-semibold text-gray-900">파트너 콘솔</h1>
        {/* "접근 권한이 없습니다" 가 아니다. 권한을 올려 달라고 요청할 일이 아니라, 이 계정이
            어떤 입점 조직에도 속해 있지 않다는 뜻이다. 둘을 같은 문구로 적으면 파트너도
            운영자도 무엇을 고쳐야 하는지 모른다. */}
        <p className="mt-3 text-sm text-gray-700">
          이 계정은 입점 조직에 속해 있지 않습니다. 입점사 담당자로 등록되어야 매출을 볼 수 있습니다.
        </p>
        <p className="mt-1 text-sm text-gray-500">
          입점 계약이 되어 있다면 운영자에게 조직 구성원 등록을 요청하세요.
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-gray-900">파트너 콘솔</h1>
        <Link to="/partner/orders" className="text-sm text-blue-600 hover:underline">
          주문 내역 보기 →
        </Link>
      </header>

      {error !== null && (
        <p className="rounded bg-red-50 p-3 text-sm text-red-700" data-testid="partner-error">{error}</p>
      )}

      {profile !== null && <ProfileCard profile={profile} />}

      {profile !== null && !profile.salesAvailable ? (
        // ②번 상태. 빈 표가 아니라 문장으로 적는다 — 법인 고객이 "데이터가 사라졌다" 로 읽는
        // 것을 막는 유일한 방법이다.
        <section className="rounded-lg bg-white p-4 shadow" data-testid="partner-no-sales">
          <h2 className="text-lg font-semibold text-gray-900">매출</h2>
          <p className="mt-2 text-sm text-gray-700">
            이 조직은 판매 조직이 아니라 매출 집계 대상이 아닙니다. 데이터가 비어 있는 것이 아니라
            해당 개념이 없습니다.
          </p>
        </section>
      ) : (
        <section className="space-y-3">
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-sm text-gray-700">
              시작일
              <input
                type="date" value={from} onChange={(e) => setFrom(e.target.value)}
                data-testid="partner-from"
                className="ml-2 rounded border border-gray-300 px-2 py-1"
              />
            </label>
            <label className="text-sm text-gray-700">
              종료일
              <input
                type="date" value={to} onChange={(e) => setTo(e.target.value)}
                data-testid="partner-to"
                className="ml-2 rounded border border-gray-300 px-2 py-1"
              />
            </label>
          </div>

          {dashboard !== null && (
            <>
              <SummaryCards dashboard={dashboard} />
              {/* 각주. 금액은 정확하고 날짜만 하루 밀렸을 수 있다 — 그 구분까지 적어야
                  "숫자가 틀렸다" 가 아니라 "경계일이 다를 수 있다" 로 읽힌다. */}
              {dashboard.estimatedCaptureDates && (
                <p className="rounded bg-amber-50 p-2 text-xs text-amber-800" data-testid="partner-estimated">
                  일부 결제는 결제시각이 전달되지 않아 수신 시각으로 집계했습니다. 금액은 정확하며
                  자정 근처 건의 일자가 하루 다를 수 있습니다.
                </p>
              )}
              <div className="rounded-lg bg-white p-4 shadow">
                <h2 className="text-lg font-semibold text-gray-900">일자별</h2>
                <div className="mt-2"><DailyTable daily={dashboard.daily} /></div>
              </div>
              <div className="rounded-lg bg-white p-4 shadow">
                <h2 className="text-lg font-semibold text-gray-900">많이 팔린 상품</h2>
                {/* 순위와 금액이 둘 다 실매출 기준이다. 총매출로 줄을 세우면 전량 환불된 상품이
                    1위에 앉는다 — 레퍼런스 백오피스가 실제로 그랬다. */}
                <p className="mt-1 text-xs text-gray-500">환불을 뺀 실매출 기준입니다.</p>
                <div className="mt-2"><BestProducts products={dashboard.bestProducts} /></div>
              </div>
            </>
          )}
        </section>
      )}

      <MemberTable members={members} />
    </div>
  );
}
