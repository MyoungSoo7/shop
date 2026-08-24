package github.lms.lemuel.operation.notification.adapter.in.web;

import java.util.Set;

/**
 * 접속한 클라이언트가 받을 수 있는 신원들. <b>검증된 JWT 에서만</b> 파생된다 —
 * 경로·쿼리 파라미터에서 파생하는 순간 푸시 스트림은 IDOR 이 된다.
 */
public record SubscriberIdentity(String subject, Set<String> recipients) {

    public SubscriberIdentity {
        recipients = Set.copyOf(recipients);
    }
}
