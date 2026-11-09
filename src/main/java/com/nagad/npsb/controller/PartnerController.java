package com.nagad.npsb.controller;

import com.nagad.npsb.dto.PartnerRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.PartnerService;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Partner Configuration: the partners (Bank / MFS / PSP) and their recipient addresses. Anyone signed in changes them. */
@RestController
@Tag(name = "partners", description = "Partners and their email addresses")
public class PartnerController {
    private final CurrentUser currentUser;
    private final PartnerService partners;

    public PartnerController(CurrentUser currentUser, PartnerService partners) {
        this.currentUser = currentUser;
        this.partners = partners;
    }

    @GetMapping("/api/partners")
    @Operation(summary = "List Partners")
    public List<Object> listPartners() {
        currentUser.require();
        return partners.listPartners();
    }

    @PostMapping("/api/partners")
    @Operation(summary = "Create Partner")
    public Map<String, Object> createPartner(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PartnerRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();  // anyone signed in changes partners directly: no approval needed
        PartnerRequest data = PartnerRequest.from(Body.parse(body));
        return partners.createPartner(user, data);
    }

    @PutMapping("/api/partners/{partner_id}")
    @Operation(summary = "Update Partner")
    public Map<String, Object> updatePartner(
            @PathVariable("partner_id") int partnerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PartnerRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        PartnerRequest data = PartnerRequest.from(Body.parse(body));
        return partners.updatePartner(user, partnerId, data);
    }
}
