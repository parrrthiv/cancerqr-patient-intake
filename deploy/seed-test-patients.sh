#!/usr/bin/env bash
#
# seed-test-patients.sh — populate CancerQR with synthetic test patients.
#
# Drives the dev-only endpoint POST /api/admin/test/patient, which creates a
# patient WITH a PET scan + blood report, runs analysis + report-data
# extraction, and creates tumor-board reviews — i.e. the same end state as a
# completed WhatsApp intake. Each seeded report starts PHI-review = PENDING,
# so you can also exercise the redaction-approval flow.
#
# REQUIREMENTS
#   - App running with the `dev` profile active (your deploy uses
#     SPRING_PROFILES_ACTIVE=production,dev) so /admin/test/* is registered.
#   - An admin login (default admin/admin123 from the dev seeder).
#   - curl + base64 (standard on the EC2 box). Run with bash, not sh.
#
# USAGE
#   bash deploy/seed-test-patients.sh
#   BASE_URL=https://testapi.cancerqr.com USERNAME=admin PASSWORD=admin123 COUNT=15 \
#       bash deploy/seed-test-patients.sh
#
set -uo pipefail

BASE_URL="${BASE_URL:-https://testapi.cancerqr.com}"
API="${BASE_URL%/}/api"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"
COUNT="${COUNT:-15}"

WORK="$(mktemp -d)"
JAR="$WORK/cookies.txt"
RUN="$(date +%H%M%S)"          # keeps phone numbers unique per run
trap 'rm -rf "$WORK"' EXIT

echo "Target: $API   (login as $USERNAME)"

# 1x1 PNG used as every patient's PET scan (a valid image; no text needed).
PET="$WORK/pet_scan.png"
base64 -d > "$PET" <<'PNG'
iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC
PNG

# Build a minimal but valid blood-report PDF with extractable lab values.
make_blood_pdf() {
  local out="$1" name="$2" esr="$3" crp="$4" hb="$5" stage="$6"
  local s="BT /F1 14 Tf 72 740 Td (CancerQR Blood Report) Tj 0 -26 Td (Patient: ${name}) Tj 0 -26 Td (ESR: ${esr} mm/hr) Tj 0 -26 Td (CRP: ${crp} mg/L) Tj 0 -26 Td (Hemoglobin: ${hb} g/dL) Tj 0 -26 Td (Cancer Stage: ${stage}) Tj ET"
  local len; len=$(printf '%s' "$s" | wc -c)
  cat > "$out" <<EOF
%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>
endobj
4 0 obj
<< /Length ${len} >>
stream
${s}
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
trailer
<< /Root 1 0 R /Size 6 >>
startxref
0
%%EOF
EOF
}

# Log in: scrape the CSRF token, then POST credentials (keeps the session cookie).
CSRF=$(curl -sk -c "$JAR" "$API/dashboard/login" \
  | grep -o 'name="_csrf"[^>]*value="[^"]*"' | sed -E 's/.*value="([^"]*)".*/\1/' | head -1)
if [ -z "${CSRF:-}" ]; then
  echo "ERROR: could not read a CSRF token from $API/dashboard/login — is the app up?"
  exit 1
fi

LOGIN_LOC=$(curl -sk -b "$JAR" -c "$JAR" -D - -o /dev/null -X POST "$API/dashboard/login" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "_csrf=$CSRF" | tr -d '\r' | grep -i '^location:' | head -1)
if echo "$LOGIN_LOC" | grep -qi 'error'; then
  echo "ERROR: login failed (check credentials / that the dev profile is active)."
  echo "       redirect was: $LOGIN_LOC"
  exit 1
fi
echo "Logged in OK."

# Profiles: name|cancerType|age|weightKg|pain|diagnosisDate|esr|crp|hb|stage
PROFILES=(
  "Aarav Sharma|BREAST_CANCER|54|68|6|2026-02-10|42|9|11.8|II"
  "Diya Patel|LUNG_CANCER|63|59|8|2026-01-22|68|24|10.2|III"
  "Vivaan Reddy|COLORECTAL_CANCER|49|74|4|2026-03-05|28|6|12.6|I"
  "Ananya Iyer|PROSTATE_CANCER|71|78|5|2025-12-18|35|11|13.1|II"
  "Kabir Nair|GYNECOLOGIC_CANCER|58|62|7|2026-02-28|55|18|10.9|III"
  "Saanvi Rao|GI_PANCREAS_STOMACH_LIVER|66|70|9|2026-01-09|74|31|9.8|IV"
  "Arjun Mehta|BREAST_CANCER|45|81|3|2026-03-12|22|4|13.4|I"
  "Ishaan Gupta|LUNG_CANCER|69|66|6|2026-02-01|48|14|11.1|II"
  "Myra Joshi|COLORECTAL_CANCER|52|57|5|2026-01-30|39|10|12.0|II"
  "Reyansh Das|PROSTATE_CANCER|74|83|7|2025-11-25|61|20|10.5|III"
  "Aadhya Menon|GYNECOLOGIC_CANCER|47|60|4|2026-03-18|31|7|12.9|I"
  "Vihaan Kulkarni|GI_PANCREAS_STOMACH_LIVER|60|72|8|2026-02-14|70|27|9.5|IV"
  "Anika Bose|BREAST_CANCER|38|64|2|2026-03-22|18|3|13.8|I"
  "Kiaan Verma|LUNG_CANCER|72|58|9|2026-01-15|80|33|9.2|IV"
  "Tara Pillai|COLORECTAL_CANCER|56|69|6|2026-02-20|44|13|11.4|II"
)

TOTAL=${#PROFILES[@]}
[ "$COUNT" -gt "$TOTAL" ] && COUNT=$TOTAL
ok=0; fail=0
for ((i=0; i<COUNT; i++)); do
  IFS='|' read -r name ctype age weight pain date esr crp hb stage <<< "${PROFILES[$i]}"
  phone="+9190${RUN}$(printf '%02d' "$i")"
  blood="$WORK/blood_${i}.pdf"
  make_blood_pdf "$blood" "$name" "$esr" "$crp" "$hb" "$stage"

  resp=$(curl -sk -b "$JAR" -X POST "$API/admin/test/patient" \
    -F "name=$name" -F "age=$age" -F "cancerType=$ctype" -F "weightKg=$weight" \
    -F "painScale=$pain" -F "diagnosisDate=$date" -F "whatsappNumber=$phone" \
    -F "petScan=@${PET};type=image/png" \
    -F "bloodReport=@${blood};type=application/pdf")

  if echo "$resp" | grep -q '"status":"SUCCESS"'; then
    pid=$(echo "$resp" | grep -o '"patientId":"[^"]*"' | sed -E 's/.*:"([^"]*)".*/\1/')
    echo "[$((i+1))/$COUNT] OK    $name ($ctype)  id=$pid"
    ok=$((ok+1))
  else
    echo "[$((i+1))/$COUNT] FAIL  $name ($ctype)  -> $(echo "$resp" | head -c 300)"
    fail=$((fail+1))
  fi
done

echo "---"
echo "Done: $ok created, $fail failed."
echo "View them at: $BASE_URL/api/dashboard/patients"
