package com.azentio.aml.repository;

import com.azentio.aml.domain.CaseNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only investigation notes. There is deliberately no update or delete path. */
public interface CaseNoteRepository extends JpaRepository<CaseNote, Long> {

    List<CaseNote> findByAmlCase_IdOrderByNoteCreatedAtAsc(Long caseId);

    long countByAmlCase_Id(Long caseId);
}
