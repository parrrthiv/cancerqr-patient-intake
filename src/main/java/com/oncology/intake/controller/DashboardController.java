package com.oncology.intake.controller;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.entity.*;
import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.repository.*;
import com.oncology.intake.security.DoctorPrincipal;
import com.oncology.intake.security.PatientAccessService;
import com.oncology.intake.security.WhatsAppNumberHasher;
import com.oncology.intake.service.AuditService;
import com.oncology.intake.service.PatientIntakeService;
import com.oncology.intake.service.PatientMessageService;
import com.oncology.intake.service.ReportDataExtractionAsyncRunner;
import com.oncology.intake.service.StorageService;
import com.oncology.intake.service.TumorBoardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Controller for the Tumor Board dashboard and admin/referring-doctor flows.
 *
 * Authentication is handled by Spring Security (see {@code SecurityConfig}).
 * Spring guarantees that:
 *  - GET/POST {@code /dashboard/login} are routed through the security filter, not this controller.
 *  - GET {@code /dashboard/logout} is handled by Spring's logout filter.
 *  - Every other {@code /dashboard/**} method runs only with a non-null
 *    {@link DoctorPrincipal}, so methods do not need to re-check.
 *
 * Role-restricted routes (admin-only doctor management, referring-doctor-only
 * "add patient") are enforced by the SecurityConfig request matchers, not by
 * controller code. The role checks left in helpers are belt-and-braces.
 */
@Controller
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    // Server-side validation for the admin / referring-doctor forms. The HTML
    // input constraints (min/max, <select>, type=date) are advisory only and
    // trivially bypassable, so every POST handler re-validates here.
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9][0-9\\s\\-()]{6,19}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final ReportRepository reportRepository;
    private final AnalysisRepository analysisRepository;
    private final TumorBoardReviewRepository reviewRepository;
    private final FinalProtocolRepository protocolRepository;
    private final TumorBoardService tumorBoardService;
    private final StorageService storageService;
    private final ReportDataExtractionAsyncRunner reportExtractionRunner;
    private final CancerQRProtocolConfig protocolConfig;
    private final PatientIntakeService patientIntakeService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PatientAccessService patientAccessService;
    private final WhatsAppNumberHasher whatsAppNumberHasher;
    private final PatientMessageService patientMessageService;

    private static final String REFERRAL_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Login page. Spring Security handles the POST; this method only renders the GET.
     * If the user is already logged in, redirect to dashboard home.
     */
    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal DoctorPrincipal principal, Model model) {
        if (principal != null) {
            return "redirect:/dashboard";
        }
        return "dashboard/login";
    }

    /**
     * Main dashboard
     */
    @GetMapping
    public String dashboard(@AuthenticationPrincipal DoctorPrincipal principal, Model model) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        if (doctor == null) {
            // Account deleted while session was alive — force re-login
            return "redirect:/dashboard/logout";
        }

        if (doctor.getDomain() == PhysicianDomain.REFERRING_DOCTOR) {
            List<Patient> myPatients = patientRepository.findByReferringDoctorId(doctor.getId());
            model.addAttribute("doctor", doctor);
            model.addAttribute("myPatients", myPatients);
            model.addAttribute("patientCount", myPatients.size());
            return "dashboard/index";
        }

        List<TumorBoardReview> pendingReviews = tumorBoardService
                .getUnassignedReviewsForDomain(doctor.getDomain());
        List<TumorBoardReview> myReviews = reviewRepository.findByDoctorId(doctor.getId());

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
                              @AuthenticationPrincipal DoctorPrincipal principal,
                              Model model) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        Patient patient = patientRepository.findById(patientId).orElse(null);

        if (doctor == null || patient == null) {
            return "redirect:/dashboard";
        }

        // Authorization: not "is logged in" but "is allowed to view THIS patient".
        if (!patientAccessService.canViewPatient(doctor, patient)) {
            log.warn("Doctor id={} domain={} denied access to patient id={}",
                    doctor.getId(), doctor.getDomain(), patientId);
            return "redirect:/dashboard";
        }

        // Message history (doctor → patient) for the messaging card; both the
        // referring-doctor view and the board view render it.
        model.addAttribute("patientMessages",
                patientMessageService.messagesForPatient(patientId));
        model.addAttribute("maxMessageChars", PatientMessageService.MAX_BODY_CHARS);

        if (doctor.getDomain() == PhysicianDomain.REFERRING_DOCTOR) {
            // Referring doctors get the read-only view (no protocol selection forms).
            model.addAttribute("doctor", doctor);
            model.addAttribute("patient", patient);
            model.addAttribute("readOnly", true);
            List<Report> reports = reportRepository.findByPatientId(patientId);
            model.addAttribute("reports", reports);
            // Only ADMIN may open un-approved reports; the template gates the
            // download link accordingly (the endpoint enforces it regardless).
            model.addAttribute("isAdmin", doctor.getDomain() == PhysicianDomain.ADMIN);
            Analysis latestAnalysis = analysisRepository
                    .findFirstByPatientIdOrderByCreatedAtDesc(patientId).orElse(null);
            model.addAttribute("analysis", latestAnalysis);
            Map<String, Object> reviewStatus = tumorBoardService.getReviewStatusForPatient(patientId);
            model.addAttribute("reviewStatus", reviewStatus);
            return "dashboard/patient-review";
        }

        // Fire-and-forget: if extraction never ran (or failed) for this patient,
        // kick it off now. PDFBox parsing is slow; running it inline on the
        // request thread froze the dashboard for any patient whose extraction
        // hadn't completed. Doctor may need to refresh once for values to appear.
        if (patient.getCancerStage() == null
                && patient.getEsrValue() == null
                && patient.getCrpValue() == null) {
            reportExtractionRunner.runForPatient(patientId);
        }

        TumorBoardReview review = reviewRepository
                .findByPatientIdAndPhysicianDomain(patientId, doctor.getDomain())
                .orElse(null);

        Map<String, Object> reviewStatus = tumorBoardService.getReviewStatusForPatient(patientId);

        String cancerTypeId = patient.getCancerType() != null
                ? patient.getCancerType().toUpperCase().replace(" ", "_")
                : "BREAST_CANCER";

        CancerQRProtocolConfig.CancerProtocol cancerProtocol = null;
        CancerQRProtocolConfig.PhysicianProtocol physicianProtocol = null;

        if (protocolConfig.getCancerProtocols() != null) {
            cancerProtocol = protocolConfig.getCancerProtocols().get(cancerTypeId);
            if (cancerProtocol != null && cancerProtocol.getPhysicians() != null) {
                physicianProtocol = cancerProtocol.getPhysicians().get(doctor.getDomain().name());
            }
        }

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

            Object specialtyObj = protocols.get("specialty");
            if (specialtyObj instanceof List) {
                savedSpecialty = String.join(", ", (List<String>) specialtyObj);
            } else if (specialtyObj instanceof String) {
                savedSpecialty = (String) specialtyObj;
            }
        }

        List<Report> reports = reportRepository.findByPatientId(patientId);
        Analysis latestAnalysis = analysisRepository
                .findFirstByPatientIdOrderByCreatedAtDesc(patientId).orElse(null);

        model.addAttribute("doctor", doctor);
        model.addAttribute("patient", patient);
        model.addAttribute("reports", reports);
        // Only ADMIN may open un-approved reports; the template gates the download
        // link accordingly (the endpoint enforces it regardless).
        model.addAttribute("isAdmin", doctor.getDomain() == PhysicianDomain.ADMIN);
        model.addAttribute("analysis", latestAnalysis);
        model.addAttribute("review", review);
        model.addAttribute("reviewStatus", reviewStatus);
        model.addAttribute("cancerProtocol", cancerProtocol);
        model.addAttribute("physicianProtocol", physicianProtocol);
        model.addAttribute("masterLists", protocolConfig.getMasterLists());

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
                               @AuthenticationPrincipal DoctorPrincipal principal,
                               RedirectAttributes redirectAttributes) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        Patient patient = patientRepository.findById(patientId).orElse(null);

        // Authorization: must be allowed to view this patient AND must have a
        // review row for this doctor's domain. (Was: 500 with "Review not found"
        // when those weren't true — exposed a stack trace and surprised the user.)
        if (doctor == null || patient == null
                || !patientAccessService.canViewPatient(doctor, patient)) {
            log.warn("Doctor id={} denied submitReview for patient id={}",
                    principal.getId(), patientId);
            return "redirect:/dashboard";
        }

        Optional<TumorBoardReview> reviewOpt = reviewRepository
                .findByPatientIdAndPhysicianDomain(patientId, doctor.getDomain());
        if (reviewOpt.isEmpty()) {
            log.warn("Doctor id={} domain={} has no review row for patient id={}",
                    doctor.getId(), doctor.getDomain(), patientId);
            redirectAttributes.addFlashAttribute("error",
                    "No review task exists for your domain on this patient.");
            return "redirect:/dashboard";
        }
        TumorBoardReview review = reviewOpt.get();

        if (review.getDoctor() == null) {
            review.setDoctor(doctor);
        }

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
     * Send a message to the patient (shown in the patient portal, mirrored to
     * WhatsApp best-effort). Authorization mirrors every other per-patient
     * route: the doctor must pass {@code PatientAccessService.canViewPatient}.
     */
    @PostMapping("/patient/{patientId}/message")
    public String sendMessageToPatient(@PathVariable UUID patientId,
                                       @RequestParam String body,
                                       @AuthenticationPrincipal DoctorPrincipal principal,
                                       RedirectAttributes redirectAttributes) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        Patient patient = patientRepository.findById(patientId).orElse(null);

        if (doctor == null || patient == null
                || !patientAccessService.canViewPatient(doctor, patient)) {
            log.warn("Doctor id={} denied sendMessage for patient id={}",
                    principal.getId(), patientId);
            return "redirect:/dashboard";
        }

        try {
            patientMessageService.sendToPatient(doctor, patient, body);
            redirectAttributes.addFlashAttribute("success",
                    "Message sent. The patient will see it in their portal (and on WhatsApp if reachable).");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard/patient/" + patientId;
    }

    /**
     * View/download a report file.
     *
     * Forces {@code attachment} disposition + {@code application/octet-stream} +
     * {@code X-Content-Type-Options: nosniff} so a malicious upload
     * cannot render as HTML/SVG inside the dashboard origin.
     */
    @GetMapping("/reports/{reportId}/view")
    @ResponseBody
    public ResponseEntity<byte[]> viewReport(@PathVariable UUID reportId,
                                             @AuthenticationPrincipal DoctorPrincipal principal) {
        Report report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }

        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        if (doctor == null || !patientAccessService.canViewReport(doctor, report)) {
            log.warn("Doctor id={} denied access to report id={}",
                    principal.getId(), reportId);
            // 404 not 403 — don't confirm the report exists to a caller without access.
            return ResponseEntity.notFound().build();
        }

        byte[] fileBytes = storageService.retrieveFile(report.getStorageLocation());

        String safeFileName = report.getFileName() == null
                ? "report.bin"
                : report.getFileName().replaceAll("[\r\n\"\\\\]", "_");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(fileBytes.length)
                .body(fileBytes);
    }

    /**
     * View all patients (admin / non-referring view; referring doctors see only their own).
     *
     * <p>Paginated to cap memory + render time. Defaults: page=0, size=50. Override via
     * query params {@code ?page=N&size=M}. {@code size} is hard-capped at 200 to prevent
     * abusive requests.
     */
    @GetMapping("/patients")
    public String allPatients(@AuthenticationPrincipal DoctorPrincipal principal,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              Model model) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        if (doctor == null) {
            return "redirect:/dashboard/logout";
        }

        // Defensive caps: reject obvious abuse without erroring out.
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("createdAt").descending());

        // Visibility rules mirror PatientAccessService.canViewPatient(...):
        //   ADMIN              → every patient
        //   REFERRING_DOCTOR   → only patients they referred
        //   any other domain   → only patients in the tumor board queue
        org.springframework.data.domain.Page<Patient> patientPage;
        switch (doctor.getDomain()) {
            case ADMIN -> patientPage = patientRepository.findAll(pageable);
            case REFERRING_DOCTOR -> patientPage =
                    patientRepository.findByReferringDoctorId(doctor.getId(), pageable);
            default -> patientPage = patientRepository.findAllInTumorBoard(pageable);
        }
        List<Patient> patients = patientPage.getContent();

        model.addAttribute("doctor", doctor);

        // Avoid N+1: fetch all reviews and protocols for the visible patients in
        // two queries, then assemble per-patient view models in memory. Previously
        // each patient triggered 2 extra queries (review list + final protocol).
        List<UUID> patientIds = patients.stream().map(Patient::getId).toList();

        Map<UUID, List<TumorBoardReview>> reviewsByPatient =
                patientIds.isEmpty()
                        ? Map.of()
                        : reviewRepository.findByPatientIdIn(patientIds).stream()
                                .collect(Collectors.groupingBy(r -> r.getPatient().getId()));

        Map<UUID, FinalProtocol> protocolByPatient =
                patientIds.isEmpty()
                        ? Map.of()
                        : protocolRepository.findByPatientIdIn(patientIds).stream()
                                .collect(Collectors.toMap(
                                        p -> p.getPatient().getId(),
                                        p -> p,
                                        (a, b) -> a));

        List<Map<String, Object>> patientData = new ArrayList<>(patients.size());
        for (Patient patient : patients) {
            Map<String, Object> data = new HashMap<>();
            data.put("patient", patient);

            List<TumorBoardReview> reviews = reviewsByPatient.getOrDefault(patient.getId(), List.of());
            data.put("reviewStatus", TumorBoardService.buildReviewStatus(reviews));

            FinalProtocol protocol = protocolByPatient.get(patient.getId());
            data.put("hasProtocol", protocol != null);
            data.put("protocolStatus", protocol != null ? protocol.getStatus() : null);

            patientData.add(data);
        }

        model.addAttribute("patientData", patientData);
        // Pagination metadata for the template (optional to render — list still works without it).
        model.addAttribute("currentPage", patientPage.getNumber());
        model.addAttribute("totalPages", patientPage.getTotalPages());
        model.addAttribute("totalElements", patientPage.getTotalElements());
        model.addAttribute("pageSize", patientPage.getSize());
        return "dashboard/patients";
    }

    /**
     * View final protocol
     */
    @GetMapping("/protocol/{patientId}")
    public String viewProtocol(@PathVariable UUID patientId,
                               @AuthenticationPrincipal DoctorPrincipal principal,
                               Model model) {
        Doctor doctor = doctorRepository.findById(principal.getId()).orElse(null);
        Patient patient = patientRepository.findById(patientId).orElse(null);

        if (doctor == null || patient == null
                || !patientAccessService.canViewPatient(doctor, patient)) {
            log.warn("Doctor id={} denied access to protocol for patient id={}",
                    principal.getId(), patientId);
            return "redirect:/dashboard";
        }

        FinalProtocol protocol = protocolRepository.findByPatientId(patientId).orElse(null);
        List<TumorBoardReview> reviews = reviewRepository.findByPatientId(patientId);

        model.addAttribute("patient", patient);
        model.addAttribute("protocol", protocol);
        model.addAttribute("reviews", reviews);
        // Only finalize-capable doctors see the approve/send actions (the routes
        // are CAN_FINALIZE-gated regardless).
        model.addAttribute("canFinalize", principal.isCanFinalize());

        return "dashboard/protocol";
    }

    // ── Add Patient (intake-capable doctors) ────────────────────────────
    // CAN_INTAKE enforced by SecurityConfig; helpers keep belt-and-braces checks.
    // The intaking doctor is recorded as the patient's referringDoctor.

    @GetMapping("/patients/add")
    public String addPatientForm(@AuthenticationPrincipal DoctorPrincipal principal, Model model) {
        Doctor doctor = requireIntakeCapable(principal);
        if (doctor == null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("doctor", doctor);
        model.addAttribute("cancerProtocols", protocolConfig.getCancerProtocols());
        return "dashboard/add-patient";
    }

    @PostMapping("/patients/add")
    public String addPatient(@RequestParam String name,
                             @RequestParam String whatsappNumber,
                             @RequestParam String cancerType,
                             @RequestParam(required = false) Integer age,
                             @RequestParam(required = false) BigDecimal weightKg,
                             @RequestParam(required = false) Integer painScale,
                             @RequestParam(required = false) String diagnosisDate,
                             @RequestParam(name = "consentAttested", required = false) Boolean consentAttested,
                             @AuthenticationPrincipal DoctorPrincipal principal,
                             RedirectAttributes redirectAttributes) {
        Doctor doctor = requireIntakeCapable(principal);
        if (doctor == null) {
            return "redirect:/dashboard";
        }

        // Consent attestation: the form has a required checkbox, but trust nothing
        // from the client. If the doctor didn't tick it, refuse to create the record.
        if (!Boolean.TRUE.equals(consentAttested)) {
            redirectAttributes.addFlashAttribute("error",
                    "You must attest that you have obtained the patient's informed consent.");
            return "redirect:/dashboard/patients/add";
        }

        // Validate server-side, mirroring the WhatsApp intake bounds. The form's
        // HTML constraints are advisory only.
        List<String> errors = new ArrayList<>();
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty() || trimmedName.length() > 100) {
            errors.add("Patient name is required (max 100 characters).");
        }
        if (whatsappNumber == null || !PHONE_PATTERN.matcher(whatsappNumber.trim()).matches()) {
            errors.add("Enter a valid WhatsApp number (e.g. +919876543210).");
        }
        if (!isKnownCancerType(cancerType)) {
            errors.add("Please select a valid cancer type.");
        }
        if (age != null && (age < 0 || age > 120)) {
            errors.add("Age must be between 0 and 120.");
        }
        if (weightKg != null
                && (weightKg.compareTo(BigDecimal.ONE) < 0
                    || weightKg.compareTo(BigDecimal.valueOf(300)) > 0)) {
            errors.add("Weight must be between 1 and 300 kg.");
        }
        if (painScale != null && (painScale < 0 || painScale > 10)) {
            errors.add("Pain scale must be between 0 and 10.");
        }
        LocalDate parsedDiagnosisDate = null;
        if (diagnosisDate != null && !diagnosisDate.isBlank()) {
            try {
                parsedDiagnosisDate = LocalDate.parse(diagnosisDate.trim());
                if (parsedDiagnosisDate.isAfter(LocalDate.now())) {
                    errors.add("Diagnosis date cannot be in the future.");
                }
            } catch (DateTimeParseException e) {
                errors.add("Diagnosis date must be a valid date (YYYY-MM-DD).");
            }
        }
        if (!errors.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", String.join(" ", errors));
            return "redirect:/dashboard/patients/add";
        }

        // Normalise so "+91 98765 43210" and "919876543210" collide on the hash
        // — the same human shouldn't be created twice just because of formatting.
        String normalisedNumber = WhatsAppNumberHasher.normalise(whatsappNumber);
        String numberHash = whatsAppNumberHasher.hash(normalisedNumber);

        if (patientRepository.existsByWhatsappNumberHash(numberHash)) {
            redirectAttributes.addFlashAttribute("error",
                    "A patient with this WhatsApp number already exists.");
            return "redirect:/dashboard/patients/add";
        }

        LocalDateTime now = LocalDateTime.now();
        Patient patient = Patient.builder()
                .name(trimmedName)
                .whatsappNumber(normalisedNumber)
                // whatsappNumberHash is set automatically by PatientHashListener
                .cancerType(cancerType.trim())
                .age(age)
                .weightKg(weightKg)
                .painScale(painScale)
                .diagnosisDate(parsedDiagnosisDate)
                .consentGiven(true)
                .consentTimestamp(now)
                .conversationState(Patient.ConversationState.COMPLETED)
                .intakeCompleted(true)
                .referringDoctor(doctor)
                .build();
        patient = patientRepository.save(patient);

        // Audit trail: record both that the patient was created and that consent
        // was attested by this referring doctor (vs. obtained directly via WhatsApp).
        auditService.logDoctorAction(doctor.getId(), patient.getId(),
                AuditAction.PATIENT_CREATED,
                "Created via referring-doctor add-patient form");
        auditService.logDoctorAction(doctor.getId(), patient.getId(),
                AuditAction.CONSENT_GIVEN,
                "Verbal/written consent attested by referring doctor (not WhatsApp)");

        tumorBoardService.createReviewTasksForPatient(patient.getId());

        log.info("Referring doctor id={} created patient id={}", doctor.getId(), patient.getId());
        redirectAttributes.addFlashAttribute("success",
                "Patient '" + trimmedName + "' added successfully");
        return "redirect:/dashboard/patients";
    }

    /** A doctor who may perform patient intake (CAN_INTAKE capability). */
    private Doctor requireIntakeCapable(DoctorPrincipal principal) {
        if (principal == null || !principal.isCanIntake()) {
            return null;
        }
        return doctorRepository.findById(principal.getId()).orElse(null);
    }

    /** True if {@code cancerType} matches a configured cancer-type name (case-insensitive). */
    private boolean isKnownCancerType(String cancerType) {
        if (cancerType == null || cancerType.isBlank()) {
            return false;
        }
        var protocols = protocolConfig.getCancerProtocols();
        if (protocols == null) {
            return false;
        }
        String candidate = cancerType.trim();
        return protocols.values().stream()
                .anyMatch(p -> p.getName() != null && p.getName().equalsIgnoreCase(candidate));
    }

    /**
     * Shared field validation for the doctor create/update forms. Returns the
     * (possibly empty) list of human-readable errors; password is validated
     * separately because it is required on create but optional on update.
     */
    private List<String> validateDoctorFields(String fullName, String username, String email, String phone) {
        List<String> errors = new ArrayList<>();
        if (fullName == null || fullName.trim().isEmpty() || fullName.trim().length() > 100) {
            errors.add("Full name is required (max 100 characters).");
        }
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            errors.add("Username must be 3-50 characters: letters, digits, dot, underscore or hyphen.");
        }
        if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            errors.add("Email address is not valid.");
        }
        if (phone != null && !phone.isBlank() && !PHONE_PATTERN.matcher(phone.trim()).matches()) {
            errors.add("Phone number is not valid.");
        }
        return errors;
    }

    // ── Doctor Management (Admin Only) ──────────────────────────────────
    // Role enforced by SecurityConfig; helpers keep belt-and-braces checks.

    @GetMapping("/doctors")
    public String listDoctors(@AuthenticationPrincipal DoctorPrincipal principal, Model model) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }

        List<Doctor> doctors = doctorRepository.findAll();
        long activeCount = doctors.stream().filter(Doctor::getActive).count();
        Map<String, Long> byDomain = doctors.stream()
                .collect(Collectors.groupingBy(d -> d.getDomain().getDisplayName(), Collectors.counting()));

        model.addAttribute("admin", admin);
        model.addAttribute("doctors", doctors);
        model.addAttribute("totalCount", doctors.size());
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("byDomain", byDomain);
        model.addAttribute("domains", PhysicianDomain.values());
        model.addAttribute("editDoctor", null);

        return "dashboard/doctors";
    }

    @PostMapping("/doctors")
    public String createDoctor(@RequestParam String fullName,
                               @RequestParam String username,
                               @RequestParam String password,
                               @RequestParam PhysicianDomain domain,
                               @RequestParam(required = false) String email,
                               @RequestParam(required = false) String phone,
                               @RequestParam(name = "canReview", required = false) Boolean canReview,
                               @RequestParam(name = "canIntake", required = false) Boolean canIntake,
                               @RequestParam(name = "canFinalize", required = false) Boolean canFinalize,
                               @AuthenticationPrincipal DoctorPrincipal principal,
                               RedirectAttributes redirectAttributes) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }

        List<String> errors = validateDoctorFields(fullName, username, email, phone);
        if (password == null || password.length() < 8) {
            errors.add("Password must be at least 8 characters.");
        }
        if (!errors.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", String.join(" ", errors));
            return "redirect:/dashboard/doctors";
        }

        if (doctorRepository.existsByUsername(username)) {
            redirectAttributes.addFlashAttribute("error",
                    "Username '" + username + "' already exists");
            return "redirect:/dashboard/doctors";
        }

        Doctor.DoctorBuilder builder = Doctor.builder()
                .fullName(fullName)
                .username(username)
                .password(passwordEncoder.encode(password))
                .domain(domain)
                .email(email)
                .phone(phone)
                .active(true)
                .canReview(Boolean.TRUE.equals(canReview))
                .canIntake(Boolean.TRUE.equals(canIntake))
                .canFinalize(Boolean.TRUE.equals(canFinalize));

        if (domain == PhysicianDomain.REFERRING_DOCTOR) {
            builder.referralCode(generateUniqueReferralCode());
        }

        Doctor doctor = builder.build();
        doctorRepository.save(doctor);

        log.info("Admin id={} created doctor id={} domain={}", admin.getId(), doctor.getId(), domain);
        redirectAttributes.addFlashAttribute("success",
                "Doctor '" + fullName + "' created successfully");
        return "redirect:/dashboard/doctors";
    }

    @GetMapping("/doctors/{id}/edit")
    public String editDoctorForm(@PathVariable UUID id,
                                 @AuthenticationPrincipal DoctorPrincipal principal,
                                 Model model) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }

        Doctor editDoctor = doctorRepository.findById(id).orElse(null);
        if (editDoctor == null) {
            return "redirect:/dashboard/doctors";
        }

        List<Doctor> doctors = doctorRepository.findAll();
        long activeCount = doctors.stream().filter(Doctor::getActive).count();
        Map<String, Long> byDomain = doctors.stream()
                .collect(Collectors.groupingBy(d -> d.getDomain().getDisplayName(), Collectors.counting()));

        model.addAttribute("admin", admin);
        model.addAttribute("doctors", doctors);
        model.addAttribute("totalCount", doctors.size());
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("byDomain", byDomain);
        model.addAttribute("domains", PhysicianDomain.values());
        model.addAttribute("editDoctor", editDoctor);

        return "dashboard/doctors";
    }

    @PostMapping("/doctors/{id}")
    public String updateDoctor(@PathVariable UUID id,
                               @RequestParam String fullName,
                               @RequestParam String username,
                               @RequestParam(required = false) String password,
                               @RequestParam PhysicianDomain domain,
                               @RequestParam(required = false) String email,
                               @RequestParam(required = false) String phone,
                               @RequestParam(name = "canReview", required = false) Boolean canReview,
                               @RequestParam(name = "canIntake", required = false) Boolean canIntake,
                               @RequestParam(name = "canFinalize", required = false) Boolean canFinalize,
                               @AuthenticationPrincipal DoctorPrincipal principal,
                               RedirectAttributes redirectAttributes) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }

        Doctor doctor = doctorRepository.findById(id).orElse(null);
        if (doctor == null) {
            redirectAttributes.addFlashAttribute("error", "Doctor not found");
            return "redirect:/dashboard/doctors";
        }

        List<String> errors = validateDoctorFields(fullName, username, email, phone);
        if (password != null && !password.isBlank() && password.length() < 8) {
            errors.add("Password must be at least 8 characters.");
        }
        if (!errors.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", String.join(" ", errors));
            return "redirect:/dashboard/doctors/" + id + "/edit";
        }

        if (!doctor.getUsername().equals(username) && doctorRepository.existsByUsername(username)) {
            redirectAttributes.addFlashAttribute("error",
                    "Username '" + username + "' already exists");
            return "redirect:/dashboard/doctors/" + id + "/edit";
        }

        doctor.setFullName(fullName);
        doctor.setUsername(username);
        if (password != null && !password.isBlank()) {
            doctor.setPassword(passwordEncoder.encode(password));
        }
        doctor.setDomain(domain);
        // Keep the referral code in sync with the role: generate one when a doctor
        // becomes a Referring Doctor (createDoctor only does this at creation time),
        // and clear it if they move out of that role so a stale code can't be used.
        if (domain == PhysicianDomain.REFERRING_DOCTOR) {
            if (doctor.getReferralCode() == null || doctor.getReferralCode().isBlank()) {
                doctor.setReferralCode(generateUniqueReferralCode());
            }
        } else {
            doctor.setReferralCode(null);
        }
        doctor.setEmail(email);
        doctor.setPhone(phone);
        doctor.setCanReview(Boolean.TRUE.equals(canReview));
        doctor.setCanIntake(Boolean.TRUE.equals(canIntake));
        doctor.setCanFinalize(Boolean.TRUE.equals(canFinalize));
        doctorRepository.save(doctor);

        log.info("Admin id={} updated doctor id={} domain={}", admin.getId(), doctor.getId(), domain);
        redirectAttributes.addFlashAttribute("success",
                "Doctor '" + fullName + "' updated successfully");
        return "redirect:/dashboard/doctors";
    }

    /**
     * Regenerate a referring doctor's referral code (admin only). Useful when a
     * code is leaked or a doctor needs a fresh one. Role is also enforced by
     * SecurityConfig's /dashboard/doctors/** matcher; this is belt-and-braces.
     */
    @PostMapping("/doctors/{id}/referral-code")
    public String regenerateReferralCode(@PathVariable UUID id,
                                         @AuthenticationPrincipal DoctorPrincipal principal,
                                         RedirectAttributes redirectAttributes) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }
        Doctor doctor = doctorRepository.findById(id).orElse(null);
        if (doctor == null) {
            redirectAttributes.addFlashAttribute("error", "Doctor not found");
            return "redirect:/dashboard/doctors";
        }
        if (doctor.getDomain() != PhysicianDomain.REFERRING_DOCTOR) {
            redirectAttributes.addFlashAttribute("error",
                    "Referral codes apply only to Referring Doctors.");
            return "redirect:/dashboard/doctors";
        }
        String code = generateUniqueReferralCode();
        doctor.setReferralCode(code);
        doctorRepository.save(doctor);
        log.info("Admin id={} regenerated referral code for doctor id={}", admin.getId(), doctor.getId());
        redirectAttributes.addFlashAttribute("success",
                "New referral code for " + doctor.getFullName() + ": " + code);
        return "redirect:/dashboard/doctors";
    }

    @PostMapping("/doctors/{id}/toggle")
    public String toggleDoctor(@PathVariable UUID id,
                               @AuthenticationPrincipal DoctorPrincipal principal,
                               RedirectAttributes redirectAttributes) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }

        Doctor doctor = doctorRepository.findById(id).orElse(null);
        if (doctor == null) {
            redirectAttributes.addFlashAttribute("error", "Doctor not found");
            return "redirect:/dashboard/doctors";
        }

        doctor.setActive(!doctor.getActive());
        doctorRepository.save(doctor);

        String status = doctor.getActive() ? "activated" : "deactivated";
        log.info("Admin id={} {} doctor id={}", admin.getId(), status, doctor.getId());
        redirectAttributes.addFlashAttribute("success",
                "Doctor '" + doctor.getFullName() + "' " + status);
        return "redirect:/dashboard/doctors";
    }

    private Doctor requireAdmin(DoctorPrincipal principal) {
        if (principal == null || principal.getDomain() != PhysicianDomain.ADMIN) {
            return null;
        }
        return doctorRepository.findById(principal.getId()).orElse(null);
    }

    private String generateUniqueReferralCode() {
        for (int i = 0; i < 100; i++) {
            StringBuilder sb = new StringBuilder("REF-");
            for (int j = 0; j < 4; j++) {
                sb.append(REFERRAL_CODE_CHARS.charAt(RANDOM.nextInt(REFERRAL_CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (doctorRepository.findByReferralCode(code).isEmpty()) {
                return code;
            }
        }
        throw new RuntimeException("Failed to generate unique referral code");
    }

    /**
     * Approve (finalize) the final protocol — any doctor with the finalize
     * capability (CAN_FINALIZE), not ADMIN-only anymore.
     *
     * Belt-and-braces with SecurityConfig's path-level {@code CAN_FINALIZE} gate;
     * either alone would suffice, but having both means a future SecurityConfig
     * regression cannot accidentally open this up.
     */
    @PostMapping("/protocol/{protocolId}/approve")
    public String approveProtocol(@PathVariable UUID protocolId,
                                  @AuthenticationPrincipal DoctorPrincipal principal,
                                  RedirectAttributes redirectAttributes) {
        if (principal == null || !principal.isCanFinalize()) {
            log.warn("Doctor id={} without CAN_FINALIZE attempted to approve protocol id={}",
                    principal == null ? null : principal.getId(), protocolId);
            redirectAttributes.addFlashAttribute("error",
                    "You do not have permission to finalize protocols.");
            return "redirect:/dashboard";
        }

        FinalProtocol protocol = tumorBoardService.approveFinalProtocol(protocolId);

        log.info("Doctor id={} approved protocol id={} for patient id={}",
                principal.getId(), protocolId, protocol.getPatient().getId());
        redirectAttributes.addFlashAttribute("success", "Protocol approved!");
        return "redirect:/dashboard/protocol/" + protocol.getPatient().getId();
    }

    // ── PHI Review Queue (Admin Only) ───────────────────────────────────
    // PR 13 — Stage 1 of the redaction workflow. Admin reviews every uploaded
    // report and confirms it's free of identifying header/footer info before
    // it can be opened by tumor-board reviewers without risking PHI exposure.

    @GetMapping("/reports/phi-review")
    public String phiReviewQueue(@AuthenticationPrincipal DoctorPrincipal principal, Model model) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("admin", admin);
        model.addAttribute("pendingReports",
                reportRepository.findByPhiReviewStatusOrderByUploadedAtAsc(
                        Report.PhiReviewStatus.PENDING));
        return "dashboard/phi-review";
    }

    @PostMapping("/reports/{reportId}/phi-review")
    public String submitPhiReview(@PathVariable UUID reportId,
                                  @RequestParam Report.PhiReviewStatus decision,
                                  @AuthenticationPrincipal DoctorPrincipal principal,
                                  RedirectAttributes redirectAttributes) {
        Doctor admin = requireAdmin(principal);
        if (admin == null) {
            return "redirect:/dashboard";
        }
        if (decision != Report.PhiReviewStatus.APPROVED
                && decision != Report.PhiReviewStatus.REDACTION_NEEDED) {
            redirectAttributes.addFlashAttribute("error",
                    "Decision must be APPROVED or REDACTION_NEEDED.");
            return "redirect:/dashboard/reports/phi-review";
        }

        Report report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            redirectAttributes.addFlashAttribute("error", "Report not found.");
            return "redirect:/dashboard/reports/phi-review";
        }

        report.setPhiReviewStatus(decision);
        report.setPhiReviewedByDoctorId(admin.getId());
        report.setPhiReviewedAt(LocalDateTime.now());
        reportRepository.save(report);

        auditService.logDoctorAction(admin.getId(),
                report.getPatient() != null ? report.getPatient().getId() : null,
                AuditLog.AuditAction.REPORT_DOWNLOADED,  // closest existing action; ideally REPORT_PHI_REVIEWED — add to enum later
                "PHI review decision: " + decision + " on report " + reportId);

        log.info("Admin id={} marked report id={} as {}", admin.getId(), reportId, decision);
        redirectAttributes.addFlashAttribute("success",
                "Report marked " + decision + ".");
        return "redirect:/dashboard/reports/phi-review";
    }
}
