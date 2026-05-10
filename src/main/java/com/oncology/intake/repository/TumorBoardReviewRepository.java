package com.oncology.intake.repository;

import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.TumorBoardReview;
import com.oncology.intake.entity.TumorBoardReview.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TumorBoardReviewRepository extends JpaRepository<TumorBoardReview, UUID> {

    List<TumorBoardReview> findByPatientId(UUID patientId);

    /**
     * Batched lookup for the dashboard list view — one query for many patients,
     * lets callers build per-patient summaries without N+1.
     */
    List<TumorBoardReview> findByPatientIdIn(Collection<UUID> patientIds);

    List<TumorBoardReview> findByDoctorId(UUID doctorId);

    List<TumorBoardReview> findByDoctorIdAndStatus(UUID doctorId, ReviewStatus status);

    Optional<TumorBoardReview> findByPatientIdAndPhysicianDomain(UUID patientId, PhysicianDomain domain);

    /**
     * Replaces the previous {@code findAll().stream().filter(...)} pattern in
     * {@code TumorBoardService.getUnassignedReviewsForDomain} — does the work
     * in SQL instead of streaming the entire reviews table into memory.
     */
    List<TumorBoardReview> findByPhysicianDomainAndDoctorIsNullAndStatus(
            PhysicianDomain domain, ReviewStatus status);

    @Query("SELECT COUNT(r) FROM TumorBoardReview r WHERE r.patient.id = :patientId AND r.status = 'COMPLETED'")
    long countCompletedReviewsForPatient(@Param("patientId") UUID patientId);

    @Query("SELECT r FROM TumorBoardReview r WHERE r.doctor.id = :doctorId AND r.status = 'PENDING' ORDER BY r.createdAt ASC")
    List<TumorBoardReview> findPendingReviewsForDoctor(@Param("doctorId") UUID doctorId);

    @Query("SELECT DISTINCT r.patient.id FROM TumorBoardReview r WHERE r.status = 'PENDING'")
    List<UUID> findPatientIdsWithPendingReviews();
}
