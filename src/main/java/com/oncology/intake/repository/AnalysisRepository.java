package com.oncology.intake.repository;

import com.oncology.intake.entity.Analysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Analysis entity operations.
 */
@Repository
public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    /**
     * Find all analyses for a patient
     */
    List<Analysis> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /**
     * Find latest analysis for a patient
     */
    Optional<Analysis> findFirstByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /**
     * Find analyses by formula version
     */
    List<Analysis> findByFormulaVersion(String formulaVersion);

    /**
     * Find unsent analyses
     */
    List<Analysis> findBySentToPatientFalse();

    /**
     * Find analyses requiring urgent review
     */
    List<Analysis> findByRequiresUrgentReviewTrueAndReviewedByPhysicianFalse();

    /**
     * Find unreviewed analyses
     */
    List<Analysis> findByReviewedByPhysicianFalse();

    /**
     * Count analyses by formula version
     */
    long countByFormulaVersion(String formulaVersion);

    /**
     * Find analyses created within date range
     */
    List<Analysis> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Get analysis statistics
     */
    @Query("SELECT a.formulaVersion, COUNT(a), " +
           "SUM(CASE WHEN a.requiresUrgentReview = true THEN 1 ELSE 0 END) " +
           "FROM Analysis a GROUP BY a.formulaVersion")
    List<Object[]> getAnalysisStatsByFormulaVersion();

    /**
     * Find analyses for patients with high pain scores
     */
    @Query("SELECT a FROM Analysis a WHERE a.patient.painScale >= :painThreshold " +
           "ORDER BY a.createdAt DESC")
    List<Analysis> findAnalysesForHighPainPatients(@Param("painThreshold") int painThreshold);
}
