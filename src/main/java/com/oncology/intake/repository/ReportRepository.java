package com.oncology.intake.repository;

import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.PhiReviewStatus;
import com.oncology.intake.entity.Report.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Report entity operations.
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    /**
     * Find all reports for a patient
     */
    List<Report> findByPatientId(UUID patientId);

    /**
     * Find reports by patient and type
     */
    List<Report> findByPatientIdAndReportType(UUID patientId, ReportType reportType);

    /**
     * Check if patient has specific report type
     */
    boolean existsByPatientIdAndReportType(UUID patientId, ReportType reportType);

    /**
     * Find report by WhatsApp media ID
     */
    Optional<Report> findByWhatsappMediaId(String whatsappMediaId);

    /**
     * Find unprocessed reports
     */
    List<Report> findByProcessedFalse();

    /**
     * Find reports awaiting / by a specific PHI review status. Used by the
     * admin redaction queue at /dashboard/reports/phi-review (PR 13).
     */
    List<Report> findByPhiReviewStatusOrderByUploadedAtAsc(PhiReviewStatus status);

    /**
     * Count reports by type
     */
    long countByReportType(ReportType reportType);

    /**
     * Find reports uploaded within date range
     */
    List<Report> findByUploadedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Get storage locations for cleanup
     */
    @Query("SELECT r.storageLocation FROM Report r WHERE r.patient.id = :patientId")
    List<String> findStorageLocationsByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find latest report of each type for a patient
     */
    @Query("SELECT r FROM Report r WHERE r.patient.id = :patientId " +
           "AND r.uploadedAt = (SELECT MAX(r2.uploadedAt) FROM Report r2 " +
           "WHERE r2.patient.id = :patientId AND r2.reportType = r.reportType)")
    List<Report> findLatestReportsByPatientId(@Param("patientId") UUID patientId);
}
