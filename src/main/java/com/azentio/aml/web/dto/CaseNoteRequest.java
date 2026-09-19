package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Appends a note to a case. The author is taken from the authenticated identity, never the body. */
@Schema(name = "CaseNoteRequest", description = "A new investigation note")
public record CaseNoteRequest(
        @NotBlank(message = "a note cannot be empty") @Size(max = 4000) String note) {}
