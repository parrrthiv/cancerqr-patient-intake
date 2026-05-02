package com.oncology.intake.repository;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    
    Optional<Doctor> findByUsername(String username);
    
    Optional<Doctor> findByDomain(PhysicianDomain domain);
    
    boolean existsByUsername(String username);

    Optional<Doctor> findByReferralCode(String referralCode);
}
