package com.moveinsync.rostersync.controller;

import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.dto.Dtos.EmployeeView;
import com.moveinsync.rostersync.dto.Dtos.TransportUpdate;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.service.EmployeeChangeService;
import com.moveinsync.rostersync.service.LookupService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final LookupService lookup;
    private final EmployeeChangeService changes;

    public EmployeeController(LookupService lookup, EmployeeChangeService changes) {
        this.lookup = lookup;
        this.changes = changes;
    }

    // Employee role: their own record only, identified by their token, not the URL.
    @GetMapping("/me")
    public EmployeeView me(@AuthenticationPrincipal CurrentUser user) {
        if (user.employeeId() == null) {
            throw ApiException.notFound("EMPLOYEE_NOT_FOUND", "Employee not found");
        }
        return lookup.employee(user.tenantId(), user.employeeId());
    }

    @GetMapping("/{employeeId}")
    public EmployeeView get(@AuthenticationPrincipal CurrentUser user, @PathVariable String employeeId) {
        return lookup.employee(user.tenantId(), employeeId);
    }

    //Transport admin sets pickup point / special assistance. HR events never overwrite these.
    @PatchMapping("/{employeeId}/transport")
    public EmployeeView transport(@AuthenticationPrincipal CurrentUser user, @PathVariable String employeeId,
                                  @Valid @RequestBody TransportUpdate body) {
        String tenantId = user.tenantId();
        Map<Field, String> edit = new EnumMap<>(Field.class);
        if (body.pickupPoint() != null) {
            edit.put(Field.PICKUP_POINT, body.pickupPoint());
        }
        if (body.specialAssistance() != null) {
            edit.put(Field.SPECIAL_ASSISTANCE, body.specialAssistance());
        }
        changes.applyTransportEdit(tenantId, employeeId, edit);
        return lookup.employee(tenantId, employeeId);
    }
}
