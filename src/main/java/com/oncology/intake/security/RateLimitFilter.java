package com.oncology.intake.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rate-limits two sensitive endpoints to slow down opportunistic abuse:
 *
 * <ul>
 *   <li>POST {@code /api/webhook/whatsapp} — capped per source IP. Even with
 *       HMAC verification (PR 2), a flood of unsigned-then-rejected requests
 *       would still consume CPU on signature verification. This caps each
 *       client IP to a reasonable rate.</li>
 *   <li>POST {@code /api/dashboard/login} — capped per submitted username.
 *       Stops the obvious password-guessing pattern: same username, thousands
 *       of POSTs.</li>
 *   <li>POST {@code /api/portal/login} — same per-identifier cap as the staff
 *       login, keyed on the submitted phone number.</li>
 *   <li>POST {@code /api/portal/register} — per-IP. Registration can trigger a
 *       WhatsApp OTP send; this caps mass account creation / OTP spam.</li>
 *   <li>POST {@code /api/portal/verify} — per-IP outer cap on OTP guessing
 *       (the service additionally locks each code after 5 wrong attempts).</li>
 * </ul>
 *
 * <p>State is in-memory per instance (Bucket4j ConcurrentHashMap). Acceptable
 * because we run one EC2 instance today. Multi-instance deployment would need
 * a shared backend (Redis); the {@link Bucket} interface lets us swap that in
 * without changing the filter's logic.
 *
 * <p>Limits chosen conservatively:
 * <ul>
 *   <li>Webhook: 60 requests / minute per IP. Meta's own traffic is well below
 *       this; legitimate replay storms (e.g. retry after our 500) stay under it.
 *       A coordinated flood exceeds it and gets 429 quickly.</li>
 *   <li>Login: 5 attempts / 5 minutes per username. After that, return 429
 *       with a Retry-After header. Real users typing wrong passwords once or
 *       twice are unaffected; brute-forcers can't make meaningful progress.</li>
 * </ul>
 *
 * <p>Order is {@link Ordered#HIGHEST_PRECEDENCE} so we reject excess traffic
 * before Spring Security's filters spend cycles authenticating it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH = "/webhook/whatsapp";
    private static final String LOGIN_PATH = "/dashboard/login";
    // Patient portal public POST surfaces (see SecurityConfig portal chain).
    private static final String PORTAL_LOGIN_PATH = "/portal/login";
    private static final String PORTAL_REGISTER_PATH = "/portal/register";
    private static final String PORTAL_VERIFY_PATH = "/portal/verify";

    private static final Bandwidth WEBHOOK_LIMIT =
            Bandwidth.builder().capacity(60).refillIntervally(60, Duration.ofMinutes(1)).build();

    private static final Bandwidth LOGIN_LIMIT =
            Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(5)).build();

    // Registration triggers a DB write + (for known numbers) a WhatsApp OTP send;
    // cap per-IP so a script can't mass-create accounts or spam OTP messages.
    private static final Bandwidth REGISTER_LIMIT =
            Bandwidth.builder().capacity(10).refillIntervally(10, Duration.ofHours(1)).build();

    // OTP verify: the service already locks the code after 5 wrong attempts;
    // this per-IP cap is the outer layer against multi-account guessing.
    private static final Bandwidth VERIFY_LIMIT =
            Bandwidth.builder().capacity(15).refillIntervally(15, Duration.ofMinutes(5)).build();

    private final ConcurrentMap<String, Bucket> webhookBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> portalLoginBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> portalRegisterBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> portalVerifyBuckets = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();

        if ("POST".equalsIgnoreCase(request.getMethod()) && WEBHOOK_PATH.equals(path)) {
            if (!allowed(webhookBuckets, clientIp(request), WEBHOOK_LIMIT)) {
                rejectWith429(response, "webhook", 60);
                return;
            }
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && LOGIN_PATH.equals(path)) {
            // Key on username, not IP — a single user from many IPs is still a brute force.
            String username = request.getParameter("username");
            if (username != null && !username.isBlank()
                    && !allowed(loginBuckets, username, LOGIN_LIMIT)) {
                rejectWith429(response, "login", 300);
                return;
            }
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && PORTAL_LOGIN_PATH.equals(path)) {
            // Portal login submits the phone number as the username parameter.
            String phone = request.getParameter("phone");
            if (phone != null && !phone.isBlank()
                    && !allowed(portalLoginBuckets, phone, LOGIN_LIMIT)) {
                rejectWith429(response, "portal_login", 300);
                return;
            }
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && PORTAL_REGISTER_PATH.equals(path)) {
            if (!allowed(portalRegisterBuckets, clientIp(request), REGISTER_LIMIT)) {
                rejectWith429(response, "portal_register", 3600);
                return;
            }
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && PORTAL_VERIFY_PATH.equals(path)) {
            if (!allowed(portalVerifyBuckets, clientIp(request), VERIFY_LIMIT)) {
                rejectWith429(response, "portal_verify", 300);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean allowed(ConcurrentMap<String, Bucket> buckets, String key, Bandwidth limit) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(limit).build());
        return bucket.tryConsume(1);
    }

    private void rejectWith429(HttpServletResponse response, String endpoint, int retryAfterSeconds)
            throws IOException {
        meterRegistry.counter("ratelimit.rejected", "endpoint", endpoint).increment();
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("text/plain");
        response.getWriter().write("Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.");
    }

    /**
     * Best-effort client IP. Trusts {@code X-Forwarded-For} from nginx (single
     * trusted proxy). For a multi-hop chain, take the first entry — that's the
     * original client per HTTP convention.
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
