package com.azentio.aml.web.dto;

import com.azentio.aml.domain.CaseNote;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** An analyst's note on a case. Append-only: notes are never edited or removed. */
@Schema(name = "CaseNoteView", description = "An investigation note")
public record CaseNoteView(Long id, String author, String note, Instant createdAt) {

    public static CaseNoteView from(CaseNote note) {
        return new CaseNoteView(
                note.getId(), note.getAuthor(), note.getNote(), note.getNoteCreatedAt());
    }
}
