package github.lms.lemuel.operation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * operation-service 운영 설정 (prefix=app.ops).
 *
 * <p>category-mapping 은 Alertmanager {@code labels.component} → SignalCategory 이름 매핑 —
 * 알람 룰이 늘어나면 yml 만 고치면 된다(코드 하드코딩 금지). 해석·폴백·경고는
 * IngestAlertService(AlertApplier) 책임.
 */
// 등록은 앱 클래스의 @ConfigurationPropertiesScan 이 한다 — @Component 를 겹치면 이중 등록으로
// NoUniqueBeanDefinition 이 나고, 스캔만 쓰면 @WebMvcTest 슬라이스에서도 바인딩이 살아난다.
@ConfigurationProperties(prefix = "app.ops")
public class OpsProperties {

    /** labels.component → SignalCategory enum 이름 */
    private Map<String, String> categoryMapping = new HashMap<>();

    /** 매핑 실패 폴백 카테고리 (enum 이름) */
    private String defaultCategory = "UNKNOWN";

    /** REFIRED 타임라인 억제 간격 — repeat_interval 폭주 방지 */
    private Duration refireTimelineSuppression = Duration.ofMinutes(30);

    private final Webhook webhook = new Webhook();
    private final Signal signal = new Signal();
    private final Prometheus prometheus = new Prometheus();
    private final Anomaly anomaly = new Anomaly();

    public Map<String, String> getCategoryMapping() {
        return categoryMapping;
    }

    public void setCategoryMapping(Map<String, String> categoryMapping) {
        this.categoryMapping = categoryMapping;
    }

    public String getDefaultCategory() {
        return defaultCategory;
    }

    public void setDefaultCategory(String defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    public Duration getRefireTimelineSuppression() {
        return refireTimelineSuppression;
    }

    public void setRefireTimelineSuppression(Duration refireTimelineSuppression) {
        this.refireTimelineSuppression = refireTimelineSuppression;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Signal getSignal() {
        return signal;
    }

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public Anomaly getAnomaly() {
        return anomaly;
    }

    public static class Webhook {
        /**
         * Alertmanager webhook Bearer 토큰 — 값은 INTERNAL_API_KEY 재사용.
         * 미설정 시 검증 비활성(개발) + 경고 (shared-common InternalApiKeyFilter 와 동일 시맨틱).
         */
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    /** Phase 2 — Kafka 도메인 이벤트 → 신호 버킷 분모 집계 설정. */
    public static class Signal {
        /** metric_key(성공 이벤트) → 구독 토픽. 키가 곧 버킷의 metric_key 다. */
        private Map<String, String> topics = new HashMap<>();
        /** 버킷 폭(초). 5분=300. Phase 3 베이스라인 단위와 일치해야 한다. */
        private int bucketSeconds = 300;

        public Map<String, String> getTopics() {
            return topics;
        }

        public void setTopics(Map<String, String> topics) {
            this.topics = topics;
        }

        public int getBucketSeconds() {
            return bucketSeconds;
        }

        public void setBucketSeconds(int bucketSeconds) {
            this.bucketSeconds = bucketSeconds;
        }
    }

    /**
     * Phase 3 — 베이스라인 이상 탐지 설정. 판정 임계는 전부 여기서 외부화해 재배포 없이 튜닝한다.
     *
     * <p>탐지 대상은 {@link #metricCategory} 의 key 집합(= 실패율 카운터 metric_key) — 값은 이상 시
     * 생성할 인시던트의 {@code SignalCategory} 이름이다. 인프라 게이지는 Alertmanager 가 커버하므로 제외.
     */
    public static class Anomaly {
        /** 스캐너 빈 토글 — 로컬/테스트 기본 off (Prometheus 폴러와 동일). */
        private boolean enabled = false;
        /** 스캔 주기(ms). 버킷 폭(5분)과 일치. */
        private long scanIntervalMs = 300_000;
        /** 롤링 베이스라인 윈도우 크기(직전 버킷 수). 12 = 1시간. */
        private int windowSize = 12;
        /** 이상으로 볼 z-score 하한. */
        private double zThreshold = 3.0;
        /** CRITICAL 승격 z-score 하한 (미만은 WARNING). */
        private double criticalZThreshold = 5.0;
        /** 최소 표본 게이트 — 판정 버킷 count_total 하한. */
        private long minSampleTotal = 30;
        /** 상대임계 하한 — 판정 버킷 failure_rate 하한(0~1). */
        private double failureRateFloor = 0.10;
        /** 정상 복귀 자동해제 조건 — 연속 정상 버킷 수. */
        private int resolveStreakK = 3;
        /** metric_key → 인시던트 SignalCategory 이름. key 집합이 곧 탐지 대상 목록. */
        private Map<String, String> metricCategory = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getScanIntervalMs() {
            return scanIntervalMs;
        }

        public void setScanIntervalMs(long scanIntervalMs) {
            this.scanIntervalMs = scanIntervalMs;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public double getZThreshold() {
            return zThreshold;
        }

        public void setZThreshold(double zThreshold) {
            this.zThreshold = zThreshold;
        }

        public double getCriticalZThreshold() {
            return criticalZThreshold;
        }

        public void setCriticalZThreshold(double criticalZThreshold) {
            this.criticalZThreshold = criticalZThreshold;
        }

        public long getMinSampleTotal() {
            return minSampleTotal;
        }

        public void setMinSampleTotal(long minSampleTotal) {
            this.minSampleTotal = minSampleTotal;
        }

        public double getFailureRateFloor() {
            return failureRateFloor;
        }

        public void setFailureRateFloor(double failureRateFloor) {
            this.failureRateFloor = failureRateFloor;
        }

        public int getResolveStreakK() {
            return resolveStreakK;
        }

        public void setResolveStreakK(int resolveStreakK) {
            this.resolveStreakK = resolveStreakK;
        }

        public Map<String, String> getMetricCategory() {
            return metricCategory;
        }

        public void setMetricCategory(Map<String, String> metricCategory) {
            this.metricCategory = metricCategory;
        }
    }

    /** Phase 2 — Prometheus 인스턴트 쿼리 폴링 → 게이지 버킷 설정. */
    public static class Prometheus {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:9090";
        private long pollIntervalMs = 60_000;
        private int timeoutMs = 5_000;
        /** metric_key → PromQL 인스턴트 쿼리. */
        private Map<String, String> queries = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Map<String, String> getQueries() {
            return queries;
        }

        public void setQueries(Map<String, String> queries) {
            this.queries = queries;
        }
    }
}
