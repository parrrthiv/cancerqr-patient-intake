package com.oncology.intake.repository;

import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.TumorBoardReview;
import com.oncology.intake.entity.TumorBoardReview.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TumorBoardReviewRepository extends JpaRepository<TumorBoardReview, UUID> {

    List<TumorBoardReview> findByPatientId(UUID patientId);

    List<TumorBoardReview> findByDoctorId(UUID doctorId);

    List<TumorBoardReview> findByDoctorIdAndStatus(UUID doctorId, ReviewStatus status);

    Optional<TumorBoardReview> findByPatientIdAndPhysicianDomain(UUID patientId, PhysicianDomain domain);

    @Query("SELECT COUNT(r) FROM TumorBoardReview r WHERE r.patient.id = :patientId AND r.status = 'COMPLETED'")
    long countCompletedReviewsForPatient(@Param("patientId") UUID patientId);

    @Query("SELECT r FROM TumorBoardReview r WHERE r.doctor.id = :doctorId AND r.status = 'PENDING' ORDER BY r.createdAt ASC")
    List<TumorBoardReview> findPendingReviewsForDoctor(@Param("doctorId") UUID doctorId);

    @Query("SELECT DISTINCT r.patient.id FROM TumorBoardReview r WHERE r.status = 'PENDING'")
    List<UUID> findPatientIdsWithPendingReviews();
}
