package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.RecordValidator.Violation;
import com.moveinsync.rostersync.core.RecordValidator;
import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.dto.Dtos.EventRequest;
import com.moveinsync.rostersync.observability.SyncMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final RecordValidator validator;
    private final LookupService lookup;
    private final ChangeExecutor executor;
    private final SyncMetrics metrics;

    public IngestService(RecordValidator validator, LookupService lookup, ChangeExecutor executor, SyncMetrics metrics) {
        this.validator = validator;
        this.lookup = lookup;
        this.executor = executor;
        this.metrics = metrics;
    }

    public BatchResult ingest(String tenantId, List<EventRequest> requests) {
        Set<String> offices = lookup.officeCodes(tenantId);
        BatchResult.Builder result = new BatchResult.Builder(requests.size());

        for (int i = 0; i < requests.size(); i++) {
            EventRequest req = requests.get(i);
            if (req == null) {
                result.reject(i, null, List.of(Violation.record("EMPTY_RECORD")));
                continue;
            }
            metrics.received(req.type() == null ? null : req.type().name());
            List<Violation> violations = validator.validate(
                    new RecordValidator.Input(req.employeeId(), req.type(), req.version(), req.fields()), offices);
            if (!violations.isEmpty()) {
                result.reject(i, req.employeeId(), violations);
                violations.forEach(v -> metrics.rejected(v.code()));
                log.info("record rejected index={} employeeId={} codes={}", i, req.employeeId(),
                        violations.stream().map(Violation::message).toList());
                continue;
            }
            try {
                ChangeApplier.Result r = executor.apply(tenantId, req.toEvent(), "HR_EVENT");
                metrics.processed(r.decision());
                result.count(r.decision());
                if (r.decision() == ChangeApplier.Decision.VERSION_CONFLICT) {
                    result.reject(i, req.employeeId(), List.of(new Violation("version", "VERSION_CONFLICT")));
                    metrics.rejected("VERSION_CONFLICT");
                }
                if (!r.droppedFields().isEmpty()) {
                    result.ownershipIgnored(i, req.employeeId(), r.droppedFields().stream().map(Enum::name).toList());
                }
            } catch (RuntimeException e) {
                // partial failure: this record failed, the rest of the batch carries on
                result.reject(i, req.employeeId(), List.of(Violation.record("PROCESSING_ERROR")));
                metrics.rejected("PROCESSING_ERROR");
                log.error("record failed index={} employeeId={} error={}", i, req.employeeId(), e.getClass().getSimpleName());
            }
        }
        return result.build();
    }
}
