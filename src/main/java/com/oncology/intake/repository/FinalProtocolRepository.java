package com.oncology.intake.repository;

import com.oncology.intake.entity.FinalProtocol;
import com.oncology.intake.entity.FinalProtocol.ProtocolStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinalProtocolRepository extends JpaRepository<FinalProtocol, UUID> {

    Optional<FinalProtocol> findByPatientId(UUID patientId);

    /** Batched lookup for the dashboard list view — see TumorBoardReviewRepository. */
    List<FinalProtocol> findByPatientIdIn(Collection<UUID> patientIds);

    List<FinalProtocol> findByStatus(ProtocolStatus status);

    List<FinalProtocol> findBySentToPatientFalseAndStatus(ProtocolStatus status);
}
