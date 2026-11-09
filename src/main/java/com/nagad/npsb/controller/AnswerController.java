package com.nagad.npsb.controller;

import com.nagad.npsb.dto.AnswerRequest;
import com.nagad.npsb.dto.DecisionNoteRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.AnswerService;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Our answers to partners' replies: written in the app, approved by an admin, sent in the same email thread. */
@RestController
@Tag(name = "answers", description = "Answers to partners' replies")
public class AnswerController {
    private final CurrentUser currentUser;
    private final AnswerService answers;

    public AnswerController(CurrentUser currentUser, AnswerService answers) {
        this.currentUser = currentUser;
        this.answers = answers;
    }

    @PostMapping("/api/replies/{reply_id}/answers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write an answer to a partner's reply: it waits for an admin's approval.")
    public Map<String, Object> createAnswer(
            @PathVariable("reply_id") int replyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AnswerRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        AnswerRequest data = AnswerRequest.from(Body.parse(body));
        return answers.createAnswer(user, replyId, data);
    }

    @PutMapping("/api/answers/{answer_id}")
    @Operation(summary = "The writer changes their answer: it waits for approval again.")
    public Map<String, Object> updateAnswer(
            @PathVariable("answer_id") int answerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AnswerRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        AnswerRequest data = AnswerRequest.from(Body.parse(body));
        return answers.updateAnswer(user, answerId, data);
    }

    @PostMapping("/api/answers/{answer_id}/cancel")
    @Operation(summary = "Cancel Answer")
    public Map<String, Object> cancelAnswer(@PathVariable("answer_id") int answerId) {
        User user = currentUser.require();
        return answers.cancelAnswer(user, answerId);
    }

    @PostMapping("/api/answers/{answer_id}/reject")
    @Operation(summary = "Admin sends an answer back to its writer, with a reason.")
    public Map<String, Object> rejectAnswer(
            @PathVariable("answer_id") int answerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DecisionNoteRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        DecisionNoteRequest data = DecisionNoteRequest.from(Body.parse(body));
        return answers.rejectAnswer(admin, answerId, data.note());
    }

    @PostMapping("/api/answers/{answer_id}/approve")
    @Operation(summary = "Admin approves an answer: it is sent to the partner in the same email thread.")
    public Map<String, Object> approveAnswer(@PathVariable("answer_id") int answerId) {
        User admin = currentUser.requireAdmin();
        return answers.approveAnswer(admin, answerId);
    }

    @GetMapping("/api/answers")
    @Operation(summary = "Answers waiting for approval (or failed); plus your own rejected answers.")
    public List<Map<String, Object>> listAnswers() {
        User user = currentUser.require();
        return answers.listAnswers(user);
    }

    @GetMapping("/api/emails/{email_id}/conversation")
    @Operation(summary = "The back-and-forth of one email as a tree.")
    public Map<String, Object> conversation(@PathVariable("email_id") int emailId) {
        User user = currentUser.require();
        return answers.conversation(user, emailId);
    }
}
