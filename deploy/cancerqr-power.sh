#!/usr/bin/env bash
#
# cancerqr-power.sh — start / stop / status the CancerQR EC2 + RDS to save cost
# while the environment is idle. Run in AWS CloudShell (or anywhere with the AWS
# CLI + credentials that can manage the instances).
#
# Instance identifiers are read from env vars so this script stays account-agnostic
# (nothing account-specific is committed):
#
#   export EC2_INSTANCE_ID=i-xxxxxxxxxxxxxxxxx
#   export RDS_INSTANCE_ID=cancerqr-db
#   export AWS_REGION=ap-south-1          # optional, defaults to ap-south-1
#
#   ./cancerqr-power.sh start | stop | status
#
# Notes:
#   - A *stopped* RDS instance is auto-started by AWS after 7 days. If you stop to
#     save cost, re-run 'stop' weekly or RDS quietly comes back on and bills.
#   - Only compute pauses when stopped; EBS / RDS storage + any Elastic IP still
#     bill a small amount. The big savings are EC2 + RDS compute hours.
#   - The app container runs with --restart unless-stopped, so it relaunches when
#     EC2 starts; it may retry briefly until RDS finishes coming up, then connects.
set -uo pipefail

REGION="${AWS_REGION:-ap-south-1}"
EC2="${EC2_INSTANCE_ID:-}"
RDS="${RDS_INSTANCE_ID:-}"

if [[ -z "$EC2" || -z "$RDS" ]]; then
  echo "ERROR: set EC2_INSTANCE_ID and RDS_INSTANCE_ID first, e.g.:" >&2
  echo "  export EC2_INSTANCE_ID=i-xxxx ; export RDS_INSTANCE_ID=cancerqr-db" >&2
  exit 1
fi

case "${1:-}" in
  start)
    echo "Starting RDS '$RDS' (takes a few minutes to become available)..."
    aws rds start-db-instance --db-instance-identifier "$RDS" --region "$REGION" >/dev/null 2>&1 \
      || echo "  (RDS already starting/available)"
    echo "Starting EC2 '$EC2'..."
    aws ec2 start-instances --instance-ids "$EC2" --region "$REGION" >/dev/null
    echo "Done. App auto-starts via Docker once RDS is reachable."
    ;;
  stop)
    echo "Stopping EC2 '$EC2'..."
    aws ec2 stop-instances --instance-ids "$EC2" --region "$REGION" >/dev/null
    echo "Stopping RDS '$RDS'..."
    aws rds stop-db-instance --db-instance-identifier "$RDS" --region "$REGION" >/dev/null 2>&1 \
      || echo "  (RDS already stopped)"
    echo "Stopping. Compute paused; storage + Elastic IP still bill a little."
    ;;
  status)
    echo -n "EC2 ($EC2): "
    aws ec2 describe-instances --instance-ids "$EC2" --region "$REGION" \
      --query 'Reservations[].Instances[].State.Name' --output text 2>/dev/null || echo "unknown"
    echo -n "RDS ($RDS): "
    aws rds describe-db-instances --db-instance-identifier "$RDS" --region "$REGION" \
      --query 'DBInstances[].DBInstanceStatus' --output text 2>/dev/null || echo "unknown"
    ;;
  *)
    echo "Usage: $0 {start|stop|status}" >&2
    echo "  Requires env: EC2_INSTANCE_ID, RDS_INSTANCE_ID (AWS_REGION optional)" >&2
    exit 1
    ;;
esac
