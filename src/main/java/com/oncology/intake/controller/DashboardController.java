package com.oncology.intake.controller;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.entity.*;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.repository.*;
import com.oncology.intake.service.TumorBoardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * Controller for Tumor Board Dashboard.
 * Provides UI for doctors to review patient cases.
 */
@Controller
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final TumorBoardReviewRepository reviewRepository;
    private final FinalProtocolRepository protocolRepository;
    private final TumorBoardService tumorBoardService;
    private final CancerQRProtocolConfig protocolConfig;

    /**
     * Login page
     */
    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model) {
        if (session.getAttribute("doctorId") != null) {
            return "redirect:/dashboard";
        }
        
        // Get all doctors for login dropdown
        List<Doctor> doctors = doctorRepository.findAll();
        model.addAttribute("doctors", doctors);
        model.addAttribute("domains", PhysicianDomain.values());
        
        return "dashboard/login";
    }

    /**
     * Process login
     */
    @PostMapping("/login")
    public String login(@RequestParam String username, 
                        @RequestParam String password,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
        
        Optional<Doctor> doctorOpt = doctorRepository.findByUsername(username);
        
        if (doctorOpt.isPresent() && doctorOpt.get().getPassword().equals(password)) {
            Doctor doctor = doctorOpt.get();
            session.setAttribute("doctorId", doctor.getId());
            session.setAttribute("doctorName", doctor.getFullName());
            session.setAttribute("doctorDomain", doctor.getDomain());
            
            log.info("Doctor logged in: {} ({})", doctor.getFullName(), doctor.getDomain());
            return "redirect:/dashboard";
        }
        
        redirectAttributes.addFlashAttribute("error", "Invalid credentials");
        return "redirect:/dashboard/login";
    }

    /**
     * Logout
     */
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/dashboard/login";
    }

    /**
     * Main dashboard
     */
    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null) {
            session.invalidate();
            return "redirect:/dashboard/login";
        }

        // Get pending reviews for this doctor's domain
        List<TumorBoardReview> pendingReviews = tumorBoardService
                .getUnassignedReviewsForDomain(doctor.getDomain());
        
        // Get reviews assigned to this doctor
        List<TumorBoardReview> myReviews = reviewRepository.findByDoctorId(doctorId);
        
        // Stats
        long pendingCount = myReviews.stream()
                .filter(r -> r.getStatus() == TumorBoardReview.ReviewStatus.PENDING 
                          || r.getStatus() == TumorBoardReview.ReviewStatus.IN_PROGRESS)
                .count();
        long completedCount = myReviews.stream()
                .filter(r -> r.getStatus() == TumorBoardReview.ReviewStatus.COMPLETED)
                .count();

        model.addAttribute("doctor", doctor);
        model.addAttribute("pendingReviews", pendingReviews);
        model.addAttribute("myReviews", myReviews);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("completedCount", completedCount);
        model.addAttribute("unassignedCount", pendingReviews.size());

        return "dashboard/index";
    }

    /**
     * View patient case for review
     */
    @GetMapping("/patient/{patientId}")
    public String viewPatient(@PathVariable UUID patientId,
                              HttpSession session,
                              Model model) {
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
        Patient patient = patientRepository.findById(patientId).orElse(null);
        
        if (doctor == null || patient == null) {
            return "redirect:/dashboard";
        }

        // Get or create review for this doctor's domain
        TumorBoardReview review = reviewRepository
                .findByPatientIdAndPhysicianDomain(patientId, doctor.getDomain())
                .orElse(null);

        // Get review status for all domains
        Map<String, Object> reviewStatus = tumorBoardService.getReviewStatusForPatient(patientId);

        // Get protocol defaults for this cancer type
        String cancerTypeId = patient.getCancerType() != null ? 
                patient.getCancerType().toUpperCase().replace(" ", "_") : "BREAST_CANCER";
        
        CancerQRProtocolConfig.CancerProtocol cancerProtocol = null;
        CancerQRProtocolConfig.PhysicianProtocol physicianProtocol = null;
        
        if (protocolConfig.getCancerProtocols() != null) {
            cancerProtocol = protocolConfig.getCancerProtocols().get(cancerTypeId);
            if (cancerProtocol != null && cancerProtocol.getPhysicians() != null) {
                physicianProtocol = cancerProtocol.getPhysicians().get(doctor.getDomain().name());
            }
        }

        // Extract saved data from review if exists
        List<String> savedEcs = null;
        String savedDiet = null;
        String savedFasting = null;
        List<String> savedMushrooms = null;
        List<String> savedHerbs = null;
        List<String> savedDrugs = null;
        String savedSpecialty = null;
        
        if (review != null && review.getSelectedProtocols() != null) {
            Map<String, Object> protocols = review.getSelectedProtocols();
            savedEcs = getListFromMap(protocols, "ecsDefault");
            savedDiet = (String) protocols.get("diet");
            savedFasting = (String) protocols.get("fasting");
            savedMushrooms = getListFromMap(protocols, "mushrooms");
            savedHerbs = getListFromMap(protocols, "herbs");
            savedDrugs = getListFromMap(protocols, "repurposedDrugs");
            
            // Handle specialty - could be list or string
            Object specialtyObj = protocols.get("specialty");
            if (specialtyObj instanceof List) {
                savedSpecialty = String.join(", ", (List<String>) specialtyObj);
            } else if (specialtyObj instanceof String) {
                savedSpecialty = (String) specialtyObj;
            }
        }

        model.addAttribute("doctor", doctor);
        model.addAttribute("patient", patient);
        model.addAttribute("review", review);
        model.addAttribute("reviewStatus", reviewStatus);
        model.addAttribute("cancerProtocol", cancerProtocol);
        model.addAttribute("physicianProtocol", physicianProtocol);
        model.addAttribute("masterLists", protocolConfig.getMasterLists());
        
        // Add saved data
        model.addAttribute("savedEcs", savedEcs);
        model.addAttribute("savedDiet", savedDiet);
        model.addAttribute("savedFasting", savedFasting);
        model.addAttribute("savedMushrooms", savedMushrooms);
        model.addAttribute("savedHerbs", savedHerbs);
        model.addAttribute("savedDrugs", savedDrugs);
        model.addAttribute("savedSpecialty", savedSpecialty);

        return "dashboard/patient-review";
    }
    
    @SuppressWarnings("unchecked")
    private List<String> getListFromMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return null;
    }

    /**
     * Submit review
     */
    @PostMapping("/patient/{patientId}/review")
    public String submitReview(@PathVariable UUID patientId,
                               @RequestParam(required = false) List<String> ecsDefault,
                               @RequestParam(required = false) List<String> ecsOptional,
                               @RequestParam(required = false) String diet,
                               @RequestParam(required = false) String fasting,
                               @RequestParam(required = false) List<String> mushrooms,
                               @RequestParam(required = false) List<String> herbs,
                               @RequestParam(required = false) List<String> repurposedDrugs,
                               @RequestParam(required = false) List<String> specialty,
                               @RequestParam(required = false) String notes,
                               @RequestParam(required = false) String recommendations,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null) {
            return "redirect:/dashboard";
        }

        // Find or create review
        TumorBoardReview review = reviewRepository
                .findByPatientIdAndPhysicianDomain(patientId, doctor.getDomain())
                .orElseThrow(() -> new RuntimeException("Review not found"));

        // Assign doctor if not already
        if (review.getDoctor() == null) {
            review.setDoctor(doctor);
        }

        // Build protocols map
        Map<String, Object> protocols = new HashMap<>();
        protocols.put("ecsDefault", ecsDefault != null ? ecsDefault : List.of());
        protocols.put("ecsOptional", ecsOptional != null ? ecsOptional : List.of());
        protocols.put("diet", diet);
        protocols.put("fasting", fasting);
        protocols.put("lifestyle", "No Sugar");
        protocols.put("mushrooms", mushrooms != null ? mushrooms : List.of());
        protocols.put("herbs", herbs != null ? herbs : List.of());
        protocols.put("repurposedDrugs", repurposedDrugs != null ? repurposedDrugs : List.of());
        protocols.put("specialty", specialty != null ? specialty : List.of());

        tumorBoardService.submitReview(review.getId(), protocols, notes, recommendations);

        redirectAttributes.addFlashAttribute("success", "Review submitted successfully!");
        return "redirect:/dashboard";
    }

    /**
     * View all patients (admin view)
     */
    @GetMapping("/patients")
    public String allPatients(HttpSession session, Model model) {
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        List<Patient> patients = patientRepository.findAll();
        
        // Add review status for each patient
        List<Map<String, Object>> patientData = new ArrayList<>();
        for (Patient patient : patients) {
            Map<String, Object> data = new HashMap<>();
            data.put("patient", patient);
            data.put("reviewStatus", tumorBoardService.getReviewStatusForPatient(patient.getId()));
            
            // Check for final protocol
            Optional<FinalProtocol> protocol = protocolRepository.findByPatientId(patient.getId());
            data.put("hasProtocol", protocol.isPresent());
            data.put("protocolStatus", protocol.map(FinalProtocol::getStatus).orElse(null));
            
            patientData.add(data);
        }

        model.addAttribute("patientData", patientData);
        return "dashboard/patients";
    }

    /**
     * View final protocol
     */
    @GetMapping("/protocol/{patientId}")
    public String viewProtocol(@PathVariable UUID patientId,
                               HttpSession session,
                               Model model) {
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        Patient patient = patientRepository.findById(patientId).orElse(null);
        FinalProtocol protocol = protocolRepository.findByPatientId(patientId).orElse(null);
        List<TumorBoardReview> reviews = reviewRepository.findByPatientId(patientId);

        model.addAttribute("patient", patient);
        model.addAttribute("protocol", protocol);
        model.addAttribute("reviews", reviews);

        return "dashboard/protocol";
    }

    /**
     * Approve final protocol
     */
    @PostMapping("/protocol/{protocolId}/approve")
    public String approveProtocol(@PathVariable UUID protocolId,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {
        UUID doctorId = (UUID) session.getAttribute("doctorId");
        if (doctorId == null) {
            return "redirect:/dashboard/login";
        }

        FinalProtocol protocol = tumorBoardService.approveFinalProtocol(protocolId);
        
        redirectAttributes.addFlashAttribute("success", "Protocol approved!");
        return "redirect:/dashboard/protocol/" + protocol.getPatient().getId();
    }
}
