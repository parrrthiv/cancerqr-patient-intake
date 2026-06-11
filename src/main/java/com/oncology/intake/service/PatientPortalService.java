package com.oncology.intake.service;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.FinalProtocol;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.PatientAccount;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.repository.DoctorRepository;
import com.oncology.intake.repository.FinalProtocolRepository;
import com.oncology.intake.repository.PatientAccountRepository;
import com.oncology.intake.repository.PatientMessageRepository;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.repository.ReportRepository;
import com.oncology.intake.security.WhatsAppNumberHasher;
import com.oncology.intake.service.AnalysisService.PatientDietGuidance;
import com.oncology.intake.util.MediaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Patient-facing portal: registration/verification, web intake (the same
 * journey as the WhatsApp bot, driven by the SAME {@link ConversationState}
 * machine so a patient can switch channels mid-intake), status assembly, and
 * intake completion.
 *
 * <h2>Account-takeover guard (why OTP exists)</h2>
 * If a patient record already exists for a phone number, it was created by the
 * WhatsApp bot (or a referring doctor) and may contain PHI. Letting anyone who
 * types that number register a portal login would hand them the victim's
 * intake data. So: existing record → the account starts DISABLED and a 6-digit
 * code is sent to that number ON WHATSAPP (the record's own channel); only the
 * person holding the phone can complete registration. Brand-new numbers
 * register directly — the record starts empty, there is nothing to take over.
 * (Go-live hardening: SMS OTP for new numbers too, tracked in CLAUDE.md.)
 *
 * <h2>Reuse, not reimplementation</h2>
 * All writes go through {@link PatientIntakeService} (audited, validated),
 * uploads go through the same {@code MediaValidator → storeReport(Path)}
 * pipeline as WhatsApp media, the upload step is claimed atomically with
 * {@code advanceStateIfCurrent} (same double-submit race protection), and
 * completion runs the same analysis + tumor-board sequence as
 * {@code ConversationService.generateAndSendAnalysis} — minus the WhatsApp
 * sends, because the portal shows results in the browser.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientPortalService {

    /** Mirrors the dashboard's server-side phone validation. */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9][0-9\\s\\-()]{6,19}$");
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Pattern REFERRAL_CODE_PATTERN = Pattern.compile("^REF-[A-Z0-9]{4}$");

    private static final int OTP_VALIDITY_MINUTES = 10;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** States from which the combined web "basic details" form may be submitted. */
    private static final Set<ConversationState> BASICS_STATES = EnumSet.of(
            ConversationState.ASK_REFERRAL_CODE,
            ConversationState.ASK_CANCER_TYPE,
            ConversationState.ASK_AGE,
            ConversationState.ASK_WEIGHT,
            ConversationState.ASK_PAIN_SCALE,
            ConversationState.ASK_DIAGNOSIS_DATE);

    private final PatientAccountRepository accountRepository;
    private final PatientRepository patientRepository;
    private final ReportRepository reportRepository;
    private final FinalProtocolRepository protocolRepository;
    private final PatientMessageRepository messageRepository;
    private final DoctorRepository doctorRepository;
    private final PatientIntakeService patientIntakeService;
    private final AnalysisService analysisService;
    private final TumorBoardService tumorBoardService;
    private final WhatsAppClientService whatsAppClient;
    private final WhatsAppNumberHasher hasher;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CancerQRProtocolConfig protocolConfig;

    /**
     * Whether the portal may use the WhatsApp API for the account-takeover OTP.
     *
     * <p><strong>MUST be {@code true} in production.</strong> When {@code false},
     * registering a phone number that already has a (possibly PHI-bearing)
     * patient record links a new portal login to it WITHOUT proving ownership of
     * the number — i.e. the account-takeover guard is OFF. Set {@code false} only
     * in test environments where the WhatsApp Business API is in development mode
     * and therefore cannot deliver a code to arbitrary testers. Toggle via
     * {@code PORTAL_WHATSAPP_ENABLED}. Brand-new numbers are unaffected either way
     * (they have no existing record to take over). See CLAUDE.md.
     */
    @Value("${app.portal.whatsapp-enabled:true}")
    private boolean whatsAppEnabled;

    /** User-displayable problem; controllers surface the message as a flash error. */
    public static class PortalException extends RuntimeException {
        public PortalException(String message) {
            super(message);
        }
    }

    public enum RegistrationOutcome {
        /** Account ready — go log in. */
        CREATED,
        /** Existing patient record: a WhatsApp code was just sent — go verify. */
        OTP_SENT,
        /** A still-valid code is already pending — go verify (nothing re-sent). */
        OTP_PENDING
    }

    public enum VerifyOutcome { VERIFIED, INVALID_CODE, EXPIRED, TOO_MANY_ATTEMPTS, NOT_PENDING }

    // ── Registration & verification ─────────────────────────────────────

    @Transactional
    public RegistrationOutcome register(String rawPhone, String displayName, String rawPassword) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new PortalException("Please enter your name (max 100 characters).");
        }
        if (rawPhone == null || !PHONE_PATTERN.matcher(rawPhone.trim()).matches()) {
            throw new PortalException("Please enter a valid phone number (e.g. +919876543210).");
        }
        if (rawPassword == null || rawPassword.length() < 8 || rawPassword.length() > 100) {
            throw new PortalException("Password must be 8–100 characters.");
        }

        String normalised = WhatsAppNumberHasher.normalise(rawPhone);
        String phoneHash = hasher.hash(normalised);

        Optional<PatientAccount> existingAccount = accountRepository.findByPhoneHash(phoneHash);
        if (existingAccount.isPresent()) {
            PatientAccount account = existingAccount.get();
            if (Boolean.TRUE.equals(account.getEnabled())) {
                throw new PortalException(
                        "An account with this phone number already exists. Please sign in.");
            }
            // Account is disabled (a verification was pending).
            if (!whatsAppEnabled) {
                // OTP delivery is off in this environment: enable the account
                // directly with the freshly-submitted password. NOT for production
                // — this is the account-takeover guard being intentionally bypassed.
                account.setDisplayName(name);
                account.setPassword(passwordEncoder.encode(rawPassword));
                enableWithoutVerification(account);
                return RegistrationOutcome.CREATED;
            }
            // Pending verification. Don't resend while a code is still valid —
            // that would let an attacker spam the victim's WhatsApp.
            if (otpStillUsable(account)) {
                return RegistrationOutcome.OTP_PENDING;
            }
            // Abandoned/expired attempt: refresh the registration in place.
            account.setDisplayName(name);
            account.setPassword(passwordEncoder.encode(rawPassword));
            issueAndSendOtp(account, normalised);
            accountRepository.save(account);
            return RegistrationOutcome.OTP_SENT;
        }

        Optional<Patient> existingPatient = patientRepository.findByWhatsappNumberHash(phoneHash);
        if (existingPatient.isPresent()) {
            PatientAccount account = PatientAccount.builder()
                    .patientId(existingPatient.get().getId())
                    .phoneHash(phoneHash)
                    .phone(normalised)
                    .password(passwordEncoder.encode(rawPassword))
                    .displayName(name)
                    .enabled(false)
                    .phoneVerified(false)
                    .otpAttempts(0)
                    .build();

            if (!whatsAppEnabled) {
                // OTP delivery is off: link the portal login to the existing
                // record WITHOUT proving ownership. Acceptable only in a test
                // environment — see the whatsAppEnabled field javadoc.
                account.setEnabled(true);
                accountRepository.save(account);
                auditService.logSystemAction(existingPatient.get().getId(),
                        AuditAction.PORTAL_ACCOUNT_CREATED,
                        "Portal account linked to existing patient WITHOUT verification (WhatsApp OTP disabled)");
                log.warn("Portal account linked to existing patient record WITHOUT WhatsApp "
                        + "verification — account-takeover guard is OFF (PORTAL_WHATSAPP_ENABLED=false)");
                return RegistrationOutcome.CREATED;
            }

            // Record may contain PHI — prove number ownership via WhatsApp first.
            issueAndSendOtp(account, normalised);
            accountRepository.save(account);
            auditService.logSystemAction(existingPatient.get().getId(),
                    AuditAction.PORTAL_ACCOUNT_CREATED,
                    "Portal account created for existing patient — pending WhatsApp verification");
            return RegistrationOutcome.OTP_SENT;
        }

        // Brand-new number: create the patient record + an active account.
        Patient patient = Patient.builder()
                .whatsappNumber(normalised)
                // whatsappNumberHash is set automatically by PatientHashListener
                .name(name)
                .conversationState(ConversationState.INITIAL)
                .lastInteractionAt(LocalDateTime.now())
                .build();
        patient = patientRepository.save(patient);

        PatientAccount account = PatientAccount.builder()
                .patientId(patient.getId())
                .phoneHash(phoneHash)
                .phone(normalised)
                .password(passwordEncoder.encode(rawPassword))
                .displayName(name)
                .enabled(true)
                .phoneVerified(false)
                .otpAttempts(0)
                .build();
        accountRepository.save(account);

        auditService.logSystemAction(patient.getId(), AuditAction.PATIENT_CREATED,
                "Patient created from portal registration");
        auditService.logSystemAction(patient.getId(), AuditAction.PORTAL_ACCOUNT_CREATED,
                "Portal account created (new number, active immediately)");
        log.info("Created portal account + patient record for a new number");
        return RegistrationOutcome.CREATED;
    }

    @Transactional
    public VerifyOutcome verifyOtp(String rawPhone, String code) {
        if (rawPhone == null || code == null || !OTP_PATTERN.matcher(code.trim()).matches()) {
            return VerifyOutcome.INVALID_CODE;
        }
        PatientAccount account = accountRepository
                .findByPhoneHash(hasher.hash(rawPhone))
                .orElse(null);
        if (account == null || Boolean.TRUE.equals(account.getEnabled())
                || account.getOtpHash() == null || account.getOtpExpiresAt() == null) {
            return VerifyOutcome.NOT_PENDING;
        }
        if (account.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            return VerifyOutcome.EXPIRED;
        }
        if (account.getOtpAttempts() != null && account.getOtpAttempts() >= OTP_MAX_ATTEMPTS) {
            return VerifyOutcome.TOO_MANY_ATTEMPTS;
        }

        byte[] expected = account.getOtpHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = sha256Hex(code.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            account.setOtpAttempts((account.getOtpAttempts() == null ? 0 : account.getOtpAttempts()) + 1);
            accountRepository.save(account);
            return VerifyOutcome.INVALID_CODE;
        }

        account.setEnabled(true);
        account.setPhoneVerified(true);
        account.setOtpHash(null);
        account.setOtpExpiresAt(null);
        account.setOtpAttempts(0);
        accountRepository.save(account);
        auditService.logSystemAction(account.getPatientId(), AuditAction.PORTAL_ACCOUNT_VERIFIED,
                "Portal account verified via WhatsApp code");
        log.info("Portal account {} verified", account.getId());
        return VerifyOutcome.VERIFIED;
    }

    private boolean otpStillUsable(PatientAccount account) {
        return account.getOtpHash() != null
                && account.getOtpExpiresAt() != null
                && account.getOtpExpiresAt().isAfter(LocalDateTime.now())
                && (account.getOtpAttempts() == null || account.getOtpAttempts() < OTP_MAX_ATTEMPTS);
    }

    /**
     * Enable an account without an OTP step (used only when WhatsApp OTP is
     * disabled for the environment). Clears any pending code and records that the
     * number was NOT verified, so a later hardening pass can require verification
     * for these accounts if needed.
     */
    private void enableWithoutVerification(PatientAccount account) {
        account.setEnabled(true);
        account.setPhoneVerified(false);
        account.setOtpHash(null);
        account.setOtpExpiresAt(null);
        account.setOtpAttempts(0);
        accountRepository.save(account);
        auditService.logSystemAction(account.getPatientId(), AuditAction.PORTAL_ACCOUNT_CREATED,
                "Portal account enabled WITHOUT WhatsApp verification (OTP disabled in this environment)");
        log.warn("Portal account {} enabled WITHOUT verification — account-takeover guard is OFF "
                + "(PORTAL_WHATSAPP_ENABLED=false)", account.getId());
    }

    /**
     * Generate a fresh code, store only its SHA-256, and deliver it over
     * WhatsApp. Runs inside the registration transaction: a failed send throws,
     * rolling back the (disabled) account row so the user can simply retry.
     */
    private void issueAndSendOtp(PatientAccount account, String normalisedPhone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        account.setOtpHash(sha256Hex(code));
        account.setOtpExpiresAt(LocalDateTime.now().plusMinutes(OTP_VALIDITY_MINUTES));
        account.setOtpAttempts(0);
        try {
            whatsAppClient.sendTextMessage(normalisedPhone,
                    "🔐 Your CancerQR portal verification code is *" + code + "*.\n\n"
                    + "It expires in " + OTP_VALIDITY_MINUTES + " minutes. "
                    + "If you didn't try to register, you can ignore this message.").block();
        } catch (Exception e) {
            // Never log the code. Typical causes: number not on WhatsApp, or the
            // 24h customer-service window is closed for this number.
            log.warn("Failed to send portal OTP via WhatsApp: {}", e.getMessage());
            throw new PortalException(
                    "We couldn't send a verification code to this number on WhatsApp. "
                    + "Open WhatsApp, send \"hi\" to our assistant first, then try registering again.");
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Web intake (drives the same state machine as WhatsApp) ──────────

    @Transactional
    public void submitConsent(UUID patientId, boolean consented) {
        Patient patient = patientIntakeService.getPatient(patientId);
        ConversationState state = patient.getConversationState();
        if (state != ConversationState.INITIAL && state != ConversationState.AWAITING_CONSENT) {
            throw new PortalException("Consent has already been recorded for this intake.");
        }
        if (!consented) {
            throw new PortalException("Please tick the consent box to continue.");
        }
        patientIntakeService.recordConsent(patientId);
        // Web collects the (optional) referral code on the details form, so jump
        // straight to ASK_CANCER_TYPE — a valid state for the WhatsApp flow too.
        patientIntakeService.updateConversationState(patientId, ConversationState.ASK_CANCER_TYPE);
    }

    @Transactional
    public void submitBasics(UUID patientId, String cancerTypeKey, Integer age,
                             BigDecimal weightKg, Integer painScale,
                             String diagnosisDate, String referralCode) {
        Patient patient = patientIntakeService.getPatient(patientId);
        if (!BASICS_STATES.contains(patient.getConversationState())) {
            throw new PortalException("This step isn't available right now — refresh the page.");
        }

        // Server-side validation, mirroring the WhatsApp bot's bounds exactly.
        Map<String, CancerQRProtocolConfig.CancerProtocol> protocols =
                protocolConfig.getCancerProtocols();
        if (cancerTypeKey == null || protocols == null || !protocols.containsKey(cancerTypeKey)) {
            throw new PortalException("Please select a valid cancer type.");
        }
        if (age == null || age < 0 || age > 120) {
            throw new PortalException("Age must be between 0 and 120.");
        }
        if (weightKg == null || weightKg.compareTo(BigDecimal.ONE) < 0
                || weightKg.compareTo(BigDecimal.valueOf(300)) > 0) {
            throw new PortalException("Weight must be between 1 and 300 kg.");
        }
        if (painScale == null || painScale < 0 || painScale > 10) {
            throw new PortalException("Pain level must be between 0 and 10.");
        }
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(diagnosisDate == null ? "" : diagnosisDate.trim());
        } catch (Exception e) {
            throw new PortalException("Diagnosis date must be a valid date (YYYY-MM-DD).");
        }
        if (parsedDate.isAfter(LocalDate.now())) {
            throw new PortalException("Diagnosis date cannot be in the future.");
        }

        // Optional referral code — same semantics as the WhatsApp step.
        Doctor referringDoctor = null;
        if (referralCode != null && !referralCode.isBlank()) {
            String codeInput = referralCode.trim().toUpperCase();
            if (!REFERRAL_CODE_PATTERN.matcher(codeInput).matches()) {
                throw new PortalException(
                        "Referral code format is REF-XXXX. Leave it blank if you don't have one.");
            }
            referringDoctor = doctorRepository.findByReferralCode(codeInput)
                    .orElseThrow(() -> new PortalException(
                            "Referral code not found. Check it, or leave it blank."));
        }

        patientIntakeService.updateCancerType(patientId, cancerTypeKey);
        patientIntakeService.updateAge(patientId, age);
        patientIntakeService.updateWeight(patientId, weightKg);
        patientIntakeService.updatePainScale(patientId, painScale);
        patientIntakeService.updateDiagnosisDate(patientId, parsedDate);
        if (referringDoctor != null) {
            patientIntakeService.linkReferringDoctor(patientId, referringDoctor);
        }
        patientIntakeService.updateConversationState(patientId, ConversationState.ASK_PET_SCAN);
    }

    /**
     * Handle a report upload from the portal. Which report is expected (PET vs
     * blood) is derived from the patient's CURRENT state — the same claim-based
     * race protection as the WhatsApp media path, so a double-submitted form
     * can't ingest two files against one step.
     *
     * @return the report type that was stored
     */
    public ReportType handleUpload(UUID patientId, MultipartFile file) {
        Patient patient = patientIntakeService.getPatient(patientId);
        ConversationState current = patient.getConversationState();

        ReportType type = switch (current) {
            case ASK_PET_SCAN -> ReportType.PET_SCAN;
            case ASK_BLOOD_REPORT -> ReportType.BLOOD_REPORT;
            default -> throw new PortalException("We're not expecting an upload at this step.");
        };
        ConversationState next = (type == ReportType.PET_SCAN)
                ? ConversationState.ASK_BLOOD_REPORT
                : ConversationState.PROCESSING;

        if (file == null || file.isEmpty()) {
            throw new PortalException("Please choose a file to upload (JPG, PNG, WebP, or PDF).");
        }

        // Atomically claim this intake step (double-submit / two-tab protection).
        if (!patientIntakeService.advanceStateIfCurrent(patientId, current, next)) {
            throw new PortalException("This step was already completed — refresh the page.");
        }

        Path temp = null;
        try {
            temp = Files.createTempFile("cqr-portal-", ".tmp");
            // Stream the multipart body to disk — no whole-file heap buffer,
            // matching the streaming-upload guarantees of the WhatsApp path.
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            patientIntakeService.storeReport(
                    patientId, type, temp,
                    safeFileName(file.getOriginalFilename(), type, file.getContentType()),
                    file.getContentType(), null);
        } catch (MediaValidator.InvalidMediaException e) {
            patientIntakeService.updateConversationState(patientId, current); // release the claim
            throw new PortalException(
                    "That file isn't supported. Please upload a JPG, PNG, WebP, or PDF up to 25 MB.");
        } catch (Exception e) {
            patientIntakeService.updateConversationState(patientId, current); // release the claim
            log.error("Portal upload failed for patient {}", patientId, e);
            throw new PortalException("We couldn't process that file. Please try again.");
        } finally {
            deleteQuietly(temp);
        }

        // Outside the rollback zone: the file IS stored. If analysis fails the
        // state stays PROCESSING and the portal shows the "being prepared" panel
        // (same behaviour as the WhatsApp flow's failure path).
        if (type == ReportType.BLOOD_REPORT) {
            completeIntake(patientId);
        }
        return type;
    }

    /** Skip the current upload step (parity with the WhatsApp *SKIP* keyword). */
    public void skipCurrentUpload(UUID patientId) {
        Patient patient = patientIntakeService.getPatient(patientId);
        ConversationState current = patient.getConversationState();
        switch (current) {
            case ASK_PET_SCAN -> patientIntakeService.advanceStateIfCurrent(
                    patientId, current, ConversationState.ASK_BLOOD_REPORT);
            case ASK_BLOOD_REPORT -> {
                if (patientIntakeService.advanceStateIfCurrent(
                        patientId, current, ConversationState.PROCESSING)) {
                    completeIntake(patientId);
                }
            }
            default -> throw new PortalException("There is nothing to skip at this step.");
        }
    }

    /** Start a fresh intake (parity with the WhatsApp *START* keyword). */
    public void restartIntake(UUID patientId) {
        Patient patient = patientIntakeService.getPatient(patientId);
        ConversationState state = patient.getConversationState();
        if (state != ConversationState.EXPIRED
                && state != ConversationState.RESULT_SENT
                && state != ConversationState.COMPLETED) {
            throw new PortalException("Your current intake is still in progress.");
        }
        patientIntakeService.resetPatientIntake(patientId);
    }

    /**
     * Same completion sequence as {@code ConversationService.generateAndSendAnalysis},
     * minus the WhatsApp sends (the portal renders the diet guidance instead).
     * Analysis failure leaves the state at PROCESSING and is audited — the
     * stored reports are NOT rolled back.
     */
    private void completeIntake(UUID patientId) {
        patientIntakeService.markIntakeCompleted(patientId);
        try {
            analysisService.generateAnalysis(patientId);
            analysisService.getLatestAnalysis(patientId)
                    .ifPresent(a -> analysisService.markAsSent(a.getId()));
            tumorBoardService.createReviewTasksForPatient(patientId);
            patientIntakeService.updateConversationState(patientId, ConversationState.RESULT_SENT);
        } catch (Exception e) {
            log.error("Failed to generate analysis for patient {} (portal intake)", patientId, e);
            auditService.logFailedAction(patientId, AuditAction.ANALYSIS_GENERATED,
                    "Failed to generate analysis (portal intake)", e.getMessage());
        }
    }

    private static String safeFileName(String original, ReportType type, String contentType) {
        if (original != null && !original.isBlank()) {
            // Strip any path component and control characters a hostile client
            // could smuggle in; keep a sane length.
            String name = original.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            name = name.replaceAll("[\\r\\n\\t\"]", "_").trim();
            if (!name.isEmpty()) {
                return name.length() > 200 ? name.substring(name.length() - 200) : name;
            }
        }
        return type.name().toLowerCase() + "_" + System.currentTimeMillis()
                + extensionFor(contentType);
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return "";
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ── Status assembly for the portal home page ────────────────────────

    public record StatusStep(String label, String state) {} // state: done | current | todo

    public record PortalStatus(
            Patient patient,
            List<StatusStep> steps,
            List<Report> reports,
            PatientDietGuidance dietGuidance,   // null until an analysis exists
            long reviewsCompleted,
            long reviewsTotal,
            boolean protocolReady,
            long unreadMessages,
            boolean expired
    ) {}

    @Transactional(readOnly = true)
    public PortalStatus buildStatus(UUID patientId) {
        Patient patient = patientIntakeService.getPatient(patientId);
        ConversationState state = patient.getConversationState();

        Map<String, Object> reviewStatus = tumorBoardService.getReviewStatusForPatient(patientId);
        long reviewsCompleted = ((Number) reviewStatus.getOrDefault("completed", 0L)).longValue();
        long reviewsTotal = ((Number) reviewStatus.getOrDefault("totalRequired", 8)).longValue();

        boolean protocolReady = protocolRepository.findByPatientId(patientId)
                .map(p -> p.getStatus() == FinalProtocol.ProtocolStatus.APPROVED
                        || p.getStatus() == FinalProtocol.ProtocolStatus.SENT)
                .orElse(false);

        PatientDietGuidance guidance = analysisService.getPatientDietGuidance(patientId).orElse(null);
        boolean analysisReady = guidance != null;

        List<StatusStep> steps = List.of(
                step("Consent", Boolean.TRUE.equals(patient.getConsentGiven()),
                        state == ConversationState.INITIAL || state == ConversationState.AWAITING_CONSENT),
                step("Your details", patient.hasBasicInfo() || atLeast(state, ConversationState.ASK_PET_SCAN),
                        BASICS_STATES.contains(state)),
                step("PET scan", atLeast(state, ConversationState.ASK_BLOOD_REPORT),
                        state == ConversationState.ASK_PET_SCAN),
                step("Blood report", atLeast(state, ConversationState.PROCESSING),
                        state == ConversationState.ASK_BLOOD_REPORT),
                step("Initial assessment", analysisReady,
                        state == ConversationState.PROCESSING),
                step("Specialist review", reviewsTotal > 0 && reviewsCompleted >= reviewsTotal,
                        analysisReady && reviewsCompleted < reviewsTotal),
                step("Care plan", protocolReady,
                        reviewsTotal > 0 && reviewsCompleted >= reviewsTotal && !protocolReady)
        );

        return new PortalStatus(
                patient,
                steps,
                reportRepository.findByPatientId(patientId),
                guidance,
                reviewsCompleted,
                reviewsTotal,
                protocolReady,
                messageRepository.countByPatientIdAndReadAtIsNull(patientId),
                state == ConversationState.EXPIRED
        );
    }

    private static StatusStep step(String label, boolean done, boolean current) {
        return new StatusStep(label, done ? "done" : (current ? "current" : "todo"));
    }

    /** True when the conversation has reached {@code threshold} or beyond (EXPIRED excluded). */
    private static boolean atLeast(ConversationState state, ConversationState threshold) {
        return state != ConversationState.EXPIRED && state.ordinal() >= threshold.ordinal();
    }
}
