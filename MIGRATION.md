# AWS Account Migration Runbook

How to stand up CancerQR in a fresh AWS account and cut over to it. AWS resources
can't be "moved" between accounts — you re-create infra and (for this disposable
test env) rebuild fresh rather than copy data.

> **Secrets** (DB password, PHI keys, WhatsApp token) are NOT in this file. Keep
> them in a local `cancerqr-new-account-values.md` / password manager — never commit.
> Region throughout: **ap-south-1**. Stay in the **default VPC** so pieces wire up.

---

## Phase 1 — Infrastructure (new account console)

1. **EC2 key pair** — `cancerqr-key` (RSA, .pem). Download and keep safe.
2. **Security groups:**
   - `cancerqr-web-sg` (EC2): inbound 80 `0.0.0.0/0`, 443 `0.0.0.0/0`, 22 *My IP*, 8080 *My IP*.
   - `cancerqr-db-sg` (RDS): inbound 5432 from **`cancerqr-web-sg`** (the SG, not an IP).
3. **S3 bucket** — private (Block all public access ON), SSE-S3 (AES-256) on.
4. **IAM role** `cancerqr-ec2-role` (EC2 trust) with least-priv S3 policy:
   `s3:GetObject/PutObject/DeleteObject/ListBucket` on `arn:aws:s3:::BUCKET` + `/*`.
5. **RDS PostgreSQL** — identifier `cancerqr-db`; set master user/password; instance
   `db.t3.micro` (free tier) / `db.t4g.micro`; 20 GB gp3; **Public access No**;
   SG `cancerqr-db-sg`; **Initial database name set** (must match `DB_NAME` or the
   app can't connect); automated backups on.
6. **EC2** — Ubuntu 22.04; `t3.small` (stable) or `t3.micro` (free tier); key
   `cancerqr-key`; auto-assign public IP; SG `cancerqr-web-sg`; **attach IAM role
   `cancerqr-ec2-role`**.
7. **Elastic IP** — allocate + associate with the instance.

Record (into your local values sheet): account ID, EIP, SG IDs, S3 bucket, RDS
endpoint, DB name/user.

## Phase 2 — Deploy the app (on the new EC2 box)

```bash
ssh -i cancerqr-key.pem ubuntu@<ELASTIC_IP>

# DB reachable? (catches SG mistakes early)
sudo apt-get update && sudo apt-get install -y docker.io git postgresql-client
timeout 5 bash -c "</dev/tcp/<RDS_ENDPOINT>/5432" && echo DB_REACHABLE || echo DB_BLOCKED

# Clone YOUR FORK (not upstream — upstream lacks the auth/security work) and build
git clone https://github.com/parrrthiv/cancerqr-patient-intake.git
cd cancerqr-patient-intake
git log --oneline -3          # sanity: shows recent PRs
sudo usermod -aG docker ubuntu && newgrp docker
docker build -t cancerqr-app:latest .

# Generate + SAVE two different PHI keys
openssl rand -base64 32   # -> PHI_ENCRYPTION_KEY
openssl rand -base64 32   # -> PHI_HMAC_KEY
```

Run the container with the new-account values (full template in
`cancerqr-new-account-values.md` §5):
- `SPRING_PROFILES_ACTIVE=production,dev` (seeds admin + test data; use plain
  `production` at go-live), context path `/api`.
- `DB_HOST/DB_NAME/DB_USERNAME/DB_PASSWORD` = new RDS; `S3_BUCKET_NAME` = new bucket;
  `AWS_REGION=ap-south-1`.
- Leave `AWS_ACCESS_KEY`/`AWS_SECRET_KEY` **blank** — the instance IAM role provides S3.
- `PHI_ENCRYPTION_KEY` + `PHI_HMAC_KEY` (the two you just generated).

Verify:
```bash
docker logs -f cancerqr     # Flyway -> v9, "PHI encryptor initialised",
                            # "WhatsApp number hasher initialised", "Started ..."
curl -s http://localhost:8080/api/actuator/health   # status UP, db UP, s3 UP
```
(`s3: UP` proves the IAM role works. You can't fully log in over plain HTTP:8080 —
the production profile forces Secure cookies; login works after HTTPS in Phase 3.)

## Phase 3 — Cut over (DNS → nginx + TLS → webhook)

Order matters: nginx up → DNS → cert (Let's Encrypt must reach the domain on the box).

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
```
nginx reverse proxy `/etc/nginx/sites-available/cancerqr`:
```nginx
server {
    listen 80;
    server_name testapi.cancerqr.com;
    client_max_body_size 30M;
    location / {
        proxy_pass http://127.0.0.1:8080;   # MUST be loopback, else 502 via SG
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;   # rate limiter
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
```bash
sudo ln -sf /etc/nginx/sites-available/cancerqr /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```
1. **Repoint DNS** A record `testapi.cancerqr.com` → new Elastic IP (lower TTL first).
   Confirm: `dig +short testapi.cancerqr.com` returns the new IP.
2. **Cert:** `sudo certbot --nginx -d testapi.cancerqr.com` (choose HTTP→HTTPS
   redirect). Then `sudo certbot renew --dry-run`.
3. **Verify:** `curl -i https://testapi.cancerqr.com/api/actuator/health`; log in at
   `/api/dashboard/login`.
4. **Webhook:** domain unchanged, so the Meta callback URL is the same — re-verify:
   `curl "https://testapi.cancerqr.com/api/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=<VERIFY_TOKEN>&hub.challenge=ok"` (echoes `ok`), confirm `messages`
   subscription, send a test message.

## Phase 4 — Tear down the OLD account (stops the bill)

After the new env verifies green: terminate old EC2, delete old RDS, **release the
old Elastic IP** (unattached EIPs/IPv4 are billed), delete old S3 bucket + old
snapshots. Set a **Budget alert** on the new account so spend can't surprise you.

---

### Keep existing data instead of rebuilding? (usually skip for test)
- **Reuse the exact `PHI_ENCRYPTION_KEY`** or encrypted data is unrecoverable.
- RDS: snapshot → share with new account ID → copy → restore (custom KMS key needed
  if encrypted with the default key).
- S3: `aws s3 sync s3://old s3://new` with a cross-account bucket policy.
