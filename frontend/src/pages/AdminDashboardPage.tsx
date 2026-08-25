import React, { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { adminApi, AdminUserResponse, AdminOrderSummary } from '@/api/admin';
import { productApi } from '@/api/product';
import { orderApi } from '@/api/order';
import { couponApi } from '@/api/coupon';
import { authApi } from '@/api/auth';
import { opsDashboardApi, TodayOverview, MetricCard } from '@/api/opsDashboard';
import { revenueAdminApi, RevenueReport, toIsoDate } from '@/api/revenueAdmin';
import { OrderResponse, ProductResponse, CouponResponse, CouponType, CouponCreateRequest } from '@/types';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

type Tab = 'overview' | 'orders' | 'products' | 'users' | 'coupons';

/** 주문 탭 한 페이지 건수. 서버 상한(200)보다 작게 잡는다. */
const ORDER_PAGE_SIZE = 50;

/** 개요 탭 "최근 주문" 건수. */
const RECENT_ORDER_COUNT = 5;

/** 매출 섹션이 보는 기간 — 오늘 포함 최근 N일. 서버 상한은 366일이다. */
const REVENUE_DAYS = 30;

/** 결제수단 라벨. 서버는 enum 이름을 주고 화면이 한글로 읽는다. */
const TENDER_LABEL: Record<string, string> = {
  CARD: '카드',
  KAKAO_PAY: '카카오페이',
  NAVER_PAY: '네이버페이',
  PAYCO: '페이코',
  SAMSUNG_PAY: '삼성페이',
  BANK_TRANSFER: '무통장입금',
  VIRTUAL_ACCOUNT: '가상계좌',
  POINT: '포인트',
  GIFT_CARD: '상품권',
};

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtDate = (s: string) =>
  new Date(s).toLocaleDateString('ko-KR', { year: 'numeric', month: 'short', day: 'numeric' });

/** 서버가 보낸 금액은 문자열이다(정밀도 보존). 표시 직전에만 수로 바꾼다. */
const fmtAmount = (v: string | null) => (v === null ? '—' : fmt(Number(v)));

const fmtTime = (s: string) =>
  new Date(s).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });

// ── 오늘 한눈에 카드 ──────────────────────────────────────────────────────
/**
 * 서버가 집계한 하루치 지표 한 장.
 *
 * 라벨을 화면에서 만들지 않는 이유 — 지표를 더할 때 매핑 테이블을 같이 고치는 걸 잊으면
 * 언젠가 `PAYMENT_REFUNDED` 같은 키가 그대로 찍힌 카드가 뜬다. 라벨은 서버가 정본이다.
 */
const TodayCard: React.FC<{ card: MetricCard }> = ({ card }) => (
  <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
    <span className="text-sm font-medium text-gray-500">{card.label}</span>
    <p className="text-2xl font-bold text-gray-900 mt-2">{card.count.toLocaleString()}건</p>
    {card.hasAmount && (
      <p className="text-sm font-semibold text-gray-700 mt-1">{fmtAmount(card.amount)}</p>
    )}
    {/*
      금액을 못 읽은 건이 있으면 반드시 말한다. 모르는 값을 조용히 0으로 합산하면 합계가
      "맞는 것처럼 보이는 틀린 값"이 되고, 그건 아무도 검증하지 않는다.
    */}
    {card.hasAmount && !card.amountComplete && (
      <p className="text-xs text-amber-600 mt-1">금액 미상 {card.amountUnknownCount}건 제외</p>
    )}
  </div>
);

// ── 통계 카드 ────────────────────────────────────────────────────────────
const StatCard: React.FC<{
  label: string; value: string | number; sub?: string;
  icon: React.ReactNode; color: string;
}> = ({ label, value, sub, icon, color }) => (
  <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
    <div className="flex items-center justify-between mb-3">
      <span className="text-sm font-medium text-gray-500">{label}</span>
      <div className={`w-10 h-10 rounded-lg ${color} flex items-center justify-center`}>
        {icon}
      </div>
    </div>
    <p className="text-2xl font-bold text-gray-900">{value}</p>
    {sub && <p className="text-xs text-gray-400 mt-1">{sub}</p>}
  </div>
);

// ── 상태 배지 ─────────────────────────────────────────────────────────────
const OrderStatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const cfg: Record<string, string> = {
    CREATED:  'bg-yellow-100 text-yellow-800',
    PAID:     'bg-green-100 text-green-800',
    CANCELED: 'bg-red-100 text-red-800',
    REFUNDED: 'bg-purple-100 text-purple-800',
  };
  const label: Record<string, string> = {
    CREATED: '주문완료', PAID: '결제완료', CANCELED: '취소됨', REFUNDED: '환불됨',
  };
  return (
    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${cfg[status] ?? 'bg-gray-100 text-gray-700'}`}>
      {label[status] ?? status}
    </span>
  );
};

const ProductStatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const cfg: Record<string, string> = {
    ACTIVE:       'bg-green-100 text-green-800',
    INACTIVE:     'bg-gray-100 text-gray-700',
    OUT_OF_STOCK: 'bg-orange-100 text-orange-800',
    DISCONTINUED: 'bg-red-100 text-red-800',
  };
  const label: Record<string, string> = {
    ACTIVE: '판매중', INACTIVE: '비활성', OUT_OF_STOCK: '품절', DISCONTINUED: '단종',
  };
  return (
    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${cfg[status] ?? 'bg-gray-100 text-gray-700'}`}>
      {label[status] ?? status}
    </span>
  );
};

const RoleBadge: React.FC<{ role: string }> = ({ role }) => {
  const cfg: Record<string, string> = {
    ADMIN:   'bg-red-100 text-red-800',
    MANAGER: 'bg-purple-100 text-purple-800',
    USER:    'bg-blue-100 text-blue-800',
  };
  return (
    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${cfg[role] ?? 'bg-gray-100 text-gray-700'}`}>
      {role}
    </span>
  );
};

// ════════════════════════════════════════════════════════════════════════════
const AdminDashboardPage: React.FC = () => {
  const currentUser = authApi.getCurrentUser();
  const isAdmin = currentUser?.role === 'ADMIN';

  const [activeTab, setActiveTab] = useState<Tab>('overview');

  const [orders,   setOrders]   = useState<OrderResponse[]>([]);
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [users,    setUsers]    = useState<AdminUserResponse[]>([]);
  const [coupons,  setCoupons]  = useState<CouponResponse[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);

  // 쿠폰 생성 폼
  const [couponForm, setCouponForm] = useState<CouponCreateRequest>({
    code: '', type: 'PERCENTAGE', discountValue: 10, minOrderAmount: 0, maxUses: 100,
  });
  const [couponError, setCouponError]   = useState<string | null>(null);
  const [couponSuccess, setCouponSuccess] = useState<string | null>(null);
  const [creatingCoupon, setCreatingCoupon] = useState(false);

  /**
   * 주문 목록은 <b>한 페이지치</b>다. 예전에는 전 주문을 받아 배열에 담고 화면이 세었는데,
   * 주문은 지우지 않고 계속 쌓이므로 그 방식은 언젠가 반드시 죽는다. 대신 규모를 말하는
   * 숫자는 전부 아래 {@link orderSummary}(서버 집계)에서 온다 — 배열을 세면 페이징이 붙은
   * 지금 그 숫자는 "첫 페이지만 센 값"이 되는데, 화면에는 여전히 숫자가 찍힌다.
   */
  const [orderPageIndex, setOrderPageIndex] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderTotalPages, setOrderTotalPages] = useState(0);
  const [orderSummary, setOrderSummary] = useState<AdminOrderSummary | null>(null);
  /** 개요 탭의 "최근 주문" — 상태 필터와 무관한 최신순 5건이라 목록과 따로 부른다. */
  const [recentOrders, setRecentOrders] = useState<OrderResponse[]>([]);

  // 필터
  const [orderStatusFilter,  setOrderStatusFilter]  = useState('ALL');
  const [productStatusFilter, setProductStatusFilter] = useState('ALL');
  const [userRoleFilter,      setUserRoleFilter]      = useState('ALL');
  const [orderSearch,  setOrderSearch]  = useState('');
  const [productSearch, setProductSearch] = useState('');
  const [userSearch,    setUserSearch]    = useState('');

  // 주문 취소 처리
  const [cancellingId, setCancellingId] = useState<number | null>(null);

  // 오늘 한눈에 — 서버 집계. 실패해도 화면 전체를 막지 않는다(아래 useEffect 주석 참조).
  const [today, setToday] = useState<TodayOverview | null>(null);
  const [todayError, setTodayError] = useState<string | null>(null);

  // 기간 매출 — 결제 원장 기준. 주문 상태 합계로 세던 옛 "총 매출"을 대신한다.
  const [revenue, setRevenue] = useState<RevenueReport | null>(null);
  const [revenueError, setRevenueError] = useState<string | null>(null);

  useEffect(() => {
    const load = async () => {
      try {
        // MANAGER는 회원 목록/쿠폰 조회 권한이 없으므로 ADMIN일 때만 요청
      const baseRequests = [
        // 전 기간 카드·상태 분포의 유일한 출처. 목록을 세지 않는다.
        adminApi.getOrderSummary(),
        adminApi.getOrders({ page: 0, size: RECENT_ORDER_COUNT }),
        productApi.getAllProducts(),
      ] as const;

      if (isAdmin) {
        const [summary, recent, productList, userList, couponList] = await Promise.all([
          ...baseRequests,
          adminApi.getAllUsers(),
          couponApi.getAll(),
        ]);
        setOrderSummary(summary);
        setRecentOrders(recent.content);
        setProducts(productList.sort((a, b) => b.id - a.id));
        setUsers(userList.sort((a, b) => a.id - b.id));
        setCoupons(couponList.sort((a, b) => b.id - a.id));
      } else {
        const [summary, recent, productList] = await Promise.all(baseRequests);
        setOrderSummary(summary);
        setRecentOrders(recent.content);
        setProducts(productList.sort((a, b) => b.id - a.id));
      }
      } catch {
        setError('데이터를 불러오지 못했습니다.');
      } finally {
        setLoading(false);
      }
    };
    // isAdmin 은 localStorage 의 역할에서 파생된 불리언이라 세션 중 바뀌지 않는다 —
    // 의존성에 넣어도 재조회는 역할이 실제로 달라질 때만 일어난다.
    load();
  }, [isAdmin]);

  /**
   * 주문 목록 한 페이지. 상태 필터·페이지가 바뀔 때마다 서버에 다시 묻는다.
   *
   * <p>상태 필터를 <b>서버에서</b> 거는 것이 핵심이다. 받아 온 페이지를 브라우저가 거르면
   * "50건 중 PAID 3건"이 나오는데, 화면은 그걸 "PAID 3건"으로 보여 준다 — 나머지 페이지에
   * 있는 PAID 건은 세어지지도, 보이지도 않는다.
   */
  useEffect(() => {
    let canceled = false;
    setOrdersLoading(true);
    adminApi
      .getOrders({
        status: orderStatusFilter === 'ALL' ? undefined : [orderStatusFilter],
        page: orderPageIndex,
        size: ORDER_PAGE_SIZE,
      })
      .then((page) => {
        if (canceled) return;
        setOrders(page.content);
        setOrderTotal(page.totalElements);
        setOrderTotalPages(page.totalPages);
      })
      .catch(() => {
        if (!canceled) setError('데이터를 불러오지 못했습니다.');
      })
      .finally(() => {
        if (!canceled) setOrdersLoading(false);
      });
    return () => {
      // 필터를 빠르게 바꾸면 늦게 도착한 앞 응답이 뒤 응답을 덮어쓴다 — 화면의 필터와
      // 목록이 어긋나고, 그 상태는 에러 없이 그냥 틀린 화면으로 남는다.
      canceled = true;
    };
  }, [orderStatusFilter, orderPageIndex]);

  /**
   * 오늘 집계는 <b>따로</b> 부른다. 위 목록 로드와 한 Promise.all 에 묶으면 운영 서비스가
   * 잠깐 죽었을 때 주문·상품 목록까지 통째로 못 보게 된다 — 장애 상황을 보라고 만든 화면이
   * 장애 때 제일 먼저 죽는 구조가 된다. 여기 실패는 이 섹션 안에만 남긴다.
   *
   * MANAGER 는 부르지 않는다. `/api/ops/**` 는 ROLE_ADMIN 체인이라 403 이 확정인데,
   * 굳이 던져서 콘솔에 붉은 줄을 남길 이유가 없다.
   */
  useEffect(() => {
    if (!isAdmin) return;
    opsDashboardApi
      .today()
      .then(setToday)
      .catch(() => setTodayError('오늘 집계를 불러오지 못했습니다.'));
  }, [isAdmin]);

  /**
   * 기간 매출 — 최근 {@link REVENUE_DAYS}일.
   *
   * 오늘 집계와 같은 이유로 따로 부르고 실패도 이 섹션 안에만 남긴다.
   *
   * MANAGER 도 부른다 — `/admin/revenue` 는 ADMIN·MANAGER 매처라 403 이 아니다
   * (`/api/ops/**` 와 다르다).
   */
  useEffect(() => {
    const to = new Date();
    const from = new Date();
    from.setDate(to.getDate() - (REVENUE_DAYS - 1));
    revenueAdminApi
      .report(toIsoDate(from), toIsoDate(to))
      .then(setRevenue)
      .catch(() => setRevenueError('매출 집계를 불러오지 못했습니다.'));
  }, []);

  // ── 통계 계산 ──
  /**
   * 주문 쪽 숫자는 <b>전부</b> 서버 집계에서 온다. 목록 배열을 세면 페이징이 붙은 지금
   * "첫 페이지만 센 값"이 되고, 그 값은 틀렸다는 신호 없이 카드에 그대로 찍힌다.
   * 상품 재고는 여전히 전건을 받아 오므로 배열에서 센다.
   */
  const stats = useMemo(() => {
    const countOf = (status: string) =>
      orderSummary?.statuses.find((s) => s.status === status)?.count ?? 0;

    /*
      여기 있던 `totalRevenue: amountOf('PAID')` 를 뺐다.
      그 값은 <b>현재 상태가 PAID 인 주문</b>의 주문금액 합이라, 주문이 발송(IN_TRANSIT)되거나
      배송 완료(DELIVERED)되면 PAID 가 아니게 되면서 매출에서 빠졌다 — 장사가 굴러갈수록
      줄어드는 숫자였다. 환불도 차감이 아니라 다른 상태 칸으로 옮겨갈 뿐이라 그냥 사라졌고,
      결과는 늘 "그럴듯하게 작은" 값이라 아무도 이상하게 여기지 않았다.

      매출은 주문 상태가 아니라 결제 원장(수납 시각·환불 완료 시각)에 달린다 —
      `revenueAdminApi` 가 그 정의를 들고 있고, 그래서 기간이 반드시 붙는다.
    */
    return {
      totalOrders:    orderSummary?.totalCount ?? 0,
      paidCount:      countOf('PAID'),
      createdCount:   countOf('CREATED'),
      canceledCount:  countOf('CANCELED'),
      refundedCount:  countOf('REFUNDED'),
      lowStockCount:  products.filter((p) => p.stockQuantity > 0 && p.stockQuantity < 10).length,
      outOfStockCount: products.filter((p) => p.stockQuantity === 0).length,
    };
  }, [orderSummary, products]);

  /**
   * ── 필터된 목록 ──
   *
   * <p>상태 필터는 서버가 이미 걸었다. 여기 남은 것은 주문ID·회원ID 검색뿐이고, 그건
   * <b>지금 페이지 안에서만</b> 찾는다. 그 사실은 화면에 적어 둔다 — 안 적으면 "없습니다"가
   * "존재하지 않는다"로 읽힌다.
   */
  const filteredOrders = useMemo(() =>
    orders.filter((o) =>
      orderSearch === '' || String(o.id).includes(orderSearch) || String(o.userId).includes(orderSearch)
    ), [orders, orderSearch]);

  const filteredProducts = useMemo(() =>
    products.filter((p) =>
      (productStatusFilter === 'ALL' || p.status === productStatusFilter) &&
      (productSearch === '' || p.name.toLowerCase().includes(productSearch.toLowerCase()))
    ), [products, productStatusFilter, productSearch]);

  const filteredUsers = useMemo(() =>
    users.filter((u) =>
      (userRoleFilter === 'ALL' || u.role === userRoleFilter) &&
      (userSearch === '' || u.email.toLowerCase().includes(userSearch.toLowerCase()))
    ), [users, userRoleFilter, userSearch]);

  const handleCancelOrder = async (orderId: number) => {
    if (!window.confirm(`주문 #${orderId}을 취소하시겠습니까?`)) return;
    setCancellingId(orderId);
    try {
      const updated = await orderApi.cancelOrder(orderId);
      setOrders((prev) => prev.map((o) => (o.id === orderId ? updated : o)));
      // 집계는 서버가 세므로 여기서 손으로 고치지 않고 다시 묻는다. 손으로 고치면
      // 카드와 목록이 갈라지고, 갈라진 쪽이 어느 쪽인지 화면에서는 알 수 없다.
      adminApi.getOrderSummary().then(setOrderSummary).catch(() => {});
    } catch {
      alert('주문 취소에 실패했습니다.');
    } finally {
      setCancellingId(null);
    }
  };

  const handleCreateCoupon = async (e: React.FormEvent) => {
    e.preventDefault();
    setCouponError(null);
    setCouponSuccess(null);
    setCreatingCoupon(true);
    try {
      const created = await couponApi.create(couponForm);
      setCoupons((prev) => [created, ...prev]);
      setCouponSuccess(`쿠폰 "${created.code}" 생성 완료!`);
      setCouponForm({ code: '', type: 'PERCENTAGE', discountValue: 10, minOrderAmount: 0, maxUses: 100 });
    } catch (err) {
      setCouponError(apiErrorMessage(err, '쿠폰 생성에 실패했습니다.'));
    } finally {
      setCreatingCoupon(false);
    }
  };

  const ALL_TABS: { id: Tab; label: string; icon: string; adminOnly?: boolean }[] = [
    { id: 'overview',  label: '개요',      icon: '📊' },
    { id: 'orders',    label: '주문 관리',  icon: '📦' },
    { id: 'products',  label: '상품 관리',  icon: '🛍️' },
    { id: 'users',     label: '회원 관리',  icon: '👥', adminOnly: true },
    { id: 'coupons',   label: '쿠폰 관리',  icon: '🎟️', adminOnly: true },
  ];
  const TABS = ALL_TABS.filter((t) => !t.adminOnly || isAdmin);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Spinner size="lg" message="관리자 데이터 로드 중..." />
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <p className="text-red-600">{error}</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto">

        {/* 헤더 */}
        <div className="mb-8">
          <div>
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-3xl font-bold text-gray-900">
                {isAdmin ? '관리자 대시보드' : '매니저 대시보드'}
              </h1>
              <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${
                isAdmin ? 'bg-red-100 text-red-700' : 'bg-purple-100 text-purple-700'
              }`}>
                {currentUser?.role}
              </span>
            </div>
            <p className="text-sm text-gray-500">
              주문 {orders.length}건 · 상품 {products.length}개
              {isAdmin && ` · 회원 ${users.length}명`}
            </p>
          </div>
        </div>

        {/* 탭 */}
        <div className="flex bg-white rounded-xl shadow-sm border border-gray-200 mb-6 p-1 gap-1">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 py-2.5 text-sm font-semibold rounded-lg transition-all ${
                activeTab === tab.id
                  ? 'bg-gray-900 text-white shadow'
                  : 'text-gray-500 hover:text-gray-800 hover:bg-gray-50'
              }`}
            >
              {tab.icon} {tab.label}
            </button>
          ))}
        </div>

        {/* ── 개요 탭 ─────────────────────────────────────────────────────── */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/*
              오늘 한눈에 — 서버가 이벤트로 집계한 하루치.
              아래 "전체 기간" 카드들과 데이터 출처도 기간도 다르므로 섹션을 갈라 둔다.
            */}
            {isAdmin && (
              <section>
                <div className="flex items-baseline justify-between mb-3">
                  <h2 className="font-bold text-gray-900">
                    오늘 한눈에
                    {today && (
                      <span className="ml-2 text-xs font-normal text-gray-400">
                        {today.date} · {today.zone}
                      </span>
                    )}
                  </h2>
                  {/*
                    기준 시각을 숨기지 않는다. 이벤트로 채워지는 화면은 항상 조금 늦는데,
                    그 지연이 안 보이면 방금 들어온 주문이 없을 때 사람이 시스템을 의심한다.
                    오늘 이벤트가 아직 하나도 없으면 asOf 가 null 이다 — 이때 다른 시각을
                    대신 보여 주는 것이 제일 나쁜 거짓말이라 그냥 비워 둔다.
                  */}
                  {today?.asOf && (
                    <span className="text-xs text-gray-400">{fmtTime(today.asOf)} 기준</span>
                  )}
                </div>

                {todayError && <p className="text-sm text-red-600">{todayError}</p>}

                {!todayError && !today && (
                  <p className="text-sm text-gray-400">집계를 불러오는 중…</p>
                )}

                {today && (
                  <>
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                      {today.metrics.map((card) => (
                        <TodayCard key={card.key} card={card} />
                      ))}
                    </div>
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <StatCard
                        label="미해결 인시던트"
                        value={today.openIncidents.toLocaleString()}
                        sub="OPEN · 확인됨"
                        color={today.openIncidents > 0 ? 'bg-red-50' : 'bg-gray-50'}
                        icon={<svg className="w-5 h-5 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 9v2m0 4h.01M5 19h14a2 2 0 001.84-2.75L13.74 4a2 2 0 00-3.5 0l-7.1 12.25A2 2 0 004.99 19z"/></svg>}
                      />
                      <StatCard
                        label="오늘 실패한 알림"
                        value={today.failedDispatches.toLocaleString()}
                        sub="실패 · 일부 실패"
                        color={today.failedDispatches > 0 ? 'bg-amber-50' : 'bg-gray-50'}
                        icon={<svg className="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2c0 .5-.2 1-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"/></svg>}
                      />
                    </div>
                  </>
                )}
              </section>
            )}

            {/*
              아래는 전 기간 누계다. 주문 쪽 숫자는 서버가 GROUP BY 로 세어 준 값이다 —
              예전에는 브라우저가 전 주문을 받아 세었고, 그건 데이터가 늘면 같이 느려지다
              언젠가 죽는 구조였다. 라벨에 기간을 박는 것은 필수다 — 기간이 다른 숫자가
              라벨 없이 나란히 있으면 반드시 오독된다.
            */}
            {/*
              ── 매출 ──
              주문 상태가 아니라 결제 원장에 달린 숫자다. 그래서 기간이 반드시 붙는다 —
              "전 기간 매출"은 이 정의로 만들 수 없고, 만들 수 있는 척하는 것이 옛 카드의
              문제였다.
            */}
            <section className="space-y-4">
              <div className="flex items-baseline justify-between">
                <h2 className="font-bold text-gray-900">매출</h2>
                <span className="text-xs text-gray-400">
                  {revenue ? `${revenue.from} ~ ${revenue.to}` : `최근 ${REVENUE_DAYS}일`}
                </span>
              </div>

              {revenueError && <p className="text-sm text-red-600">{revenueError}</p>}
              {!revenueError && !revenue && (
                <p className="text-sm text-gray-400">매출 집계를 불러오는 중…</p>
              )}

              {revenue && (
                <>
                  <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                    {/*
                      순매출을 먼저 놓는다. 수납액만 크게 띄우면 환불이 눈에 안 들어오고,
                      그러면 "매출은 늘었는데 왜 돈이 없지"가 된다.
                      환불이 수납을 넘긴 기간은 음수다 — 숨기지 않고 그대로 적는다.
                    */}
                    <StatCard
                      label={`순매출 (최근 ${REVENUE_DAYS}일)`}
                      value={fmt(revenue.netAmount)}
                      sub="수납액 − 환불액"
                      color={revenue.netAmount < 0 ? 'bg-red-50' : 'bg-green-50'}
                      icon={<svg className="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>}
                    />
                    <StatCard
                      label="수납액"
                      value={fmt(revenue.capturedAmount)}
                      sub="실제로 결제가 잡힌 금액"
                      color="bg-blue-50"
                      icon={<svg className="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z"/></svg>}
                    />
                    <StatCard
                      label="환불액"
                      value={fmt(revenue.refundedAmount)}
                      sub="환불이 완료된 날 기준"
                      color={revenue.refundedAmount > 0 ? 'bg-amber-50' : 'bg-gray-50'}
                      icon={<svg className="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3 10h10a5 5 0 015 5v1M3 10l4-4M3 10l4 4"/></svg>}
                    />
                  </div>

                  <div className="bg-white rounded-xl border border-gray-200 p-5">
                    <h3 className="font-bold text-gray-900 mb-4">결제수단 구성</h3>

                    {revenue.byTender.length === 0 && (
                      <p className="text-sm text-gray-400">이 기간에 집계된 결제수단이 없습니다.</p>
                    )}

                    <div className="space-y-2">
                      {revenue.byTender.map((t) => (
                        <div key={t.tenderType} className="flex items-center justify-between text-sm">
                          <span className="text-gray-700">
                            {TENDER_LABEL[t.tenderType] ?? t.tenderType}
                            {/*
                              포인트·상품권은 이 기간에 새로 들어온 현금이 아니다 — 상품권은
                              팔릴 때 이미 한 번 수납됐다. 카드와 같은 줄에 두되 표시로 갈라
                              놓지 않으면 합쳐 읽는 순간 그만큼 이중으로 센다.
                            */}
                            {!t.usesExternalPg && (
                              <span className="ml-2 text-xs text-gray-400">내부 잔액</span>
                            )}
                          </span>
                          <span className="text-gray-900 font-medium">
                            {fmt(t.amount)}
                            <span className="ml-2 text-xs text-gray-400">{t.count.toLocaleString()}건</span>
                          </span>
                        </div>
                      ))}

                      {/*
                        분할결제 도입 전 결제에는 수단 행이 없다. 이 줄을 빼면 구성 비율만
                        그럴듯하게 남고 합이 수납액에 못 미치는 것을 볼 사람이 없어진다.
                      */}
                      {!revenue.tenderBreakdownComplete && (
                        <div className="flex items-center justify-between text-sm border-t border-gray-100 pt-2 mt-2">
                          <span className="text-amber-600">수단 미상</span>
                          <span className="text-amber-600 font-medium">
                            {fmt(revenue.unattributedAmount)}
                          </span>
                        </div>
                      )}
                    </div>

                    {!revenue.tenderBreakdownComplete && (
                      <p className="text-xs text-amber-600 mt-3">
                        분할결제 도입 전 결제에는 수단 기록이 없어 수납액 전체를 설명하지 못합니다.
                      </p>
                    )}
                  </div>
                </>
              )}
            </section>

            <h2 className="font-bold text-gray-900">전체 기간</h2>
            {/* 통계 카드 */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
              <StatCard
                label="총 주문"
                value={stats.totalOrders.toLocaleString()}
                sub={`결제완료 ${stats.paidCount}건`}
                color="bg-blue-50"
                icon={<svg className="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z"/></svg>}
              />
              {/*
                "총 매출" 카드는 여기서 뺐다 — 이 묶음은 전 기간 누계인데 매출만은 전 기간으로
                셀 수 없기 때문이다. 결제 원장 기준 매출은 위 "매출" 섹션이 기간을 붙여 보여 준다.
                옛 카드는 상태가 PAID 인 주문의 주문금액 합이라 발송된 주문이 빠지고 환불이
                차감되지 않았다(stats useMemo 주석 참조).
              */}
              <StatCard
                label="총 회원"
                value={users.length.toLocaleString()}
                sub={`관리자 ${users.filter(u => u.role === 'ADMIN').length}명`}
                color="bg-purple-50"
                icon={<svg className="w-5 h-5 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0"/></svg>}
              />
              <StatCard
                label="총 상품"
                value={products.length.toLocaleString()}
                sub={`재고부족 ${stats.lowStockCount}개 · 품절 ${stats.outOfStockCount}개`}
                color="bg-orange-50"
                icon={<svg className="w-5 h-5 text-orange-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"/></svg>}
              />
            </div>

            {/* 주문 상태 분포 */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div className="bg-white rounded-xl border border-gray-200 p-5">
                <h3 className="font-bold text-gray-900 mb-4">주문 상태 현황</h3>
                <div className="space-y-3">
                  {[
                    { status: 'CREATED',  label: '주문완료',  cls: 'bg-yellow-400', count: stats.createdCount },
                    { status: 'PAID',     label: '결제완료',  cls: 'bg-green-400',  count: stats.paidCount },
                    { status: 'CANCELED', label: '취소됨',    cls: 'bg-red-400',    count: stats.canceledCount },
                    { status: 'REFUNDED', label: '환불됨',    cls: 'bg-purple-400', count: stats.refundedCount },
                  ].map(({ label, cls, count }) => (
                    <div key={label} className="flex items-center gap-3">
                      <div className={`w-3 h-3 rounded-full ${cls}`} />
                      <span className="text-sm text-gray-600 w-20">{label}</span>
                      <div className="flex-1 bg-gray-100 rounded-full h-2">
                        <div
                          className={`${cls} h-2 rounded-full transition-all`}
                          style={{ width: stats.totalOrders ? `${(count / stats.totalOrders) * 100}%` : '0%' }}
                        />
                      </div>
                      <span className="text-sm font-semibold text-gray-800 w-12 text-right">{count}건</span>
                    </div>
                  ))}
                </div>
              </div>

              {/* 최근 주문 5건 */}
              <div className="bg-white rounded-xl border border-gray-200 p-5">
                <h3 className="font-bold text-gray-900 mb-4">최근 주문</h3>
                <div className="space-y-2">
                  {recentOrders.map((order) => (
                    <div key={order.id} className="flex items-center justify-between py-1.5">
                      <div>
                        <p className="text-sm font-medium text-gray-900">주문 #{order.id}</p>
                        <p className="text-xs text-gray-400">사용자 #{order.userId} · {fmtDate(order.createdAt)}</p>
                      </div>
                      <div className="flex items-center gap-2">
                        <OrderStatusBadge status={order.status} />
                        <span className="text-sm font-bold text-gray-800">{fmt(order.amount)}</span>
                      </div>
                    </div>
                  ))}
                </div>
                <button onClick={() => setActiveTab('orders')}
                  className="w-full mt-3 text-center text-sm text-blue-600 hover:text-blue-800 font-medium py-2 rounded-lg hover:bg-blue-50 transition-colors">
                  전체 주문 보기 →
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── 주문 관리 탭 ─────────────────────────────────────────────────── */}
        {activeTab === 'orders' && (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            {/* 필터 바 */}
            <div className="p-4 border-b border-gray-100 flex flex-wrap gap-3 items-center">
              <input
                type="text"
                placeholder="주문ID / 회원ID 검색"
                value={orderSearch}
                onChange={(e) => setOrderSearch(e.target.value)}
                className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm w-44 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              />
              <div className="flex gap-1.5">
                {['ALL', 'CREATED', 'PAID', 'CANCELED', 'REFUNDED'].map((s) => (
                  <button
                    key={s}
                    onClick={() => {
                      // 필터를 바꾸면 페이지를 처음으로 되돌린다. 3쪽에 머문 채 필터만
                      // 바꾸면 결과가 1쪽뿐일 때 빈 화면이 뜨고, 그건 "그런 주문이 없다"로
                      // 읽힌다.
                      setOrderStatusFilter(s);
                      setOrderPageIndex(0);
                    }}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
                      orderStatusFilter === s
                        ? 'bg-gray-900 text-white'
                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                    }`}
                  >
                    {s === 'ALL' ? '전체' : { CREATED:'주문완료', PAID:'결제완료', CANCELED:'취소됨', REFUNDED:'환불됨' }[s]}
                  </button>
                ))}
              </div>
              {/* 페이지 건수가 아니라 조건에 맞는 전체 건수를 먼저 말한다. */}
              <span className="text-sm text-gray-400 ml-auto">
                전체 {orderTotal.toLocaleString()}건 중 {orders.length.toLocaleString()}건 표시
              </span>
            </div>

            {/* 테이블 */}
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['주문 ID', '회원 ID', '상품 ID', '금액', '상태', '주문일', ''].map((h) => (
                      <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filteredOrders.map((order) => (
                    <tr key={order.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 font-mono text-gray-700">#{order.id}</td>
                      <td className="px-4 py-3 text-gray-600">#{order.userId}</td>
                      <td className="px-4 py-3 text-gray-600">#{order.productId ?? '-'}</td>
                      <td className="px-4 py-3 font-semibold text-gray-900">{fmt(order.amount)}</td>
                      <td className="px-4 py-3"><OrderStatusBadge status={order.status} /></td>
                      <td className="px-4 py-3 text-gray-400">{fmtDate(order.createdAt)}</td>
                      <td className="px-4 py-3">
                        {order.status === 'CREATED' && (
                          <button
                            onClick={() => handleCancelOrder(order.id)}
                            disabled={cancellingId === order.id}
                            className="text-xs text-red-500 hover:text-red-700 font-medium disabled:opacity-40"
                          >
                            {cancellingId === order.id ? '처리중...' : '취소'}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {filteredOrders.length === 0 && !ordersLoading && (
                <p className="text-center text-gray-400 py-10">
                  {orderSearch === ''
                    ? '조건에 맞는 주문이 없습니다.'
                    : `이 페이지에는 "${orderSearch}" 와 맞는 주문이 없습니다.`}
                </p>
              )}
            </div>

            {/*
              검색은 지금 페이지 안에서만 돈다. 여러 쪽이 있는데 그 사실을 안 적으면
              "없습니다"가 "존재하지 않는다"로 읽히고, 운영자는 다음 쪽을 볼 생각을 못 한다.
            */}
            {orderSearch !== '' && orderTotalPages > 1 && (
              <p className="px-4 py-2 text-xs text-amber-600 border-t border-gray-100">
                검색은 현재 페이지({orderPageIndex + 1}/{orderTotalPages}쪽) 안에서만 찾습니다.
              </p>
            )}

            {/* 페이지 이동 */}
            {orderTotalPages > 1 && (
              <div className="flex items-center justify-center gap-3 p-4 border-t border-gray-100">
                <button
                  onClick={() => setOrderPageIndex((p) => Math.max(p - 1, 0))}
                  disabled={orderPageIndex === 0 || ordersLoading}
                  className="px-3 py-1.5 text-sm rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 disabled:opacity-40"
                >
                  이전
                </button>
                <span className="text-sm text-gray-500">
                  {orderPageIndex + 1} / {orderTotalPages}
                </span>
                <button
                  onClick={() => setOrderPageIndex((p) => Math.min(p + 1, orderTotalPages - 1))}
                  disabled={orderPageIndex >= orderTotalPages - 1 || ordersLoading}
                  className="px-3 py-1.5 text-sm rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 disabled:opacity-40"
                >
                  다음
                </button>
              </div>
            )}
          </div>
        )}

        {/* ── 상품 관리 탭 ─────────────────────────────────────────────────── */}
        {activeTab === 'products' && (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <div className="p-4 border-b border-gray-100 flex flex-wrap gap-3 items-center">
              <input
                type="text"
                placeholder="상품명 검색"
                value={productSearch}
                onChange={(e) => setProductSearch(e.target.value)}
                className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm w-44 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              />
              <div className="flex gap-1.5">
                {['ALL', 'ACTIVE', 'INACTIVE', 'OUT_OF_STOCK', 'DISCONTINUED'].map((s) => (
                  <button
                    key={s}
                    onClick={() => setProductStatusFilter(s)}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
                      productStatusFilter === s
                        ? 'bg-gray-900 text-white'
                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                    }`}
                  >
                    {s === 'ALL' ? '전체' : { ACTIVE:'판매중', INACTIVE:'비활성', OUT_OF_STOCK:'품절', DISCONTINUED:'단종' }[s]}
                  </button>
                ))}
              </div>
              <span className="text-sm text-gray-400 ml-auto">{filteredProducts.length}개</span>
              <Link to="/product"
                className="px-3 py-1.5 bg-blue-600 text-white text-xs font-semibold rounded-lg hover:bg-blue-700 transition-colors">
                상세 관리 →
              </Link>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['ID', '이미지', '상품명', '가격', '재고', '상태', '등록일'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filteredProducts.map((product) => (
                    <tr key={product.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 font-mono text-gray-500">#{product.id}</td>
                      <td className="px-4 py-3">
                        {product.primaryImageUrl ? (
                          <img src={product.primaryImageUrl} alt={product.name}
                            className="w-9 h-9 rounded-lg object-cover" />
                        ) : (
                          <div className="w-9 h-9 rounded-lg bg-gray-100 flex items-center justify-center">
                            <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
                            </svg>
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <p className="font-medium text-gray-900">{product.name}</p>
                        {product.description && (
                          <p className="text-xs text-gray-400 truncate max-w-[200px]">{product.description}</p>
                        )}
                      </td>
                      <td className="px-4 py-3 font-semibold text-gray-900">{fmt(product.price)}</td>
                      <td className="px-4 py-3">
                        <span className={`font-semibold ${
                          product.stockQuantity === 0 ? 'text-red-600'
                          : product.stockQuantity < 10 ? 'text-orange-500'
                          : 'text-gray-800'
                        }`}>
                          {product.stockQuantity}
                        </span>
                      </td>
                      <td className="px-4 py-3"><ProductStatusBadge status={product.status} /></td>
                      <td className="px-4 py-3 text-gray-400">{fmtDate(product.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {filteredProducts.length === 0 && (
                <p className="text-center text-gray-400 py-10">조건에 맞는 상품이 없습니다.</p>
              )}
            </div>
          </div>
        )}

        {/* ── 회원 관리 탭 ─────────────────────────────────────────────────── */}
        {activeTab === 'users' && (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <div className="p-4 border-b border-gray-100 flex flex-wrap gap-3 items-center">
              <input
                type="text"
                placeholder="이메일 검색"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm w-52 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              />
              <div className="flex gap-1.5">
                {['ALL', 'USER', 'ADMIN', 'MANAGER'].map((r) => (
                  <button
                    key={r}
                    onClick={() => setUserRoleFilter(r)}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
                      userRoleFilter === r
                        ? 'bg-gray-900 text-white'
                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                    }`}
                  >
                    {r === 'ALL' ? '전체' : r}
                  </button>
                ))}
              </div>
              <span className="text-sm text-gray-400 ml-auto">{filteredUsers.length}명</span>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['ID', '이메일', '역할', '가입일'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filteredUsers.map((user) => (
                    <tr key={user.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 font-mono text-gray-500">#{user.id}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <div className="w-7 h-7 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <svg className="w-4 h-4 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                                d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                            </svg>
                          </div>
                          <span className="font-medium text-gray-900">{user.email}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3"><RoleBadge role={user.role} /></td>
                      <td className="px-4 py-3 text-gray-400">{fmtDate(user.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {filteredUsers.length === 0 && (
                <p className="text-center text-gray-400 py-10">조건에 맞는 회원이 없습니다.</p>
              )}
            </div>
          </div>
        )}

        {/* ── 쿠폰 관리 탭 ─────────────────────────────────────────────────── */}
        {activeTab === 'coupons' && (
          <div className="space-y-6">
            {/* 쿠폰 생성 폼 */}
            <div className="bg-white rounded-xl border border-gray-200 p-6">
              <h3 className="font-bold text-gray-900 mb-4">쿠폰 생성</h3>
              <form onSubmit={handleCreateCoupon} className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">쿠폰 코드</label>
                  <input
                    type="text"
                    required
                    value={couponForm.code}
                    onChange={(e) => setCouponForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))}
                    placeholder="예: SUMMER20"
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">쿠폰 타입</label>
                  <select
                    value={couponForm.type}
                    onChange={(e) => setCouponForm((f) => ({ ...f, type: e.target.value as CouponType }))}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500"
                  >
                    <option value="PERCENTAGE">정률 할인 (%)</option>
                    <option value="FIXED">정액 할인 (원)</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    할인 값 ({couponForm.type === 'PERCENTAGE' ? '%' : '원'})
                  </label>
                  <input
                    type="number"
                    required
                    min={1}
                    max={couponForm.type === 'PERCENTAGE' ? 100 : undefined}
                    value={couponForm.discountValue}
                    onChange={(e) => setCouponForm((f) => ({ ...f, discountValue: Number(e.target.value) }))}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">최소 주문 금액 (원)</label>
                  <input
                    type="number"
                    min={0}
                    value={couponForm.minOrderAmount}
                    onChange={(e) => setCouponForm((f) => ({ ...f, minOrderAmount: Number(e.target.value) }))}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">최대 사용 횟수</label>
                  <input
                    type="number"
                    required
                    min={1}
                    value={couponForm.maxUses}
                    onChange={(e) => setCouponForm((f) => ({ ...f, maxUses: Number(e.target.value) }))}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">만료일 (선택)</label>
                  <input
                    type="datetime-local"
                    value={couponForm.expiresAt ?? ''}
                    onChange={(e) => setCouponForm((f) => ({ ...f, expiresAt: e.target.value || undefined }))}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div className="md:col-span-2 lg:col-span-3 flex items-center gap-4">
                  <button
                    type="submit"
                    disabled={creatingCoupon}
                    className="px-6 py-2.5 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
                  >
                    {creatingCoupon ? '생성 중...' : '쿠폰 생성'}
                  </button>
                  {couponSuccess && <p className="text-sm text-green-600 font-medium">{couponSuccess}</p>}
                  {couponError   && <p className="text-sm text-red-600">{couponError}</p>}
                </div>
              </form>
            </div>

            {/* 쿠폰 목록 */}
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              <div className="p-4 border-b border-gray-100 flex items-center justify-between">
                <h3 className="font-bold text-gray-900">쿠폰 목록</h3>
                <span className="text-sm text-gray-400">{coupons.length}개</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      {['코드', '타입', '할인', '최소금액', '사용', '만료일', '상태'].map((h) => (
                        <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {coupons.map((c) => (
                      <tr key={c.id} className="hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3 font-mono font-bold text-blue-700">{c.code}</td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                            c.type === 'PERCENTAGE' ? 'bg-blue-100 text-blue-800' : 'bg-green-100 text-green-800'
                          }`}>
                            {c.type === 'PERCENTAGE' ? '정률' : '정액'}
                          </span>
                        </td>
                        <td className="px-4 py-3 font-semibold text-gray-900">
                          {c.type === 'PERCENTAGE' ? `${c.discountValue}%` : fmt(c.discountValue)}
                        </td>
                        <td className="px-4 py-3 text-gray-600">
                          {c.minOrderAmount > 0 ? fmt(c.minOrderAmount) : '-'}
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-gray-700">{c.usedCount}</span>
                          <span className="text-gray-400"> / {c.maxUses}</span>
                        </td>
                        <td className="px-4 py-3 text-gray-400">
                          {c.expiresAt ? new Date(c.expiresAt).toLocaleDateString('ko-KR') : '무기한'}
                        </td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                            c.isActive ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
                          }`}>
                            {c.isActive ? '활성' : '비활성'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {coupons.length === 0 && (
                  <p className="text-center text-gray-400 py-10">쿠폰이 없습니다. 위 폼에서 생성하세요.</p>
                )}
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
};

export default AdminDashboardPage;
