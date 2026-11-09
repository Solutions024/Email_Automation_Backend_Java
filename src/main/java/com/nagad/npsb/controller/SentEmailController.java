package com.nagad.npsb.controller;

import com.nagad.npsb.dto.EmailNoteRequest;
import com.nagad.npsb.dto.FollowupRequest;
import com.nagad.npsb.dto.RemarksRequest;
import com.nagad.npsb.dto.ReplyStatusRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.ExportService;
import com.nagad.npsb.service.FollowupService;
import com.nagad.npsb.service.SentEmailService;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Sent Mails and the follow-up of sent emails: reply status, follow-up emails, notes, remarks and history
 * (admins change, everyone reads; anyone signed in can send a follow-up).
 */
@RestController
@Tag(name = "sent emails", description = "Sent emails and their follow-up")
public class SentEmailController {
    private final CurrentUser currentUser;
    private final SentEmailService sentEmails;
    private final FollowupService followups;
    private final ExportService exports;

    public SentEmailController(CurrentUser currentUser, SentEmailService sentEmails, FollowupService followups, ExportService exports) {
        this.currentUser = currentUser;
        this.sentEmails = sentEmails;
        this.followups = followups;
        this.exports = exports;
    }

    @GetMapping("/api/sent-emails")
    @Operation(summary = "Sent emails from every upload.")
    public List<Map<String, Object>> listSentEmails(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "partner_id", required = false) String partnerId,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo,
            @RequestParam(name = "reply", required = false) String reply,
            @RequestParam(name = "limit", required = false) String limit) {
        currentUser.require();
        String search = q == null ? "" : q;
        Integer partner = QueryParams.integer("partner_id", partnerId, null);
        String order = QueryParams.literal("sort", sort, "newest", "newest", "oldest");
        LocalDate from = QueryParams.date("date_from", dateFrom), to = QueryParams.date("date_to", dateTo);
        String replyFilter = QueryParams.literal("reply", reply, "", DispatchQueries.REPLY_FILTERS);
        int rows = Math.max(1, Math.min(QueryParams.integer("limit", limit, 500), 2000));
        return sentEmails.listSentEmails(search, partner, order, from, to, replyFilter, rows);
    }

    @GetMapping("/api/sent-emails/summary")
    @Operation(summary = "How many sent emails are in each reply status.")
    public Map<String, Object> sentSummary(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "partner_id", required = false) String partnerId,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo) {
        currentUser.require();
        String search = q == null ? "" : q;
        Integer partner = QueryParams.integer("partner_id", partnerId, null);
        LocalDate from = QueryParams.date("date_from", dateFrom), to = QueryParams.date("date_to", dateTo);
        return sentEmails.sentSummary(search, partner, from, to);
    }

    @GetMapping("/api/sent-emails/export")
    @Operation(summary = "Sent Mails, with the same filters as the page, as an Excel file.")
    public ResponseEntity<byte[]> exportSent(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "partner_id", required = false) String partnerId,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo,
            @RequestParam(name = "reply", required = false) String reply) {
        currentUser.require();
        String search = q == null ? "" : q;
        Integer partner = QueryParams.integer("partner_id", partnerId, null);
        LocalDate from = QueryParams.date("date_from", dateFrom), to = QueryParams.date("date_to", dateTo);
        String replyFilter = QueryParams.literal("reply", reply, "", DispatchQueries.REPLY_FILTERS);
        return UploadController.download(exports.exportSent(search, partner, from, to, replyFilter));
    }

    @GetMapping("/api/emails/{email_id}/events")
    @Operation(summary = "History of an email, oldest first.")
    public List<Map<String, Object>> listEmailEvents(@PathVariable("email_id") int emailId) {
        currentUser.require();
        return sentEmails.listEmailEvents(emailId);
    }

    @PutMapping("/api/emails/{email_id}/reply-status")
    @Operation(summary = "Admin records whether the partner replied.")
    public Map<String, Object> setReplyStatus(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ReplyStatusRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        ReplyStatusRequest data = ReplyStatusRequest.from(Body.parse(body));
        return sentEmails.setReplyStatus(admin, emailId, data.status(), data.note());
    }

    @PostMapping("/api/emails/{email_id}/notes")
    @Operation(summary = "Admin note on a sent email.")
    public Map<String, Object> addEmailNote(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmailNoteRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        EmailNoteRequest data = EmailNoteRequest.from(Body.parse(body));
        return sentEmails.addEmailNote(admin, emailId, data.note());
    }

    @PutMapping("/api/emails/{email_id}/remarks")
    @Operation(summary = "Admin's free-text remarks on a sent email.")
    public Map<String, Object> setRemarks(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RemarksRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        RemarksRequest data = RemarksRequest.from(Body.parse(body));
        return sentEmails.setRemarks(admin, emailId, data.remarks());
    }

    @GetMapping("/api/emails/{email_id}/followup-draft")
    @Operation(summary = "A ready-made follow-up to edit before sending.")
    public Map<String, Object> followupDraft(@PathVariable("email_id") int emailId) {
        currentUser.require();  // anyone signed in can follow up, whenever they want
        return followups.followupDraft(emailId);
    }

    @PostMapping("/api/emails/{email_id}/followups")
    @Operation(summary = "Send a follow-up for a sent email (users and admins), in the original thread.")
    public Map<String, Object> sendFollowup(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = FollowupRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();  // users and admins
        FollowupRequest data = FollowupRequest.from(Body.parse(body));
        return followups.sendFollowup(user, emailId, data.to(), data.cc(), data.subject(), data.bodyHtml());
    }
}
