package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** Rebuild an email from another template. */
@Schema(description = "The template to rebuild the email from.")
public record ApplyTemplateRequest(Integer templateId) {

    public static ApplyTemplateRequest from(Body b) {
        Integer templateId = b.integer("template_id");
        b.done();
        return new ApplyTemplateRequest(templateId);
    }
}
