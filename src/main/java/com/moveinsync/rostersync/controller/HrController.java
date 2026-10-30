package com.moveinsync.rostersync.controller;

import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.dto.Dtos.EventBatch;
import com.moveinsync.rostersync.dto.Dtos.RunView;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.service.IngestService;
import com.moveinsync.rostersync.service.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

//What the customer's HR system calls: live events and the nightly export.
@RestController
@RequestMapping("/api/v1/hr")
public class HrController {

    private final IngestService ingest;
    private final ReconciliationService reconciliation;

    public HrController(IngestService ingest, ReconciliationService reconciliation) {
        this.ingest = ingest;
        this.reconciliation = reconciliation;
    }

    // Up to 1000 events. Always 200 with a per-record report; bad records are listed, not fatal.
    @PostMapping("/events")
    public BatchResult events(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody EventBatch body) {
        return ingest.ingest(user.tenantId(), body.events());
    }

    //Tonight's full export as CSV. Runs the reconciliation and returns its summary.
    @PostMapping(value = "/snapshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RunView snapshot(@AuthenticationPrincipal CurrentUser user, @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return reconciliation.run(user.tenantId(), in);
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Uploaded file could not be read");
        }
    }
}
