import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';

const QUICK_LOGINS = [
  { email: 'seed_admin@test.com',   password: 'password123', role: 'ADMIN',   redirect: '/admin' },
  { email: 'seed_manager@test.com', password: 'password123', role: 'MANAGER', redirect: '/admin' },
];

const StartPage: React.FC = () => {
  const navigate = useNavigate();
  const [loadingRole, setLoadingRole] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleQuickLogin = async (entry: typeof QUICK_LOGINS[0]) => {
    setLoadingRole(entry.role);
    setError(null);
    try {
      const response = await authApi.login({ email: entry.email, password: entry.password });
      authApi.saveToken(response);
      
      // ADMIN / MANAGER → 관리자 대시보드, USER → 주문 페이지
      if (response.role === 'ADMIN' || response.role === 'MANAGER') {
        navigate('/admin');
      } else {
        navigate('/order');
      }
    } catch {
      setError(`${entry.role} 계정 로그인에 실패했습니다. 서버가 실행 중인지 확인하세요.`);
    } finally {
      setLoadingRole(null);
    }
  };

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col items-center justify-center p-4">
      <div className="max-w-4xl w-full bg-white rounded-2xl shadow-xl overflow-hidden flex flex-col md:flex-row">

        {/* 왼쪽 브랜딩 */}
        <div className="md:w-1/3 bg-blue-600 p-12 text-white flex flex-col justify-center items-center text-center">
          <h1 className="text-5xl font-bold mb-4 italic">Lemuel</h1>
          <p className="text-xl opacity-80">혁신적인 정산 및 쇼핑 플랫폼</p>
          <div className="mt-12 w-24 h-1 bg-white opacity-30 rounded-full" />
        </div>

        {/* 오른쪽 선택 */}
        <div className="md:w-2/3 p-12 flex flex-col justify-center">
          <h2 className="text-3xl font-bold text-gray-800 mb-2">로그인</h2>
          <p className="text-sm text-gray-400 mb-8">역할을 선택하거나 로그인해 주세요.</p>

          {error && (
            <div className="mb-5 bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
              {error}
            </div>
          )}

          <div className="grid grid-cols-1 gap-4">

            {/* USER — 로그인 페이지로 이동 */}
            <button
              onClick={() => navigate('/login')}
              className="group flex items-center p-5 border-2 border-gray-200 rounded-xl hover:border-blue-500 hover:bg-blue-50 transition-all text-left"
            >
              <div className="w-14 h-14 bg-blue-100 rounded-lg flex items-center justify-center mr-5 group-hover:bg-blue-500 transition-colors flex-shrink-0">
                <svg className="w-7 h-7 text-blue-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                </svg>
              </div>
              <div>
                <h3 className="text-lg font-bold text-gray-800">쇼핑하기 (USER)</h3>
                <p className="text-sm text-gray-500">회원가입 후 로그인하여 상품 주문 및 결제를 진행합니다.</p>
              </div>
              <svg className="w-5 h-5 text-gray-400 ml-auto flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5l7 7-7 7" />
              </svg>
            </button>

            {/* MANAGER — 빠른 로그인 */}
            <button
              onClick={() => handleQuickLogin(QUICK_LOGINS[1])}
              disabled={loadingRole !== null}
              className="group flex items-center p-5 border-2 border-gray-200 rounded-xl hover:border-green-500 hover:bg-green-50 transition-all text-left disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <div className="w-14 h-14 bg-green-100 rounded-lg flex items-center justify-center mr-5 group-hover:bg-green-500 transition-colors flex-shrink-0">
                {loadingRole === 'MANAGER' ? (
                  <svg className="w-5 h-5 text-green-600 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                  </svg>
                ) : (
                  <svg className="w-7 h-7 text-green-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01" />
                  </svg>
                )}
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-lg font-bold text-gray-800">매니저 모드 (MANAGER)</h3>
                  <span className="text-xs bg-green-100 text-green-700 font-semibold px-2 py-0.5 rounded-full">빠른 로그인</span>
                </div>
                <p className="text-sm text-gray-500">상품·주문·정산 관리. 테스트 계정으로 관리자 대시보드에 즉시 입장합니다.</p>
              </div>
            </button>

            {/* ADMIN — 빠른 로그인 */}
            <button
              onClick={() => handleQuickLogin(QUICK_LOGINS[0])}
              disabled={loadingRole !== null}
              className="group flex items-center p-5 border-2 border-gray-200 rounded-xl hover:border-purple-500 hover:bg-purple-50 transition-all text-left disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <div className="w-14 h-14 bg-purple-100 rounded-lg flex items-center justify-center mr-5 group-hover:bg-purple-500 transition-colors flex-shrink-0">
                {loadingRole === 'ADMIN' ? (
                  <svg className="w-5 h-5 text-purple-600 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                  </svg>
                ) : (
                  <svg className="w-7 h-7 text-purple-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" />
                  </svg>
                )}
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-lg font-bold text-gray-800">관리자 대시보드 (ADMIN)</h3>
                  <span className="text-xs bg-purple-100 text-purple-700 font-semibold px-2 py-0.5 rounded-full">빠른 로그인</span>
                </div>
                <p className="text-sm text-gray-500">정산 승인, 주문·상품·회원 관리. 테스트 계정으로 즉시 입장합니다.</p>
              </div>
            </button>

          </div>
        </div>
      </div>

      <p className="mt-8 text-gray-400 text-sm">© 2026 Lemuel Systems. All rights reserved.</p>
    </div>
  );
};

export default StartPage;
