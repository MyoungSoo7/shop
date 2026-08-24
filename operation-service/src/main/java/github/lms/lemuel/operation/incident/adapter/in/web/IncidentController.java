package github.lms.lemuel.operation.incident.adapter.in.web;

import github.lms.lemuel.operation.incident.adapter.in.web.dto.CommentRequest;
import github.lms.lemuel.operation.incident.adapter.in.web.dto.IncidentDetailResponse;
import github.lms.lemuel.operation.incident.adapter.in.web.dto.IncidentResponse;
import github.lms.lemuel.operation.incident.adapter.in.web.dto.IncidentSummaryResponse;
import github.lms.lemuel.operation.incident.adapter.in.web.dto.PageResponse;
import github.lms.lemuel.operation.incident.adapter.in.web.dto.TransitionRequest;
import github.lms.lemuel.operation.incident.application.port.in.CommentIncidentUseCase;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSearchCondition;
import github.lms.lemuel.operation.incident.application.port.in.TransitionIncidentUseCase;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * 운영자 인시던트 콘솔 API — 게이트웨이 {@code /api/ops/**} 라우트, JWT ROLE_ADMIN 전용.
 *
 * <p>전이 불가·낙관적 락 충돌은 409(OpsWebExceptionHandler), 미존재는 404.
 */
@RestController
@RequestMapping("/api/ops/incidents")
public class IncidentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IncidentQuery incidentQuery;
    private final TransitionIncidentUseCase transitionUseCase;
    private final CommentIncidentUseCase commentUseCase;

    public IncidentController(IncidentQuery incidentQuery, TransitionIncidentUseCase transitionUseCase,
                              CommentIncidentUseCase commentUseCase) {
        this.incidentQuery = incidentQuery;
        this.transitionUseCase = transitionUseCase;
        this.commentUseCase = commentUseCase;
    }

    @GetMapping
    public PageResponse<IncidentResponse> search(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) SignalCategory category,
            @RequestParam(required = false) IncidentSeverity severity,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        IncidentSearchCondition condition = new IncidentSearchCondition(
                status, category, severity, from, to,
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageResponse.from(incidentQuery.search(condition), IncidentResponse::from);
    }

    @GetMapping("/summary")
    public IncidentSummaryResponse summary(@RequestParam(defaultValue = "24h") String window) {
        return IncidentSummaryResponse.from(window, incidentQuery.summary(parseWindow(window)));
    }

    @GetMapping("/{id}")
    public IncidentDetailResponse get(@PathVariable Long id) {
        return IncidentDetailResponse.from(incidentQuery.get(id));
    }

    @PostMapping("/{id}/ack")
    public IncidentDetailResponse acknowledge(@PathVariable Long id,
                                              @RequestBody(required = false) @Valid TransitionRequest request,
                                              Authentication authentication) {
        Incident incident = transitionUseCase.acknowledge(id, authentication.getName(), note(request));
        return IncidentDetailResponse.from(incidentQuery.get(incident.getId()));
    }

    @PostMapping("/{id}/resolve")
    public IncidentDetailResponse resolve(@PathVariable Long id,
                                          @RequestBody(required = false) @Valid TransitionRequest request,
                                          Authentication authentication) {
        Incident incident = transitionUseCase.resolve(id, authentication.getName(), note(request));
        return IncidentDetailResponse.from(incidentQuery.get(incident.getId()));
    }

    @PostMapping("/{id}/false-positive")
    public IncidentDetailResponse markFalsePositive(@PathVariable Long id,
                                                    @RequestBody(required = false) @Valid TransitionRequest request,
                                                    Authentication authentication) {
        Incident incident = transitionUseCase.markFalsePositive(id, authentication.getName(), note(request));
        return IncidentDetailResponse.from(incidentQuery.get(incident.getId()));
    }

    @PostMapping("/{id}/comments")
    public IncidentDetailResponse comment(@PathVariable Long id,
                                          @RequestBody @Valid CommentRequest request,
                                          Authentication authentication) {
        commentUseCase.comment(id, authentication.getName(), request.note());
        return IncidentDetailResponse.from(incidentQuery.get(id));
    }

    private String note(TransitionRequest request) {
        return request == null ? null : request.note();
    }

    /** "1h" / "24h" / "7d" 형식 window 파싱 — 미지원 형식은 IllegalArgumentException(400). */
    private Duration parseWindow(String window) {
        if (window == null || window.length() < 2) {
            throw new IllegalArgumentException("window 형식 오류 (예: 1h, 24h, 7d): " + window);
        }
        char unit = Character.toLowerCase(window.charAt(window.length() - 1));
        long amount;
        try {
            amount = Long.parseLong(window, 0, window.length() - 1, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("window 형식 오류 (예: 1h, 24h, 7d): " + window);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("window 는 양수여야 합니다: " + window);
        }
        return switch (unit) {
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("window 단위는 h/d 만 지원: " + window);
        };
    }
}
