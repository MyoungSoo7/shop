import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authApi } from './api/auth';
import { ToastProvider } from './contexts/ToastContext';
import { AuthProvider } from './contexts/AuthContext';
import { MenuProvider } from './contexts/MenuContext';
import { CartProvider } from './contexts/CartContext';
import Layout from './components/Layout';
import SideNavLayout from './components/SideNavLayout';

// 공개 페이지 (즉시 로드)
import Login from './pages/Login';
import AdminLoginPage from './pages/AdminLoginPage';
import Register from './pages/Register';
import TossPaymentFail from './pages/TossPaymentFail';

// 인증 필요 페이지 (lazy load)
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const GiftClaimPage = lazy(() => import('./pages/GiftClaimPage'));
const OrderPage = lazy(() => import('./pages/OrderPage'));
const RecommendPage = lazy(() => import('./pages/RecommendPage'));
const CartPage = lazy(() => import('./pages/CartPage'));
const MyPage = lazy(() => import('./pages/MyPage'));
const MyBalancesPage = lazy(() => import('./pages/MyBalancesPage'));
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'));
const BulkOrderPage = lazy(() => import('./pages/BulkOrderPage'));
const TenderCheckoutPage = lazy(() => import('./pages/TenderCheckoutPage'));
const TossPaymentSuccess = lazy(() => import('./pages/TossPaymentSuccess'));
const RefundAdminPage = lazy(() => import('./pages/RefundAdminPage'));
const EducationCourseAdminPage = lazy(() => import('./pages/system/EducationCourseAdminPage'));
const EducationEnrollmentPage = lazy(() => import('./pages/system/EducationEnrollmentPage'));
const EducationLecturerPage = lazy(() => import('./pages/system/EducationLecturerPage'));
const SitePopupPage = lazy(() => import('./pages/system/SitePopupPage'));
const CommentModerationPage = lazy(() => import('./pages/system/CommentModerationPage'));
const PointConsolePage = lazy(() => import('./pages/system/PointConsolePage'));
const GiftCardConsolePage = lazy(() => import('./pages/system/GiftCardConsolePage'));
const AuditLogConsolePage = lazy(() => import('./pages/system/AuditLogConsolePage'));
const MemberAdminPage = lazy(() => import('./pages/system/MemberAdminPage'));
const OrganizationConsolePage = lazy(() => import('./pages/system/OrganizationConsolePage'));
const ReviewAdminPage = lazy(() => import('./pages/system/ReviewAdminPage'));
const CouponAdminPage = lazy(() => import('./pages/system/CouponAdminPage'));
const OperatorAdminPage = lazy(() => import('./pages/system/OperatorAdminPage'));
const MetricTrendPage = lazy(() => import('./pages/system/MetricTrendPage'));
const SalesStatsPage = lazy(() => import('./pages/system/SalesStatsPage'));
const OrderQueuePage = lazy(() => import('./pages/system/OrderQueuePage'));

// 관리자 페이지 (lazy load)
const ProductPage = lazy(() => import('./pages/ProductPage'));
const TagManagementPage = lazy(() => import('./pages/TagManagementPage'));
const EcommerceCategoryAdmin = lazy(() => import('./pages/EcommerceCategoryAdmin'));
const DisplaySectionAdminPage = lazy(() => import('./pages/DisplaySectionAdminPage'));
const OptionCatalogAdminPage = lazy(() => import('./pages/OptionCatalogAdminPage'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));
const ShippingAdminPage = lazy(() => import('./pages/ShippingAdminPage'));
const ShippingPolicyAdminPage = lazy(() => import('./pages/ShippingPolicyAdminPage'));
const OrderApprovalPage = lazy(() => import('./pages/OrderApprovalPage'));
const ReturnRequestAdminPage = lazy(() => import('./pages/ReturnRequestAdminPage'));
const SellerTierAdminPage = lazy(() => import('./pages/SellerTierAdminPage'));

// 운영 관제 (최고 관리자 전용) — operation-service 인시던트 콘솔
const OperationConsolePage = lazy(() => import('./pages/operation/OperationConsolePage'));

// 시스템 관리 (최고 관리자 전용, 좌측 사이드바)
const MenuManagementPage = lazy(() => import('./pages/system/MenuManagementPage'));
const CommonCodeManagementPage = lazy(() => import('./pages/system/CommonCodeManagementPage'));
const RbacManagementPage = lazy(() => import('./pages/system/RbacManagementPage'));
const BoardAdminPage = lazy(() => import('./pages/system/BoardAdminPage'));
const BoardPage = lazy(() => import('./pages/board/BoardPage'));
const BoardPostPage = lazy(() => import('./pages/board/BoardPostPage'));

// ── 일반 사용자용 (인증 필수, 역할 무관) ──────────────────────────────────
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <Layout>{children}</Layout>;
};

// ── 관리자·매니저 공용 (/admin, /product, /admin/shipping 등) ──────────────
const AdminManagerRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const user = authApi.getCurrentUser();
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/admin/login" replace />;
  }
  if (user?.role !== 'ADMIN' && user?.role !== 'MANAGER') {
    return <Navigate to="/login" replace />;
  }
  return <Layout>{children}</Layout>;
};

// ── 최고 관리자 전용 (/admin/system/**, 회원관리 등) ──────────────────────
const AdminOnlyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const user = authApi.getCurrentUser();
  if (!authApi.isAuthenticated()) {
    return <Navigate to="/admin/login" replace />;
  }
  if (user?.role !== 'ADMIN') {
    return <Navigate to="/admin" replace />;
  }
  return <Layout>{children}</Layout>;
};

function App() {
  return (
    <ToastProvider>
      <AuthProvider>
      <MenuProvider>
      <CartProvider>
        <BrowserRouter>
          <Suspense fallback={<div className="flex justify-center items-center min-h-screen"><div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div></div>}>
          <Routes>

            {/* ── 공개 (인증 불필요) ── */}
            <Route path="/"                   element={<Navigate to="/login" replace />} />
            <Route path="/login"              element={<Login />} />
            <Route path="/admin/login"        element={<AdminLoginPage />} />
            <Route path="/register"           element={<Register />} />
            <Route path="/forgot-password"    element={<ForgotPassword />} />
            <Route path="/reset-password"     element={<ResetPassword />} />
            <Route path="/order/toss/fail"    element={<TossPaymentFail />} />
            {/* 선물 받기 — 받는 사람은 이 가게의 회원이 아니다. 여기에 로그인을 걸면 "주소를 주기
                싫어서" 못 받던 문제가 "가입하기 싫어서"로 이름만 바뀐 채 그대로 남는다. 서버도 이
                경로(/gift-claims/**)만 permitAll 이다. 토큰이 곧 인가라서 다음 화면(배송지 입력)은
                6자리 본인확인을 한 번 더 통과해야 열린다. */}
            <Route path="/gift/:token"        element={<GiftClaimPage />} />
            {/* 게시판 — 라우트는 이 둘이 전부다. 어느 게시판인지는 :boardKey 가 정하고, 무엇이 보이는지는
                서버가 정의(공개 여부·역할)로 판정한다. 게시판을 새로 만들어도 라우트는 늘지 않는다.
                메뉴 진입점은 관리자가 /admin/system/boards 에서 붙인다(런타임 생성이라 시드에 없다). */}
            <Route path="/boards/:boardKey"           element={<Layout><BoardPage /></Layout>} />
            <Route path="/boards/:boardKey/:postId"   element={<Layout><BoardPostPage /></Layout>} />

            {/* ── 일반 사용자 (USER + 인증) ── */}
            <Route path="/order"        element={<ProtectedRoute><OrderPage /></ProtectedRoute>} />
            <Route path="/recommend"    element={<ProtectedRoute><RecommendPage /></ProtectedRoute>} />
            <Route path="/cart"         element={<ProtectedRoute><CartPage /></ProtectedRoute>} />
            <Route path="/mypage"       element={<ProtectedRoute><MyPage /></ProtectedRoute>} />
            <Route path="/my/balances" element={<ProtectedRoute><MyBalancesPage /></ProtectedRoute>} />
            {/* 알림 푸시 SSE 구독 — 수신함이 아니라 스트림이다(서버가 알림을 저장하지 않는다). */}
            <Route path="/notifications" element={<ProtectedRoute><NotificationsPage /></ProtectedRoute>} />
            {/* 대량주문 — 올리는 것과 주문이 나가는 것이 다른 버튼이다(검증/확정 분리). */}
            <Route path="/order/bulk"   element={<ProtectedRoute><BulkOrderPage /></ProtectedRoute>} />
            <Route path="/order/pay"    element={<ProtectedRoute><TenderCheckoutPage /></ProtectedRoute>} />
            <Route path="/order/toss/success" element={<ProtectedRoute><TossPaymentSuccess /></ProtectedRoute>} />

            {/* ── 관리자·매니저 공용 ── */}
            <Route path="/admin"              element={<AdminManagerRoute><AdminDashboardPage /></AdminManagerRoute>} />
            {/* 좌측 사이드바 항목은 menus 테이블이 정한다(SideNavLayout 이 트리에서 그림) */}
            <Route path="/product"            element={<AdminManagerRoute><SideNavLayout><ProductPage /></SideNavLayout></AdminManagerRoute>} />

            {/* 배송 관리 — 주문별 배송 생성·출고·상태 전이 (ShippingController) */}
            <Route path="/admin/shipping"
              element={<AdminManagerRoute><SideNavLayout><ShippingAdminPage /></SideNavLayout></AdminManagerRoute>} />
            {/* 배송비 정책 — 고객이 실제로 지불하는 금액을 바꾼다. 서버가 /admin/shipping-policies/** 를
                ADMIN 으로 막으므로 MANAGER 에게 열면 눌러도 되돌려보내지는 죽은 링크가 된다.
                화면 URL 은 배송 그룹 아래(/admin/shipping/policies)다 — /admin/shipping-policies 는
                백엔드 API 가 쓰는 URL 이라, 화면이 같은 곳에 있으면 새로고침에서 API 응답이 뜬다. */}
            <Route path="/admin/shipping/policies"
              element={<AdminOnlyRoute><SideNavLayout><ShippingPolicyAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 취소·환불 승인 큐 — 사용자가 신청한 건을 운영자가 종단으로 보낸다 */}
            <Route path="/admin/approvals"    element={<AdminManagerRoute><OrderApprovalPage /></AdminManagerRoute>} />
            {/* 반품·교환 처리 — 승인 → 회수 확인 → 환불/재배송. 위 승인 큐와 나눈 이유는 신청 하나가
                단계마다 다른 버튼을 요구해서다. URL 이 /admin/return-requests 가 아닌 이유: 그건 이
                화면이 부르는 API 경로이고, 겹치면 새로고침 때 목록 JSON 이 렌더된다. 승인 그룹 아래로
                두면 nginx SPA 폴백(approvals 접두사)에 그대로 얹힌다. */}
            <Route path="/admin/approvals/returns"
              element={<AdminManagerRoute><ReturnRequestAdminPage /></AdminManagerRoute>} />
            <Route path="/tags"               element={<AdminManagerRoute><TagManagementPage /></AdminManagerRoute>} />

            {/* ── 최고 관리자 전용: 운영 관제 — 시스템 관리(운영관리)로 편입, 구 경로는 리다이렉트 ── */}
            <Route path="/admin/operation"
              element={<Navigate to="/admin/system/operation" replace />} />

            {/* ── 최고 관리자 전용: 시스템 관리 (좌측 사이드바) ──
                URL 이 /admin/system/** 아래인 이유: nginx SPA 폴백이 /admin 하위에서 네비 그룹
                접두사(system, operation, shipping, approvals, login)만 index.html 로 내려보낸다.
                다른 접두사를 쓰면 새로고침·직접진입이 404 가 된다. 백엔드 API 와 같은 URL 을
                화면에 쓰면 새로고침 때 API 응답이 렌더되므로, API 경로와 겹치지 않게 둔다. */}
            <Route path="/admin/system"
              element={<Navigate to="/admin/system/menus" replace />} />
            <Route path="/admin/system/menus"
              element={<AdminOnlyRoute><SideNavLayout><MenuManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/codes"
              element={<AdminOnlyRoute><SideNavLayout><CommonCodeManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/rbac"
              element={<AdminOnlyRoute><SideNavLayout><RbacManagementPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/boards"
              element={<AdminOnlyRoute><SideNavLayout><BoardAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 화면 URL 이 /admin/education/courses 가 아닌 이유: 그 URL 은 operation-service 의
                education 슬라이스 API 다. 시스템 관리 메뉴 아래 화면이므로 /admin/system/education 으로 둔다. */}
            <Route path="/admin/system/education"
              element={<AdminOnlyRoute><SideNavLayout><EducationCourseAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/education-enrollments"
              element={<AdminOnlyRoute><SideNavLayout><EducationEnrollmentPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/education-lecturers"
              element={<AdminOnlyRoute><SideNavLayout><EducationLecturerPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/site-popups"
              element={<AdminOnlyRoute><SideNavLayout><SitePopupPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/comment-moderation"
              element={<AdminOnlyRoute><SideNavLayout><CommentModerationPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/ecommerce-categories"
              element={<AdminOnlyRoute><SideNavLayout><EcommerceCategoryAdmin /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/display-sections"
              element={<AdminOnlyRoute><SideNavLayout><DisplaySectionAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/option-catalog"
              element={<AdminOnlyRoute><SideNavLayout><OptionCatalogAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/operation"
              element={<AdminOnlyRoute><SideNavLayout><OperationConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/points"
              element={<AdminOnlyRoute><SideNavLayout><PointConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/gift-cards"
              element={<AdminOnlyRoute><SideNavLayout><GiftCardConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/audit-logs"
              element={<AdminOnlyRoute><SideNavLayout><AuditLogConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/members"
              element={<AdminOnlyRoute><SideNavLayout><MemberAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 조직·멤버십 — 회원 관리 바로 옆이다(사람을 다루는 축이 같다). 서버는 authenticated
                만 요구하고 조직 내 역할로 인가하지만, 우리 앱의 SYSTEM 영역 관례를 따라 ADMIN. */}
            <Route path="/admin/system/organizations"
              element={<AdminOnlyRoute><SideNavLayout><OrganizationConsolePage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/reviews"
              element={<AdminOnlyRoute><SideNavLayout><ReviewAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            <Route path="/admin/system/coupons"
              element={<AdminOnlyRoute><SideNavLayout><CouponAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 환불 운영 — 화면 URL 이 /admin/refunds 가 아닌 이유: 그 URL 은 order-service 의 API 이고
                게이트웨이로 노출돼 있다. 화면이 같은 URL 을 쓰면 새로고침 때 API 응답이 렌더된다.
                서버가 /admin/refunds/** 를 ADMIN·MANAGER 로 막으므로 라우트도 그에 맞춘다. */}
            <Route path="/admin/system/refunds"
              element={<AdminManagerRoute><SideNavLayout><RefundAdminPage /></SideNavLayout></AdminManagerRoute>} />
            {/* 셀러 등급 — 서버 /admin/seller-tiers/** 가 ADMIN 전용이라 라우트도 같은 등급. */}
            <Route path="/admin/system/seller-tiers"
              element={<AdminOnlyRoute><SideNavLayout><SellerTierAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 권한 계정 — 서버 /admin/operators/** 가 ADMIN 전용. 화면 URL 을 그 API 와 같게 두면
                새로고침 때 명부 JSON 이 그대로 브라우저에 뜬다. */}
            <Route path="/admin/system/operators"
              element={<AdminOnlyRoute><SideNavLayout><OperatorAdminPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 지표 추이 — operation-service /api/ops/** 가 ADMIN 전용이라 라우트도 같은 등급. */}
            <Route path="/admin/system/trends"
              element={<AdminOnlyRoute><SideNavLayout><MetricTrendPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 판매 통계 — 서버 /admin/sales/** 가 ADMIN 전용. */}
            <Route path="/admin/system/sales-stats"
              element={<AdminOnlyRoute><SideNavLayout><SalesStatsPage /></SideNavLayout></AdminOnlyRoute>} />
            {/* 작업 큐 — 서버 /admin/order-queues 는 ADMIN·MANAGER 다. 밀린 주문을 실제로 처리하는
                쪽이 MANAGER 이므로 라우트도 그에 맞춘다. */}
            <Route path="/admin/system/order-queues"
              element={<AdminManagerRoute><SideNavLayout><OrderQueuePage /></SideNavLayout></AdminManagerRoute>} />

          </Routes>
          </Suspense>
        </BrowserRouter>
      </CartProvider>
      </MenuProvider>
      </AuthProvider>
    </ToastProvider>
  );
}

export default App;
