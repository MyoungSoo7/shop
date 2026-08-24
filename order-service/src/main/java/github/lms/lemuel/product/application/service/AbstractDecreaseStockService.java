package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 원자적 조건부 UPDATE 기반 재고 차감의 공통 골격(Template Method).
 *
 * <p>일반 상품({@link DecreaseProductStockService})과 옵션/SKU({@link DecreaseVariantStockService})가
 * 공유하던 "동시성 제어 + 관측 + 실패 원인 분류" 흐름을 한 곳에 고정한다. 하위 타입은 대상 엔티티에
 * 특화된 훅만 채운다 — 재고 폭주 상황에서 초과판매를 막는 이 골격의 순서(원자 차감 → 성공 카운트,
 * 실패 시 거절 카운트 → 재조회 → 미존재/단종/재고부족 분류)를 각 서비스가 다시 구현하다 어긋나는
 * 것을 방지한다.
 *
 * <p><b>비대상:</b> 역연산인 재고 원복(Increase*Service)은 카운터·ops 신호·분류가 없는 단순 위임이라
 * 이 골격을 공유하지 않는다(억지 통일하지 않는다).
 *
 * @param <T> 차감 대상 도메인 타입 (Product / ProductVariant)
 */
public abstract class AbstractDecreaseStockService<T> {

    private final TransactionTemplate transactionTemplate;
    private final Counter successCounter;
    private final Counter rejectedCounter;

    /**
     * @param metricPrefix      메트릭 접두사 — {@code {prefix}.success} / {@code {prefix}.rejected} 로 등록
     * @param successDescription 성공 카운터 설명(거절 카운터 설명은 두 서비스가 동일하므로 골격이 고정)
     */
    protected AbstractDecreaseStockService(TransactionTemplate transactionTemplate,
                                           MeterRegistry meterRegistry,
                                           String metricPrefix,
                                           String successDescription) {
        this.transactionTemplate = transactionTemplate;
        this.successCounter = Counter.builder(metricPrefix + ".success")
                .description(successDescription)
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder(metricPrefix + ".rejected")
                .description("재고 부족·단종 등으로 차감 거절된 누적")
                .register(meterRegistry);
    }

    /** 원자적 조건부 차감 실행 — 영향 행 수(1=성공, 0=차감 불가)를 반환한다. */
    protected abstract int decreaseStockIfAvailable(Long id, int quantity);

    /** 대상 재조회 — 성공 후 최종 상태 반환 및 실패 원인 분류에 쓰인다. */
    protected abstract Optional<T> reload(Long id);

    /** 재조회 결과가 없을 때(대상 미존재) 던질 예외. */
    protected abstract RuntimeException notFound(Long id);

    /**
     * 대상이 존재하는데도 차감이 거절된 경우(단종 또는 재고 부족)의 원인별 예외를 만든다.
     * 여기서 운영 관제 신호(STOCK_DEPLETED) 발행 등 엔티티 특화 부수효과도 수행한다.
     */
    protected abstract RuntimeException classifyPresent(T current, Long id, int quantity);

    /**
     * 재고 차감 공통 흐름. 하위 타입의 {@code decrease(...)} 가 그대로 위임한다.
     */
    protected final T doDecrease(Long id, int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("차감 수량은 양수여야 합니다");
        }
        T result = transactionTemplate.execute(status -> {
            int affected = decreaseStockIfAvailable(id, quantity);
            if (affected == 0) {
                throw classifyFailure(id, quantity);
            }
            // 재고 차감 성공 직후 같은 트랜잭션 재조회 실패는 발생할 수 없는 내부 불변식이라 generic 유지(프로그래밍 오류 가드).
            return reload(id).orElseThrow(() -> new IllegalStateException(
                    "재고 차감 후 대상 사라짐 (id=" + id + ")"));
        });
        successCounter.increment();
        return result;
    }

    /**
     * 원자적 차감 실패(영향 행 0) 원인 분류. 경합이 아니라 '차감 불가' 상태이므로 재시도 대상이 아니다.
     */
    private RuntimeException classifyFailure(Long id, int quantity) {
        rejectedCounter.increment();
        T current = reload(id).orElse(null);
        if (current == null) {
            return notFound(id);
        }
        return classifyPresent(current, id, quantity);
    }
}
