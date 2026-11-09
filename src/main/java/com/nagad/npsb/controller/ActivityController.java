package com.nagad.npsb.controller;

import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** What changed (open pages reload when it did) and what needs someone's attention. */
@RestController
@Tag(name = "activity", description = "Change fingerprint and attention counts")
public class ActivityController {
    private final CurrentUser currentUser;
    private final ActivityService activity;

    public ActivityController(CurrentUser currentUser, ActivityService activity) {
        this.currentUser = currentUser;
        this.activity = activity;
    }

    @GetMapping("/api/activity")
    @Operation(summary = "A fingerprint of the emails and partner mail: open pages reload when it changes.")
    public Map<String, Object> activity() {
        currentUser.require();
        return activity.activity();
    }

    @GetMapping("/api/attention")
    @Operation(summary = "What needs someone's attention, for the banner and the menu counts.")
    public Map<String, Object> attention() {
        User user = currentUser.require();
        return activity.attention(user);
    }
}
