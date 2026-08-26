package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ClaimGiftUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.SendGiftUseCase;
import github.lms.lemuel.order.application.port.out.CreateShipmentPort;
import github.lms.lemuel.order.application.port.out.LoadGiftClaimPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.SaveGiftClaimPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendGiftMessagePort;
import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.order.domain.exception.GiftClaimNotFoundException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 선물 주문 — 보내는 쪽과 받는 쪽을 한 서비스가 맡는다.
 *
 * <p>둘로 나누지 않는 이유는 같은 집합체({@link GiftClaim}) 하나의 앞뒤이기 때문이다. 나누면
 * "링크를 발급할 때 정한 유효기간"과 "링크를 열 때 판정하는 유효기간"이 두 클래스에 흩어진다.
 *
 * <p><b>주문 생성은 일반 경로를 그대로 쓴다</b>({@link IdempotentMultiItemOrderUseCase}).
 * 다른 것은 배송지 자리에 {@code null} 을 넘긴다는 것 하나다 — 그러면 그 경로가 배송을 만들지 않고
 * 주문만 만든다. 선물 전용 생성 경로를 따로 두면 쿠폰·재고·멱등·금액 계산이 두 벌이 된다.
 *
 * <h2>트랜잭션 경계를 어디에 두는가</h2>
 * <p>{@link #send} 에는 <b>트랜잭션을 걸지 않는다.</b> 안에서 부르는 멱등 주문 생성이 스스로
 * {@code TransactionTemplate} 로 경계를 잡고 {@code DataIntegrityViolationException} 을 그 밖에서
 * 받아 "동시 중복"을 복원하기 때문이다. 바깥 트랜잭션이 있으면 그 위반이 바깥까지 오염시켜
 * (rollback-only) 복원 경로가 통째로 죽는다.
 *
 * <p>{@link #verify} 도 마찬가지다. 인증 실패는 시도 횟수를 올린 <b>뒤에</b> 예외를 던지는데,
 * 트랜잭션 안이면 그 예외가 방금 올린 카운터까지 롤백시킨다 — 무제한 대입이 열린다.
 * 저장을 독립 커밋으로 두는 것이 이 규칙의 전부다.
 *
 * <p>반대로 {@link #submitAddress} 는 주문·배송·수령기록 셋이 함께 서야 하므로 한 트랜잭션이다.
 */
@Service
public class GiftClaimService implements SendGiftUseCase, ClaimGiftUseCase {

    private static final Logger log = LoggerFactory.getLogger(GiftClaimService.class);

    /** 소멸 배치가 한 번에 훑는 최대치의 상한 — 설정이 더 큰 값을 줘도 여기서 잘린다. */
    private static final int MAX_EXPIRE_BATCH = 1000;

    private final IdempotentMultiItemOrderUseCase createOrderUseCase;
    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final CreateShipmentPort createShipmentPort;
    private final SaveGiftClaimPort saveGiftClaimPort;
    private final LoadGiftClaimPort loadGiftClaimPort;
    private final SendGiftMessagePort sendGiftMessagePort;

    private final String claimBaseUrl;
    private final int linkTtlDays;
    private final int codeTtlMinutes;

    public GiftClaimService(IdempotentMultiItemOrderUseCase createOrderUseCase,
                            LoadOrderPort loadOrderPort,
                            SaveOrderPort saveOrderPort,
                            CreateShipmentPort createShipmentPort,
                            SaveGiftClaimPort saveGiftClaimPort,
                            LoadGiftClaimPort loadGiftClaimPort,
                            SendGiftMessagePort sendGiftMessagePort,
                            @Value("${app.gift.claim-base-url:http://localhost:3000/gift}") String claimBaseUrl,
                            @Value("${app.gift.link-ttl-days:14}") int linkTtlDays,
                            @Value("${app.gift.code-ttl-minutes:5}") int codeTtlMinutes) {
        this.createOrderUseCase = createOrderUseCase;
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.createShipmentPort = createShipmentPort;
        this.saveGiftClaimPort = saveGiftClaimPort;
        this.loadGiftClaimPort = loadGiftClaimPort;
        this.sendGiftMessagePort = sendGiftMessagePort;
        this.claimBaseUrl = claimBaseUrl;
        this.linkTtlDays = linkTtlDays;
        this.codeTtlMinutes = codeTtlMinutes;
    }

    // ───────── 보내는 사람 ─────────

    @Override
    public SentGift send(SendCommand command, String idempotencyKey) {
        // 배송지 자리에 null — 이 경로의 전부다. 받는 사람이 나중에 채운다.
        Order order = createOrderUseCase.create(
                command.senderUserId(), command.lines(), command.couponCode(), null, idempotencyKey);

        // 같은 멱등 키의 재요청이면 위에서 기존 주문이 그대로 돌아온다. 그때 링크를 하나 더
        // 만들면 한 주문에 살아 있는 링크가 둘이 되고, 둘 다 배송지를 낼 수 있게 된다.
        // 동시 요청은 이 검사를 둘 다 통과할 수 있어 최종 방어선은 DB 의 order_id UNIQUE 다.
        loadGiftClaimPort.findByOrderId(order.getId()).ifPresent(existing -> {
            throw new OrderInvariantViolationException("이미 선물 링크가 발급된 주문입니다");
        });

        String token = GiftSecrets.newToken();
        LocalDateTime now = LocalDateTime.now();
        GiftClaim saved = saveGiftClaimPort.save(GiftClaim.open(
                order.getId(), command.senderUserId(),
                command.recipientName(), command.recipientPhone(), command.message(),
                GiftSecrets.hashToken(token), now, now.plusDays(linkTtlDays)));

        log.info("선물 주문 생성: orderId={}, giftClaimId={}, 수령자={}",
                order.getId(), saved.getId(), saved.maskedRecipientPhone());

        // 발송 실패가 주문을 되돌리지는 않는다 — 결제된 주문을 문자 한 통 때문에 무를 수는 없다.
        // 대신 조용히 넘어가지 않는다: 로그에 남기고 응답의 linkDelivered=false 로 보낸 사람에게
        // 알려 재발송을 누르게 한다. 여기서 예외를 던지면 결제까지 실패한 것처럼 보인다.
        boolean delivered = deliverLink(saved, token);
        return new SentGift(order, saved, token, delivered);
    }

    @Override
    @Transactional
    public boolean resendLink(Long orderId) {
        // 평문 토큰은 발급 순간에만 존재한다. 그래서 재발송은 "같은 링크를 다시 보내기"가 아니라
        // 반드시 새 토큰이다 — 옛 링크는 그 자리에서 죽는다(GiftClaim#rotateToken 참조).
        GiftClaim claim = getByOrderId(orderId);
        String token = GiftSecrets.newToken();
        claim.rotateToken(GiftSecrets.hashToken(token), LocalDateTime.now());
        GiftClaim reissued = saveGiftClaimPort.save(claim);
        return deliverLink(reissued, token);
    }

    @Override
    @Transactional(readOnly = true)
    public GiftClaim getByOrderId(Long orderId) {
        return loadGiftClaimPort.findByOrderId(orderId).orElseThrow(GiftClaimNotFoundException::new);
    }

    @Override
    @Transactional
    public GiftClaim cancel(Long orderId) {
        GiftClaim claim = getByOrderId(orderId);
        claim.cancel(LocalDateTime.now());
        return saveGiftClaimPort.save(claim);
    }

    // ───────── 받는 사람 ─────────

    @Override
    @Transactional(readOnly = true)
    public GiftView view(String token) {
        GiftClaim claim = loadByToken(token);
        Order order = loadOrderPort.findById(claim.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(claim.getOrderId()));
        return GiftView.of(claim, LocalDateTime.now(), itemsOf(order));
    }

    @Override
    public void requestVerificationCode(String token) {
        GiftClaim claim = loadByToken(token);
        String code = GiftSecrets.newVerificationCode();
        LocalDateTime now = LocalDateTime.now();
        claim.issueVerificationCode(
                GiftSecrets.hashCode(claim.getTokenHash(), code), now, now.plusMinutes(codeTtlMinutes));
        GiftClaim saved = saveGiftClaimPort.save(claim);

        // 여기서는 예외를 삼키지 않는다. 결제처럼 지킬 것이 없고, 받는 사람은 오지 않을 번호를
        // 기다리느니 지금 실패를 보는 편이 낫다.
        sendGiftMessagePort.sendVerificationCode(saved, code);
    }

    /**
     * 인증번호 확인.
     *
     * <p>실패해도 <b>저장은 남는다</b>(클래스 주석 참조 — 이 메서드에 트랜잭션이 없는 이유다).
     * 도메인이 예외를 던지기 전에 시도 횟수를 올려 두므로, 저장하지 않고 흘려보내면 카운터가
     * 사라져 {@link GiftClaim#MAX_VERIFY_ATTEMPTS} 가 무의미해진다.
     */
    @Override
    public void verify(String token, String code) {
        GiftClaim claim = loadByToken(token);
        try {
            claim.verify(GiftSecrets.hashCode(claim.getTokenHash(), code), LocalDateTime.now());
        } catch (RuntimeException failure) {
            saveGiftClaimPort.save(claim);
            throw failure;
        }
        saveGiftClaimPort.save(claim);
    }

    @Override
    @Transactional
    public void submitAddress(String token, AddressSubmission address) {
        GiftClaim claim = loadByToken(token);
        Order order = loadOrderPort.findById(claim.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(claim.getOrderId()));

        ShippingAddressSnapshot effective = toSnapshot(address, claim);

        // 순서가 중요하다. markClaimed 를 먼저 하면 상태는 CLAIMED 인데 주소는 없는 창이 생기고,
        // 뒤이어 attachShippingAddress 가 거절되면(이미 주소가 있는 주문) 링크만 소진된다.
        order.attachShippingAddress(effective);
        saveOrderPort.save(order);
        createShipmentPort.createForOrder(order.getId(), effective);

        claim.markClaimed(LocalDateTime.now());
        saveGiftClaimPort.save(claim);
        log.info("선물 수령 완료: orderId={}, giftClaimId={}", order.getId(), claim.getId());
    }

    @Override
    @Transactional
    public int expireOverdue(LocalDateTime now, int batchSize) {
        List<GiftClaim> overdue =
                loadGiftClaimPort.findExpirable(now, Math.clamp(batchSize, 1, MAX_EXPIRE_BATCH));
        for (GiftClaim claim : overdue) {
            claim.expire(now);
            saveGiftClaimPort.save(claim);
        }
        return overdue.size();
    }

    // ───────── 내부 ─────────

    private boolean deliverLink(GiftClaim claim, String token) {
        try {
            sendGiftMessagePort.sendGiftLink(claim, claimUrl(token));
            return true;
        } catch (RuntimeException failure) {
            log.error("선물 링크 발송 실패 — 주문은 남는다(재발송 필요): orderId={}, giftClaimId={}",
                    claim.getOrderId(), claim.getId(), failure);
            return false;
        }
    }

    /**
     * 받는 사람 이름을 비워 내면 링크에 적힌 이름을 쓴다.
     *
     * <p>화면에는 이미 자기 이름이 적혀 있어 다시 입력받는 것이 어색하고, 사무실 등 다른 이름으로
     * 받고 싶은 사람은 덮어쓰면 된다.
     */
    private static ShippingAddressSnapshot toSnapshot(AddressSubmission address, GiftClaim claim) {
        String name = address.recipientName() == null || address.recipientName().isBlank()
                ? claim.getRecipientName()
                : address.recipientName();
        return new ShippingAddressSnapshot(name, address.phone(), address.postalCode(),
                address.address1(), address.address2(), address.deliveryMemo());
    }

    private GiftClaim loadByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new GiftClaimNotFoundException();
        }
        return loadGiftClaimPort.findByTokenHash(GiftSecrets.hashToken(token))
                .orElseThrow(GiftClaimNotFoundException::new);
    }

    private String claimUrl(String token) {
        String base = claimBaseUrl.endsWith("/")
                ? claimBaseUrl.substring(0, claimBaseUrl.length() - 1)
                : claimBaseUrl;
        return base + "/" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** 취소된 라인은 빼고 보여 준다 — 받는 사람이 오지 않을 물건을 기다리게 하지 않는다. */
    private static List<ClaimGiftUseCase.Item> itemsOf(Order order) {
        return order.activeItems().stream()
                .map(item -> new ClaimGiftUseCase.Item(item.getProductName(), item.getQuantity()))
                .toList();
    }
}
