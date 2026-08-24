# Lemuel Frontend

React + TypeScript + Vite 기반의 정산 시스템 프론트엔드

## 기술 스택

- **React 18** - UI 라이브러리
- **TypeScript** - 타입 안정성
- **Vite** - 빌드 도구
- **React Router** - 라우팅
- **Axios** - HTTP 클라이언트
- **Tailwind CSS** - 스타일링
- **date-fns** - 날짜 포맷팅
- **Capacitor 8** - Android/iOS 하이브리드 앱 런타임

## 프로젝트 구조

```
frontend/
├── src/
│   ├── api/              # API 서비스 레이어
│   │   ├── axios.ts      # Axios 인스턴스 + JWT 인터셉터
│   │   ├── auth.ts       # 인증 API
│   │   ├── settlement.ts # 정산 검색 API
│   │   ├── order.ts      # 주문 API
│   │   ├── paymentDomain.ts    # 결제 API
│   │   └── refund.ts     # 환불 API
│   ├── components/       # 재사용 가능한 컴포넌트
│   ├── pages/            # 페이지 컴포넌트
│   │   ├── Login.tsx
│   │   └── SettlementDashboard.tsx
│   ├── types/            # TypeScript 타입 정의
│   │   └── index.ts
│   ├── App.tsx           # 메인 앱 컴포넌트
│   └── main.tsx          # 엔트리 포인트
├── package.json
├── vite.config.ts
└── tsconfig.json
```

## 시작하기

### 1. 의존성 설치

```bash
cd frontend
npm install
```

Tailwind CSS 설정:
```bash
npm install -D tailwindcss postcss autoprefixer
```

### 2. 환경 변수 설정

`.env` 파일 생성:
```bash
cp .env.example .env
```

`.env` 내용:
```
VITE_API_BASE_URL=http://localhost:8080
```

### 3. 개발 서버 실행

```bash
npm run dev
```

브라우저에서 `http://localhost:3000` 접속

### 4. 프로덕션 빌드

```bash
npm run build
```

빌드된 파일은 `dist/` 디렉토리에 생성됩니다.

## 주요 기능

### 1. JWT 인증

- **자동 토큰 관리**: `axios` 인터셉터가 모든 요청에 JWT 토큰을 자동으로 추가
- **401 에러 처리**: 토큰 만료 시 자동 로그아웃 및 로그인 페이지로 리다이렉트
- **런타임별 저장소**: 브라우저/PWA는 LocalStorage, Capacitor 네이티브 앱은 iOS Keychain/Android Keystore 기반 보안 저장소 사용

```typescript
// src/api/axios.ts
api.interceptors.request.use((config) => {
  const token = getAuthStorage().get('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

### 2. Protected Route

인증되지 않은 사용자는 로그인 페이지로 리다이렉트:

```typescript
// src/App.tsx
<Route
  path="/dashboard"
  element={
    <ProtectedRoute>
      <SettlementDashboard />
    </ProtectedRoute>
  }
/>
```

### 3. 정산 대시보드

**주요 기능**:
- 복합 검색 (주문자명, 상품명, 기간, 상태, 환불 여부)
- 실시간 집계 (총 정산 금액, 환불 금액, 최종 금액)
- 페이지네이션 (20건씩 표시)
- 반응형 테이블 디자인

**API 호출 예시**:
```typescript
const response = await settlementApi.search({
  ordererName: '홍길동',
  startDate: '2024-01-01',
  endDate: '2024-12-31',
  page: 0,
  size: 20,
  sortBy: 'createdAt',
  sortDirection: 'DESC',
});
```

## API 엔드포인트

### 인증
- `POST /auth/login` - 로그인

### 정산 검색
- `GET /api/settlements/search` - 정산 복합 검색
- `POST /api/settlements/search` - 정산 복합 검색 (JSON Body)

### 주문
- `POST /orders` - 주문 생성
- `GET /orders/{id}` - 주문 조회
- `GET /orders/user/{userId}` - 사용자별 주문 목록
- `PATCH /orders/{id}/cancel` - 주문 취소

### 결제
- `POST /payments` - 결제 생성
- `PATCH /payments/{id}/authorize` - 결제 승인
- `PATCH /payments/{id}/capture` - 결제 확정
- `GET /payments/{id}` - 결제 조회

### 환불
- `POST /refunds/{paymentId}` - 환불 요청 (Idempotency-Key 헤더 필수)
- `POST /refunds/full/{paymentId}` - 전체 환불
- `POST /refunds/partial/{paymentId}` - 부분 환불

## 스타일링

Tailwind CSS 유틸리티 클래스 사용:

```tsx
<div className="container mx-auto px-4 py-8">
  <h1 className="text-3xl font-bold mb-8">정산 대시보드</h1>
  <div className="bg-white rounded-lg shadow p-6">
    {/* 컨텐츠 */}
  </div>
</div>
```

## 타입 안정성

모든 API 응답에 대한 TypeScript 타입 정의:

```typescript
// src/types/index.ts
export interface SettlementSearchResponse {
  settlements: SettlementSearchItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  aggregations: SettlementAggregations;
}
```

## 트러블슈팅

### CORS 에러
백엔드 `SecurityConfig.java`에 CORS 설정이 추가되어 있어야 합니다:
```java
configuration.setAllowedOrigins(Arrays.asList(
    "http://localhost:3000",
    "http://localhost:5173"
));
```

### 401 Unauthorized
- JWT 토큰이 만료되었거나 유효하지 않습니다.
- 다시 로그인하세요.

### API 연결 실패
- 백엔드 서버가 실행 중인지 확인하세요 (`http://localhost:8080`)
- `.env` 파일의 `VITE_API_BASE_URL`을 확인하세요

## 개발 가이드

### 새로운 API 추가

1. **타입 정의** (`src/types/index.ts`)
2. **API 서비스** (`src/api/[name].ts`)
3. **페이지 컴포넌트** (`src/pages/[Name].tsx`)
4. **라우팅 추가** (`src/App.tsx`)

### 예시: User API 추가

```typescript
// src/types/index.ts
export interface User {
  id: number;
  email: string;
  role: string;
}

// src/api/user.ts
import api from './axios';
import { User } from '@/types';

export const userApi = {
  getUser: async (id: number): Promise<User> => {
    const response = await api.get<User>(`/users/${id}`);
    return response.data;
  },
};
```

## Capacitor 하이브리드 앱

웹 빌드 결과(`dist/`)를 Android/iOS WebView에 탑재합니다.

```bash
npm run build
npx cap sync
npx cap open android
npx cap open ios
```

`ios` 빌드는 macOS/Xcode가 필요하고, `android` 빌드는 Android SDK/Gradle 환경이 필요합니다. Windows에서는 프로젝트 생성과 `npx cap sync`까지만 확인할 수 있습니다.

네이티브 기능은 `src/lib/` adapter를 통해 접근합니다. 푸시 토큰은 현재 클라이언트에서만 발급하며, 서버 등록 endpoint가 확정되기 전에는 자동 전송하지 않습니다. 결제 성공/실패 URL은 웹 경로와 Capacitor deep link를 같은 라우터 계약으로 처리합니다.

## 모바일 반응형 검증

```bash
npm run e2e:mobile
npm run e2e:responsive
```

검증 대상에는 일반 사용자 화면, AI 채팅, 테이블 중심 정산/관리자 화면이 포함됩니다. 원격 E2E 서버의 인증/데이터 상태에 따라 특정 화면은 별도 확인이 필요합니다.

## 라이센스

MIT
