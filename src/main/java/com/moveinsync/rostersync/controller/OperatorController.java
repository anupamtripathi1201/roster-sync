package com.moveinsync.rostersync.controller;

import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.config.FailureInjector;
import com.moveinsync.rostersync.dto.Dtos.Rejection;
import com.moveinsync.rostersync.dto.Dtos.RunView;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.messaging.Outbox;
import com.moveinsync.rostersync.repository.OutboxRepository;
import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.service.ReconciliationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

//On-call tools: braked reconciliations, the outbox, and (demo only) failure injection.
@RestController
@RequestMapping("/api/v1/ops")
public class OperatorController {

    private final ReconciliationService reconciliation;
    private final OutboxRepository outboxRows;
    private final Outbox outbox;
    private final FailureInjector failures;
    private final AppProperties props;
    private final Clock clock;

    public OperatorController(ReconciliationService reconciliation, OutboxRepository outboxRows, Outbox outbox,
                              FailureInjector failures, AppProperties props, Clock clock) {
        this.reconciliation = reconciliation;
        this.outboxRows = outboxRows;
        this.outbox = outbox;
        this.failures = failures;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/reconciliations/{runId}")
    public RunView run(@AuthenticationPrincipal CurrentUser user, @PathVariable Long runId) {
        return reconciliation.get(user.tenantId(), runId);
    }

    @GetMapping("/reconciliations/{runId}/rejections")
    public List<Rejection> rejections(@AuthenticationPrincipal CurrentUser user, @PathVariable Long runId,
                                      @RequestParam(defaultValue = "100") int limit) {
        return reconciliation.rejections(user.tenantId(), runId, limit);
    }

    @PostMapping("/reconciliations/{runId}/approve")
    public RunView approve(@AuthenticationPrincipal CurrentUser user, @PathVariable Long runId) {
        return reconciliation.approve(user.tenantId(), runId, user.username());
    }

    @PostMapping("/reconciliations/{runId}/reject")
    public RunView reject(@AuthenticationPrincipal CurrentUser user, @PathVariable Long runId) {
        return reconciliation.reject(user.tenantId(), runId, user.username());
    }

    @GetMapping("/outbox")
    public Map<String, Object> outboxStatus() {
        long oldest = outboxRows.findFirstByPublishedAtIsNullOrderByIdAsc()
                .map(o -> Duration.between(o.getCreatedAt(), clock.instant()).toSeconds()).orElse(0L);
        return Map.of("pending", outboxRows.countByPublishedAtIsNull(), "oldestPendingAgeSeconds", oldest);
    }

    @PostMapping("/outbox/flush")
    public Map<String, Object> flush() {
        return Map.of("published", outbox.publishDue(clock.instant()));
    }

    //Demo only (FAILURE_INJECTION=true): make the next n passes through a crash point fail.
    @PostMapping("/failures/{point}")
    public Map<String, Object> arm(@PathVariable FailureInjector.Point point, @RequestParam(defaultValue = "1") int times) {
        requireFailureInjection();
        failures.arm(point, times);
        return Map.of("armed", point.name(), "times", times);
    }

    @DeleteMapping("/failures")
    public Map<String, Object> disarm() {
        requireFailureInjection();
        failures.disarmAll();
        return Map.of("armed", "none");
    }

    private void requireFailureInjection() {
        if (!props.failureInjection()) {
            throw ApiException.notFound("NOT_FOUND", "Resource not found");
        }
    }
}
