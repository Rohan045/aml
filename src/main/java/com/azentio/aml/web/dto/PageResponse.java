package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope.
 *
 * <p>Spring's {@code Page} serialises its internal structure, which is neither documented nor
 * stable across versions; pinning the contract here means a Spring upgrade cannot silently reshape
 * every list response the dashboard consumes.
 */
@Schema(name = "PageResponse", description = "A page of results with its pagination metadata")
public record PageResponse<T>(
        List<T> content,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "137") long totalElements,
        @Schema(example = "7") int totalPages,
        boolean first,
        boolean last) {

    /** Maps the entities of a {@link Page} into DTOs while preserving the pagination metadata. */
    public static <E, D> PageResponse<D> of(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
