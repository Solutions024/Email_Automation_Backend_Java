package com.nagad.npsb.controller;

import com.nagad.npsb.dto.ApplyTemplateRequest;
import com.nagad.npsb.dto.EmailEditRequest;
import com.nagad.npsb.dto.PrepareEmailsRequest;
import com.nagad.npsb.dto.SendEmailsRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.DraftService;
import com.nagad.npsb.service.ExportService;
import com.nagad.npsb.service.SendingService;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The emails of an upload: prepared one per partner and mail type (preview / edit), sent for approval by a user
 * (PREPARED -> PENDING), and sent by an admin from Pending Mails.
 */
@RestController
@Tag(name = "emails", description = "Preparing, editing, submitting and sending emails")
public class EmailController {
    private final CurrentUser currentUser;
    private final DraftService drafts;
    private final SendingService sending;
    private final ExportService exports;

    public EmailController(CurrentUser currentUser, DraftService drafts, SendingService sending, ExportService exports) {
        this.currentUser = currentUser;
        this.drafts = drafts;
        this.sending = sending;
        this.exports = exports;
    }

    @PostMapping("/api/uploads/{upload_id}/prepare-emails")
    @Operation(summary = "Build (or rebuild) one email per partner and mail type.")
    public Map<String, Object> prepareEmails(
            @PathVariable("upload_id") int uploadId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false, content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PrepareEmailsRequest.class)))
            @RequestBody(required = false) String body) {
        currentUser.require();
        PrepareEmailsRequest data = PrepareEmailsRequest.from(Body.parseOptional(body));
        return drafts.prepareEmails(uploadId, data.templates());
    }

    @GetMapping("/api/uploads/{upload_id}/emails")
    @Operation(summary = "List Emails")
    public List<Map<String, Object>> listEmails(@PathVariable("upload_id") int uploadId) {
        currentUser.require();
        return drafts.listEmails(uploadId);
    }

    @GetMapping("/api/emails/{email_id}/attachment")
    @Operation(summary = "The Excel file attached to this email: its transactions.")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable("email_id") int emailId) {
        currentUser.require();
        return UploadController.download(drafts.downloadAttachment(emailId));
    }

    @PutMapping("/api/emails/{email_id}")
    @Operation(summary = "Save a manual edit. Edited emails are kept when previews are refreshed.")
    public Map<String, Object> editEmail(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmailEditRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        EmailEditRequest data = EmailEditRequest.from(Body.parse(body));
        return drafts.editEmail(user, emailId, data.subject(), data.bodyHtml());
    }

    @DeleteMapping("/api/emails/{email_id}/edits")
    @Operation(summary = "Throw away manual edits.")
    public Map<String, Object> discardEmailEdits(@PathVariable("email_id") int emailId) {
        User user = currentUser.require();
        return drafts.discardEmailEdits(user, emailId);
    }

    @PutMapping("/api/emails/{email_id}/template")
    @Operation(summary = "Rebuild this email from another template. Manual edits are replaced.")
    public Map<String, Object> applyTemplate(
            @PathVariable("email_id") int emailId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApplyTemplateRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        ApplyTemplateRequest data = ApplyTemplateRequest.from(Body.parse(body));
        return drafts.applyTemplate(user, emailId, data.templateId());
    }

    @PostMapping("/api/uploads/{upload_id}/submit")
    @Operation(summary = "A user's \"Send\": every PREPARED email of this upload waits for an admin.")
    public Map<String, Object> submitEmails(@PathVariable("upload_id") int uploadId) {
        User user = currentUser.require();
        return drafts.submitEmails(user, uploadId);
    }

    @GetMapping("/api/pending-emails")
    @Operation(summary = "Emails waiting for an admin (PENDING), and ones whose sending failed (FAILED).")
    public List<Map<String, Object>> listPendingEmails() {
        currentUser.require();
        return sending.listPendingEmails();
    }

    @PostMapping("/api/pending-emails/send")
    @Operation(summary = "Admin sends the ticked PENDING / FAILED emails (never twice).")
    public Map<String, Object> sendSelected(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = SendEmailsRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        SendEmailsRequest data = SendEmailsRequest.from(Body.parse(body));
        return sending.sendSelected(admin, data.ids());
    }

    @GetMapping("/api/pending-emails/export")
    @Operation(summary = "Pending Mails as an Excel file.")
    public ResponseEntity<byte[]> exportPending() {
        currentUser.require();
        return UploadController.download(exports.exportPending());
    }
}
