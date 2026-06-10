package com.oncology.intake.controller;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.exception.IntakeExceptions.PatientNotFoundException;
import com.oncology.intake.security.PatientPortalPrincipal;
import com.oncology.intake.service.PatientIntakeService;
import com.oncology.intake.service.PatientMessageService;
import com.oncology.intake.service.PatientPortalService;
import com.oncology.intake.service.PatientPortalService.PortalException;
import com.oncology.intake.service.PatientPortalService.PortalStatus;
import com.oncology.intake.service.PatientPortalService.RegistrationOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Patient-facing portal: register/verify, web intake (no WhatsApp required),
 * status, and messages from the care team.
 *
 * <p>Security model (enforced by the dedicated {@code /portal/**} filter chain
 * in {@code SecurityConfig}):
 * <ul>
 *   <li>{@code /portal/login}, {@code /portal/register}, {@code /portal/verify}
 *       are public; everything else requires ROLE_PATIENT.</li>
 *   <li>GET/POST {@code /portal/login} and {@code /portal/logout} are owned by
 *       Spring Security's filters — only the login GET renders here.</li>
 *   <li><strong>IDOR-proof by construction:</strong> every handler resolves the
 *       patient EXCLUSIVELY from the session principal
 *       ({@link PatientPortalPrincipal#getPatientId()}). No handler accepts a
 *       patient id from the request, so one patient can never address another
 *       patient's data.</li>
 * </ul>
 *
 * <p>Errors are surfaced as flash messages (this is a browser flow — the JSON
 * {@code GlobalExceptionHandler} would be hostile UX here).
 */
@Controller
@RequestMapping("/portal")
@RequiredArgsConstructor
@Slf4j
public class PatientPortalController {

    private static final Set<ConversationState> BASICS_STATES = EnumSet.of(
            ConversationState.ASK_REFERRAL_CODE,
            ConversationState.ASK_CANCER_TYPE,
            ConversationState.ASK_AGE,
            ConversationState.ASK_WEIGHT,
            ConversationState.ASK_PAIN_SCALE,
            ConversationState.ASK_DIAGNOSIS_DATE);

    private final PatientPortalService portalService;
    private final PatientMessageService messageService;
    private final PatientIntakeService patientIntakeService;
    private final CancerQRProtocolConfig protocolConfig;

    // ── Public: login / register / verify ──────────────────────────────

    /** Login page (Spring Security handles the POST). */
    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal PatientPortalPrincipal principal) {
        if (principal != null) {
            return "redirect:/portal";
        }
        return "portal/login";
    }

    @GetMapping("/register")
    public String registerPage(@AuthenticationPrincipal PatientPortalPrincipal principal) {
        if (principal != null) {
            return "redirect:/portal";
        }
        return "portal/register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String name,
                           @RequestParam String phone,
                           @RequestParam String password,
                           RedirectAttributes redirect) {
        try {
            RegistrationOutcome outcome = portalService.register(phone, name, password);
            switch (outcome) {
                case CREATED -> {
                    return "redirect:/portal/login?registered";
                }
                case OTP_SENT -> {
                    // Phone travels as a flash attribute (becomes a hidden form
                    // field) — never as a query parameter, which would leak it
                    // into proxy/access logs.
                    redirect.addFlashAttribute("phone", phone);
                    redirect.addFlashAttribute("info",
                            "We sent a 6-digit code to this number on WhatsApp. Enter it below.");
                    return "redirect:/portal/verify";
                }
                case OTP_PENDING -> {
                    redirect.addFlashAttribute("phone", phone);
                    redirect.addFlashAttribute("info",
                            "A verification code was already sent to this number. Enter it below.");
                    return "redirect:/portal/verify";
                }
            }
            return "redirect:/portal/register";
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/portal/register";
        } catch (Exception e) {
            // e.g. unique-constraint race when the same number registers twice
            // concurrently — generic message, no internals.
            log.error("Portal registration failed", e);
            redirect.addFlashAttribute("error",
                    "Registration didn't go through. Please try again, or sign in if you already have an account.");
            return "redirect:/portal/register";
        }
    }

    /** OTP entry page. Phone arrives as a flash attribute (or is typed again). */
    @GetMapping("/verify")
    public String verifyPage() {
        return "portal/verify";
    }

    @PostMapping("/verify")
    public String verify(@RequestParam String phone,
                         @RequestParam String code,
                         RedirectAttributes redirect) {
        switch (portalService.verifyOtp(phone, code)) {
            case VERIFIED -> {
                return "redirect:/portal/login?verified";
            }
            case INVALID_CODE -> {
                redirect.addFlashAttribute("phone", phone);
                redirect.addFlashAttribute("error", "That code isn't right. Please check and try again.");
                return "redirect:/portal/verify";
            }
            case EXPIRED -> {
                redirect.addFlashAttribute("error",
                        "That code has expired. Register again to receive a new one.");
                return "redirect:/portal/register";
            }
            case TOO_MANY_ATTEMPTS -> {
                redirect.addFlashAttribute("error",
                        "Too many incorrect attempts. Register again to receive a new code.");
                return "redirect:/portal/register";
            }
            default -> {
                redirect.addFlashAttribute("error",
                        "No verification is pending for this number. Try signing in, or register first.");
                return "redirect:/portal/login";
            }
        }
    }

    // ── Authenticated: home / intake / messages ─────────────────────────

    @GetMapping
    public String home(@AuthenticationPrincipal PatientPortalPrincipal principal, Model model) {
        PortalStatus status;
        try {
            status = portalService.buildStatus(principal.getPatientId());
        } catch (PatientNotFoundException e) {
            // Patient row removed (test wipes) while the session lived on.
            return "redirect:/portal/logout";
        }

        Patient patient = status.patient();
        ConversationState state = patient.getConversationState();

        model.addAttribute("displayName", principal.getDisplayName());
        model.addAttribute("patient", patient);
        model.addAttribute("steps", status.steps());
        model.addAttribute("reports", status.reports());
        model.addAttribute("guidance", status.dietGuidance());
        model.addAttribute("reviewsCompleted", status.reviewsCompleted());
        model.addAttribute("reviewsTotal", status.reviewsTotal());
        model.addAttribute("protocolReady", status.protocolReady());
        model.addAttribute("unreadMessages", status.unreadMessages());
        model.addAttribute("expired", status.expired());
        model.addAttribute("processing", state == ConversationState.PROCESSING);
        model.addAttribute("intakeActionNeeded",
                state == ConversationState.INITIAL
                        || state == ConversationState.AWAITING_CONSENT
                        || BASICS_STATES.contains(state)
                        || state == ConversationState.ASK_PET_SCAN
                        || state == ConversationState.ASK_BLOOD_REPORT);
        return "portal/home";
    }

    @GetMapping("/intake")
    public String intake(@AuthenticationPrincipal PatientPortalPrincipal principal, Model model) {
        Patient patient;
        try {
            patient = patientIntakeService.getPatient(principal.getPatientId());
        } catch (PatientNotFoundException e) {
            return "redirect:/portal/logout";
        }

        ConversationState state = patient.getConversationState();
        model.addAttribute("displayName", principal.getDisplayName());
        model.addAttribute("patient", patient);

        if (state == ConversationState.INITIAL || state == ConversationState.AWAITING_CONSENT) {
            model.addAttribute("step", "consent");
        } else if (BASICS_STATES.contains(state)) {
            model.addAttribute("step", "basics");
            model.addAttribute("cancerProtocols", protocolConfig.getCancerProtocols());
        } else if (state == ConversationState.ASK_PET_SCAN) {
            model.addAttribute("step", "upload");
            model.addAttribute("uploadLabel", "PET scan report");
        } else if (state == ConversationState.ASK_BLOOD_REPORT) {
            model.addAttribute("step", "upload");
            model.addAttribute("uploadLabel", "blood report");
        } else if (state == ConversationState.PROCESSING) {
            model.addAttribute("step", "processing");
        } else if (state == ConversationState.EXPIRED) {
            model.addAttribute("step", "expired");
        } else {
            // RESULT_SENT / COMPLETED — intake is done; home shows the results.
            return "redirect:/portal";
        }
        return "portal/intake";
    }

    @PostMapping("/intake/consent")
    public String consent(@RequestParam(name = "consent", required = false) Boolean consent,
                          @AuthenticationPrincipal PatientPortalPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            portalService.submitConsent(principal.getPatientId(), Boolean.TRUE.equals(consent));
            redirect.addFlashAttribute("success", "Consent recorded. Now tell us a little about yourself.");
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/portal/intake";
    }

    @PostMapping("/intake/basics")
    public String basics(@RequestParam String cancerType,
                         @RequestParam Integer age,
                         @RequestParam BigDecimal weightKg,
                         @RequestParam Integer painScale,
                         @RequestParam String diagnosisDate,
                         @RequestParam(required = false) String referralCode,
                         @AuthenticationPrincipal PatientPortalPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            portalService.submitBasics(principal.getPatientId(), cancerType, age,
                    weightKg, painScale, diagnosisDate, referralCode);
            redirect.addFlashAttribute("success",
                    "Details saved. Next: upload your PET scan report (or skip if you don't have it).");
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/portal/intake";
    }

    @PostMapping("/intake/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @AuthenticationPrincipal PatientPortalPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            ReportType stored = portalService.handleUpload(principal.getPatientId(), file);
            if (stored == ReportType.PET_SCAN) {
                redirect.addFlashAttribute("success",
                        "PET scan received. Now upload your blood report.");
                return "redirect:/portal/intake";
            }
            redirect.addFlashAttribute("success",
                    "All done — thank you! Your diet guidance is below; our medical team will review your case.");
            return "redirect:/portal";
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/portal/intake";
        }
    }

    @PostMapping("/intake/skip")
    public String skipUpload(@AuthenticationPrincipal PatientPortalPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            portalService.skipCurrentUpload(principal.getPatientId());
            redirect.addFlashAttribute("success", "Step skipped.");
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/portal/intake";
    }

    @PostMapping("/intake/restart")
    public String restart(@AuthenticationPrincipal PatientPortalPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            portalService.restartIntake(principal.getPatientId());
            redirect.addFlashAttribute("success", "Let's start a fresh assessment.");
        } catch (PortalException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/portal/intake";
    }

    @GetMapping("/messages")
    public String messages(@AuthenticationPrincipal PatientPortalPrincipal principal, Model model) {
        UUID patientId = principal.getPatientId();
        // Fetch first, then mark read — the rendered snapshot still shows which
        // messages were new (readAt == null) for a subtle "new" badge.
        var messages = messageService.messagesForPatient(patientId);
        messageService.markAllRead(patientId);
        model.addAttribute("displayName", principal.getDisplayName());
        model.addAttribute("messages", messages);
        return "portal/messages";
    }

    /**
     * Friendly handling for oversized multipart bodies (Tomcat/Spring reject
     * them before our MediaValidator gets a say). Without this, the patient
     * would see the JSON error page from {@code GlobalExceptionHandler}.
     */
    @ExceptionHandler(MultipartException.class)
    public String handleMultipartTooLarge(MultipartException e, RedirectAttributes redirect) {
        log.warn("Portal multipart rejected: {}", e.getMessage());
        redirect.addFlashAttribute("error",
                "That file is too large. Please upload a JPG, PNG, WebP, or PDF up to 25 MB.");
        return "redirect:/portal/intake";
    }
}
