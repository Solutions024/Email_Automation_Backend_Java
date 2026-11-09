package com.nagad.npsb.controller;

import com.nagad.npsb.dto.DecisionNoteRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.ChangeRequestService;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Approvals (maker-checker) and the change history. */
@RestController
@Tag(name = "approvals", description = "Changes waiting for approval and the change history")
public class ApprovalController {
    private final CurrentUser currentUser;
    private final ChangeRequestService changeRequests;

    public ApprovalController(CurrentUser currentUser, ChangeRequestService changeRequests) {
        this.currentUser = currentUser;
        this.changeRequests = changeRequests;
    }

    @GetMapping("/api/change-requests")
    @Operation(summary = "Changes waiting for approval, or the change history, newest first.")
    public List<Object> listChangeRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "entity", required = false) String entity,
            @RequestParam(name = "entity_id", required = false) String entityId) {
        User user = currentUser.require();
        String which = QueryParams.literal("status", status, "pending", "pending", "history", "all");
        String kind = entity == null ? "" : entity;
        Integer id = QueryParams.integer("entity_id", entityId, null);
        return changeRequests.listChangeRequests(user, which, kind, id);
    }

    @PostMapping("/api/change-requests/{request_id}/approve")
    @Operation(summary = "Approve Change")
    public Map<String, Object> approveChange(@PathVariable("request_id") int requestId) {
        User admin = currentUser.requireAdmin();
        return changeRequests.approveChange(admin, requestId);
    }

    @PostMapping("/api/change-requests/{request_id}/reject")
    @Operation(summary = "Reject Change")
    public Map<String, Object> rejectChange(
            @PathVariable("request_id") int requestId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DecisionNoteRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        DecisionNoteRequest data = DecisionNoteRequest.from(Body.parse(body));
        return changeRequests.rejectChange(admin, requestId, data.note());
    }

    @PostMapping("/api/change-requests/{request_id}/cancel")
    @Operation(summary = "Cancel Change")
    public Map<String, Object> cancelChange(@PathVariable("request_id") int requestId) {
        User user = currentUser.require();
        return changeRequests.cancelChange(user, requestId);
    }
}
