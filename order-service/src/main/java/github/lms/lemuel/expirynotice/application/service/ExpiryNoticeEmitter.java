package github.lms.lemuel.expirynotice.application.service;

import github.lms.lemuel.expirynotice.application.port.out.PublishExpiryNoticeEventPort;
import github.lms.lemuel.expirynotice.application.port.out.RecordExpiryNoticePort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 건의 통보를 <b>한 트랜잭션</b>으로 닫는 협력자.
 *
 * <p>별도 빈으로 뺀 이유는 순전히 Spring 때문이다 — 같은 클래스 안의 메서드를 내부에서 부르면
 * 프록시를 안 타고 {@code @Transactional} 이 조용히 무효가 된다. 루프를 도는 쪽과 트랜잭션 경계를
 * 같은 클래스에 두면 그 함정을 정확히 밟는다.
 *
 * <p><b>왜 건별 트랜잭션인가.</b> 전체를 한 트랜잭션으로 묶으면 마지막 한 건의 실패가 앞의 성공
 * 전부를 되돌린다. 그러면 문제의 그 한 건이 고쳐질 때까지 매일 모두가 통보를 못 받는다.
 * 반대로 건별로 끊으면 실패는 그 건에서 멈추고, 원장에 안 남았으니 <b>다음 날 자연히 재시도된다.</b>
 *
 * <p>원장 선점과 이벤트 발행은 <b>같은</b> 트랜잭션이다. 갈라두면 "원장엔 보냈다고 적혔는데 실제로는
 * 안 나간" 건이 생기고, 그건 UNIQUE 때문에 영원히 재시도되지 않는다 — 조용히 잃는 가장 나쁜 형태다.
 */
@Component
public class ExpiryNoticeEmitter {

    private final RecordExpiryNoticePort recordPort;
    private final PublishExpiryNoticeEventPort publishPort;

    public ExpiryNoticeEmitter(RecordExpiryNoticePort recordPort, PublishExpiryNoticeEventPort publishPort) {
        this.recordPort = recordPort;
        this.publishPort = publishPort;
    }

    /** @return 이번에 통보했으면 true, 이미 통보돼 있어 건너뛰었으면 false */
    @Transactional
    public boolean emit(ExpiringItem item, ExpiryNoticeStage stage) {
        if (!recordPort.claim(item, stage)) {
            return false;
        }
        publishPort.expiryUpcoming(item, stage);
        return true;
    }
}
