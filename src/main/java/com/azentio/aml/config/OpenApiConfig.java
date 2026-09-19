package com.azentio.aml.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes the OpenAPI document and wires HTTP Basic into Swagger UI's "Authorize" dialog. */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    @Bean
    public OpenAPI sentinelOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Sentinel AML API")
                                .version("v1")
                                .description(
                                        """
                                        Transaction monitoring platform for MeridianTrust Bank.

                                        Covers ingestion (bulk CSV, REST batch, single streaming \
                                        transaction), the configurable detection engine, the \
                                        risk-scored alert queue, case management and the immutable \
                                        audit trail.

                                        All endpoints require HTTP Basic authentication and are \
                                        authorised by role. Customer PII is masked in list \
                                        responses and revealed in detail responses only to roles \
                                        entitled to see it.""")
                                .contact(new Contact().name("Sentinel Engineering Squad"))
                                .license(new License().name("Proprietary - hackathon prototype")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BASIC_AUTH,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("basic")
                                                .description(
                                                        "Analyst credentials issued by the"
                                                                + " compliance administrator")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
