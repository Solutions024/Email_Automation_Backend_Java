package com.nagad.npsb.controller;

import com.nagad.npsb.dto.LinkReplyRequest;
import com.nagad.npsb.dto.ReplyReadRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.ReplyService;
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

import java.util.List;
import java.util.Map;

/** Partners' mail, read from the sending mailbox, and the messages in the mailbox that could not be read. */
@RestController
@Tag(name = "replies", description = "Partner replies and inbox issues")
public class ReplyController {
    private final CurrentUser currentUser;
    private final ReplyService replies;

    public ReplyController(CurrentUser currentUser, ReplyService replies) {
        this.currentUser = currentUser;
        this.replies = replies;
    }

    @GetMapping("/api/replies/summary")
    @Operation(summary = "New-reply count for the sidebar badge, and how the mailbox check is doing.")
    public Map<String, Object> repliesSummary() {
        return replies.repliesSummary(currentUser.require());
    }

    @GetMapping("/api/replies")
    @Operation(summary = "Partner messages, newest first.")
    public List<Object> listReplies(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "show", required = false) String show,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "partner_id", required = false) String partnerId) {
        User user = currentUser.require();
        String list = QueryParams.literal("category", category, "replies", ReplyService.CATEGORIES);
        String which = QueryParams.literal("show", show, "all", "all", "unread");
        String search = q == null ? "" : q;
        Integer partner = QueryParams.integer("partner_id", partnerId, null);
        return replies.listReplies(user, list, which, search, partner);
    }

    @GetMapping("/api/replies/{reply_id}")
    @Operation(summary = "One reply with its text. Opening it marks it as read for this user.")
    public Map<String, Object> getReply(@PathVariable("reply_id") int replyId) {
        User user = currentUser.require();
        return replies.getReply(user, replyId);
    }

    @PutMapping("/api/replies/{reply_id}/read")
    @Operation(summary = "Mark one reply as read or as new again (for this user only).")
    public Map<String, Object> setReplyRead(
            @PathVariable("reply_id") int replyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ReplyReadRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        ReplyReadRequest data = ReplyReadRequest.from(Body.parse(body));
        return replies.setReplyRead(user, replyId, data.read());
    }

    @PostMapping("/api/replies/read-all")
    @Operation(summary = "Mark every new message as read: of one list (category), or of all of them.")
    public Map<String, Object> markAllRepliesRead(@RequestParam(name = "category", required = false) String category) {
        User user = currentUser.require();
        String list = category == null ? null : QueryParams.literal("category", category, null, ReplyService.CATEGORIES);
        return replies.markAllRepliesRead(user, list);
    }

    @PostMapping("/api/replies/{reply_id}/link")
    @Operation(summary = "An admin decides which sent email a message answers.")
    public Map<String, Object> linkReply(
            @PathVariable("reply_id") int replyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = LinkReplyRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        LinkReplyRequest data = LinkReplyRequest.from(Body.parse(body));
        return replies.linkReply(admin, replyId, data.emailId());
    }

    @PostMapping("/api/replies/{reply_id}/dismiss")
    @Operation(summary = "Not about any of our emails: hidden from the lists.")
    public Map<String, Object> dismissReply(@PathVariable("reply_id") int replyId) {
        User admin = currentUser.requireAdmin();
        return replies.dismissReply(admin, replyId);
    }

    @GetMapping("/api/inbox-issues")
    @Operation(summary = "Messages in the mailbox that could not be read.")
    public List<Object> listInboxIssues() {
        currentUser.requireAdmin();
        return replies.listInboxIssues();
    }

    @PostMapping("/api/inbox-issues/{issue_id}/retry")
    @Operation(summary = "Retry Inbox Issue")
    public Map<String, Object> retryInboxIssue(@PathVariable("issue_id") int issueId) {
        User admin = currentUser.requireAdmin();
        return replies.retryInboxIssue(admin, issueId);
    }

    @PostMapping("/api/inbox-issues/{issue_id}/dismiss")
    @Operation(summary = "Dismiss Inbox Issue")
    public Map<String, Object> dismissInboxIssue(@PathVariable("issue_id") int issueId) {
        User admin = currentUser.requireAdmin();
        return replies.dismissInboxIssue(admin, issueId);
    }

    @GetMapping("/api/replies/{reply_id}/attachments/{attachment_id}")
    @Operation(summary = "Download Reply Attachment")
    public ResponseEntity<byte[]> downloadReplyAttachment(@PathVariable("reply_id") int replyId,
                                                          @PathVariable("attachment_id") int attachmentId) {
        currentUser.require();
        return UploadController.download(replies.downloadReplyAttachment(replyId, attachmentId));
    }

    @PostMapping("/api/replies/check-now")
    @Operation(summary = "Read the mailbox now instead of waiting for the next automatic check.")
    public Map<String, Object> checkInboxNow() {
        User admin = currentUser.requireAdmin();
        return replies.checkInboxNow(admin);
    }
}
