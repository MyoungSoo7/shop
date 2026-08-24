package github.lms.lemuel.common.audit.domain;

import java.time.LocalDateTime;

public class AuditLog {

    private Long id;
    private Long actorId;
    private String actorEmail;
    private AuditAction action;
    private String resourceType;
    private String resourceId;
    private String detailJson;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;

    public AuditLog() {
        this.createdAt = LocalDateTime.now();
    }

    public static AuditLog of(AuditAction action,
                              String resourceType, String resourceId,
                              String detailJson,
                              Long actorId, String actorEmail,
                              String ipAddress, String userAgent) {
        if (action == null) {
            throw new IllegalArgumentException("action required");
        }
        AuditLog log = new AuditLog();
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.detailJson = detailJson;
        log.actorId = actorId;
        log.actorEmail = actorEmail;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        return log;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public AuditAction getAction() { return action; }
    public void setAction(AuditAction action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
