package com.nagad.npsb.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The API documentation: Swagger UI at /docs, the OpenAPI document at /openapi.json (springdoc). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI npsbOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("NPSB Dispute Email Automation")
                        .version("1.0.0")
                        .description("Backend API: partners, email templates, uploads of dispute files, the emails sent to "
                                + "partners and their replies, approvals, users and settings. Sign in with "
                                + "POST /api/auth/login, then use the access token (Authorize button)."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
