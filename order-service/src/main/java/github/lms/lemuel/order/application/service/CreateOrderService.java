package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 단건 주문 생성(레거시 호환 경로).
 *
 * <p><b>금액의 권위는 상품 마스터에 있다.</b> 예전에는 요청 본문의 {@code amount} 를 그대로 주문
 * 금액으로 썼다 — 100 만원짜리 상품을 1 원에 주문할 수 있었고, 그 금액이 결제·정산·원장까지 그대로
 * 흘렀다. 다건 주문 경로는 이미 상품에서 단가를 읽고 있었으므로 두 경로의 권위를 한 곳으로 맞춘다.
 *
 * <p>요청 금액이 서버 계산과 다르면 조용히 덮어쓰지 않고 거절한다({@link OrderAmountMismatchException}).
 * 불일치는 위변조이거나 클라이언트가 낡은 가격을 들고 있다는 뜻이고, 덮어쓰면 고객은 자기가 본 적
 * 없는 금액을 결제하게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateOrderService implements CreateOrderUseCase {

    private final LoadUserForOrderPort loadUserForOrderPort;
    private final LoadProductPort loadProductPort;
    private final SaveOrderPort saveOrderPort;
    private final SendOrderNotificationPort sendOrderNotificationPort;
    private final PublishOrderEventPort publishOrderEventPort;

    @Override
    public Order createOrder(CreateOrderCommand command) {
        log.info("주문 생성 시작: userId={}, productId={}, 요청금액={}",
                command.userId(), command.productId(), command.amount());

        // 1. 사용자 존재 확인 및 이메일 조회
        String userEmail = loadUserForOrderPort.findEmailById(command.userId())
                .orElseThrow(() -> {
                    log.warn("존재하지 않는 사용자: userId={}", command.userId());
                    return new UserNotExistsException(command.userId());
                });

        // 2. 금액은 상품 마스터에서 확정한다. 요청 금액은 대조용일 뿐 신뢰 대상이 아니다.
        Product product = loadProductPort.findById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(command.productId()));
        BigDecimal resolvedAmount = product.getPrice();
        if (command.amount() != null && command.amount().compareTo(resolvedAmount) != 0) {
            log.warn("주문 금액 불일치로 거절: productId={}, 요청={}, 실제={}",
                    command.productId(), command.amount(), resolvedAmount);
            throw new OrderAmountMismatchException(command.productId(), command.amount(), resolvedAmount);
        }

        // 3. Order 도메인 생성 (도메인 검증 수행)
        Order order = Order.create(command.userId(), command.productId(), resolvedAmount);

        // 4. 저장
        Order savedOrder = saveOrderPort.save(order);

        // ADR 0020 Phase 3b — settlement order 프로젝션 동기화용 OrderCreated 발행(같은 트랜잭션 Outbox)
        publishOrderEventPort.publishOrderCreated(
                savedOrder.getId(), savedOrder.getUserId(), savedOrder.getProductId(),
                savedOrder.getStatus().name(), savedOrder.getAmount(), savedOrder.getCreatedAt());

        log.info("주문 생성 완료: orderId={}, userId={}, amount={}",
                savedOrder.getId(), savedOrder.getUserId(), savedOrder.getAmount());

        // 5. 알림 발송
        sendOrderNotificationPort.sendOrderConfirmation(userEmail, savedOrder);

        return savedOrder;
    }
}
