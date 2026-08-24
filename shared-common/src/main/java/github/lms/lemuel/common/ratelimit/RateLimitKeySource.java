package github.lms.lemuel.common.ratelimit;

public record RateLimitKeySource(String ipAddress, String actorEmail) {}
