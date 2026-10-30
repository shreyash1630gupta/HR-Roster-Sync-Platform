package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ReconciliationCandidate;
import com.moveinsync.roster.domain.enums.CandidateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationCandidateRepository extends JpaRepository<ReconciliationCandidate, UUID> {
    List<ReconciliationCandidate> findByRunIdAndCandidateType(UUID runId, CandidateType candidateType);
}

