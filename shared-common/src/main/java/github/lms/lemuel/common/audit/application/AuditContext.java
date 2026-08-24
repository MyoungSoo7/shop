package github.lms.lemuel.common.audit.application;

/**
 * 현재 HTTP 요청의 actor 정보 (액터 id/email/ip/ua) 를 ThreadLocal 에 보관.
 *
 * <p>{@code AuditInterceptor} 에서 요청 진입 시 세팅, 응답 시 {@code clear()}.
 * 서비스·AOP 가 {@code get()} 으로 읽어 audit_logs 에 기록.
 */
public final class AuditContext {

    private static final ThreadLocal<AuditActor> HOLDER = new ThreadLocal<>();

    public static void set(AuditActor actor) {
        HOLDER.set(actor);
    }

    public static AuditActor get() {
        AuditActor actor = HOLDER.get();
        return actor != null ? actor : AuditActor.system();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record AuditActor(Long actorId, String actorEmail, String ipAddress, String userAgent) {
        public static AuditActor system() {
            return new AuditActor(null, null, null, null);
        }
    }

    private AuditContext() {}
}
