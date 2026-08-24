# Multi-stage build for React frontend
# Stage 1: Build
# 빌드 툴체인. 이 스테이지의 Node 는 런타임이 아니라 tsc·vite 를 돌리는 도구다 —
# 런타임은 아래 nginx 스테이지다. 그래서 최신 라인을 좇을 이유가 없고, 반대로 CI 가
# 검증한 dist 와 여기서 나온 dist 가 다른 엔진 산출물이 되는 것이 진짜 위험이다.
# 버전 단일 출처는 리포 루트 .nvmrc — #233(Dependabot docker-all 그룹)이 이 줄만 26 으로
# 올리고 워크플로의 node-version 리터럴은 20 에 남겨 6 메이저가 벌어진 적이 있다.
# node-version-gate 가 .nvmrc 와 이 major 의 일치를 CI 에서 강제한다.
FROM node:24-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install ALL dependencies including devDependencies (typescript, vite, etc.)
# Builder stage needs devDependencies to run `tsc && vite build`
RUN npm ci

# Copy source code
COPY . .

# Build-time env vars (override .env.production)
ARG VITE_API_BASE_URL
ARG VITE_TOSS_CLIENT_KEY
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_TOSS_CLIENT_KEY=$VITE_TOSS_CLIENT_KEY

# Build for production
RUN npm run build

# Stage 2: Production with Nginx
# 런타임 베이스. 1.25-alpine 은 Alpine 3.19.1 을 물고 있었는데 그 배포판은 지원이 끝나 보안
# 업데이트가 오지 않았고, 이미지 스캔 게이트가 CRITICAL 3건으로 막았다
# (libexpat CVE-2024-45491·45492, libxml2 CVE-2024-56171 — 셋 다 상위 Alpine 에 수정본 존재).
# 태그를 올리는 것이 정답이다(스캔을 끄는 것이 아니라 — ci.yml 의 게이트 주석과 같은 판단).
FROM nginx:1.31-alpine

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/

# Copy built app from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Expose port
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
