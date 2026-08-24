package github.lms.lemuel.operation.notification.application.port.out;

/** 구독 해제 핸들. 두 번 취소해도 무해하다(멱등). */
public interface StreamSubscription {
    void cancel();
}
