package com.nagad.npsb.controller;

import com.nagad.npsb.dto.TemplateRequest;
import com.nagad.npsb.dto.VariableRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.TemplateService;
import com.nagad.npsb.web.ApiException;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Email templates per mail type and template variables. Users change them (admins approve the changes with
 * maker-checker on); everyone reads them.
 */
@RestController
@Tag(name = "templates", description = "Email templates and template variables")
public class TemplateController {
    private final CurrentUser currentUser;
    private final TemplateService templates;

    public TemplateController(CurrentUser currentUser, TemplateService templates) {
        this.currentUser = currentUser;
        this.templates = templates;
    }

    /** Templates belong to the users who prepare the emails; admins only read (and approve) them. */
    private User requireUser() {
        User user = currentUser.require();
        if ("admin".equals(user.getRole())) throw new ApiException(403, "Email templates are managed by users. Admins approve the changes.");
        return user;
    }

    @GetMapping("/api/email-templates")
    @Operation(summary = "List Templates")
    public Map<String, Object> listTemplates() {
        User user = currentUser.require();
        return templates.templatesPayload(user, null);
    }

    @PostMapping("/api/email-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create Template")
    public Map<String, Object> createTemplate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TemplateRequest.class)))
            @RequestBody(required = false) String body) {
        User user = requireUser();
        TemplateRequest data = TemplateRequest.from(Body.parse(body));
        return templates.createTemplate(user, data);
    }

    @PutMapping("/api/email-templates/{template_id}")
    @Operation(summary = "Update Template")
    public Map<String, Object> updateTemplate(
            @PathVariable("template_id") int templateId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TemplateRequest.class)))
            @RequestBody(required = false) String body) {
        User user = requireUser();
        TemplateRequest data = TemplateRequest.from(Body.parse(body));
        return templates.updateTemplate(user, templateId, data);
    }

    @PostMapping("/api/email-templates/{template_id}/default")
    @Operation(summary = "Set Default Template")
    public Map<String, Object> setDefaultTemplate(@PathVariable("template_id") int templateId) {
        User user = requireUser();
        return templates.setDefaultTemplate(user, templateId);
    }

    @DeleteMapping("/api/email-templates/{template_id}")
    @Operation(summary = "Delete Template")
    public Map<String, Object> deleteTemplate(@PathVariable("template_id") int templateId) {
        User user = requireUser();
        return templates.deleteTemplate(user, templateId);
    }

    @PostMapping("/api/template-variables")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create Variable")
    public Map<String, Object> createVariable(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = VariableRequest.class)))
            @RequestBody(required = false) String body) {
        User user = requireUser();
        VariableRequest data = VariableRequest.from(Body.parse(body));
        return templates.createVariable(user, data);
    }

    @PutMapping("/api/template-variables/{variable_id}")
    @Operation(summary = "Update Variable")
    public Map<String, Object> updateVariable(
            @PathVariable("variable_id") int variableId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = VariableRequest.class)))
            @RequestBody(required = false) String body) {
        User user = requireUser();
        VariableRequest data = VariableRequest.from(Body.parse(body));
        return templates.updateVariable(user, variableId, data);
    }

    @DeleteMapping("/api/template-variables/{variable_id}")
    @Operation(summary = "Delete Variable")
    public Map<String, Object> deleteVariable(@PathVariable("variable_id") int variableId) {
        User user = requireUser();
        return templates.deleteVariable(user, variableId);
    }
}
