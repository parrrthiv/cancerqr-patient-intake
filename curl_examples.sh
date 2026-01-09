#!/bin/bash
# Cancer Patient Intake System - cURL Test Examples
# 
# Usage: Replace BASE_URL and run individual commands

BASE_URL="http://localhost:8080/api"
VERIFY_TOKEN="your-verify-token"
PHONE_NUMBER_ID="your-phone-number-id"

echo "=== Health Check ==="
curl -s "${BASE_URL}/actuator/health" | jq .

echo ""
echo "=== Webhook Verification ==="
curl -s "${BASE_URL}/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=${VERIFY_TOKEN}&hub.challenge=test123"

echo ""
echo "=== Simulate Initial Message (Hello) ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.hello",
            "timestamp": "1234567890",
            "type": "text",
            "text": {"body": "Hello"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Consent YES ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.consent",
            "timestamp": "1234567891",
            "type": "text",
            "text": {"body": "YES"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Age Input ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.age",
            "timestamp": "1234567892",
            "type": "text",
            "text": {"body": "55"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Weight Input ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.weight",
            "timestamp": "1234567893",
            "type": "text",
            "text": {"body": "72.5"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Pain Scale Input ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.pain",
            "timestamp": "1234567894",
            "type": "text",
            "text": {"body": "6"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Diagnosis Date Input ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.date",
            "timestamp": "1234567895",
            "type": "text",
            "text": {"body": "2024-01-15"}
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate Image Upload (PET Scan) ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.petscan",
            "timestamp": "1234567896",
            "type": "image",
            "image": {
              "id": "media-id-12345",
              "mime_type": "image/jpeg",
              "sha256": "abc123",
              "caption": "PET Scan"
            }
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Simulate PDF Upload (Blood Report) ==="
curl -X POST "${BASE_URL}/webhook/whatsapp" \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "'${PHONE_NUMBER_ID}'",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {
            "display_phone_number": "1234567890",
            "phone_number_id": "'${PHONE_NUMBER_ID}'"
          },
          "contacts": [{
            "profile": {"name": "Test Patient"},
            "wa_id": "919876543210"
          }],
          "messages": [{
            "from": "919876543210",
            "id": "wamid.bloodreport",
            "timestamp": "1234567897",
            "type": "document",
            "document": {
              "id": "media-id-67890",
              "mime_type": "application/pdf",
              "sha256": "def456",
              "filename": "blood_report.pdf"
            }
          }]
        },
        "field": "messages"
      }]
    }]
  }'

echo ""
echo "=== Complete Flow Test ==="
echo "Run commands above in sequence to test full patient intake flow"
