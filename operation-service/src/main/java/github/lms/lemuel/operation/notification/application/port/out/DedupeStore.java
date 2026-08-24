package github.lms.lemuel.operation.notification.application.port.out;

/**
 * 멱등 포트. TTL 안에서 처음 본 id 면 true, 재전달이면 false 를 돌려준다.
 * 교체 가능하다 — 내구 멱등이 필요하면 Redis/DB 어댑터로 갈아끼운다.
 * 구현체는 어댑터 계층(adapter/out/dedupe)에 산다.
 */
public interface DedupeStore {

    /** @return 새로 기록했으면 true(진행), 이미 본 id 면 false(스킵). */
    boolean markIfFirst(String id);
}
