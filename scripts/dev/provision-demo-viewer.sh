#!/usr/bin/env bash

set -euo pipefail

REALM="proofmesh"
USERNAME="viewer@proofmesh.local"
EMAIL="viewer@proofmesh.local"
ROLE="VIEWER"

: "${DEMO_VIEWER_PASSWORD:?DEMO_VIEWER_PASSWORD must be set}"

echo "Authenticating with Keycloak admin CLI..."

docker compose exec -T keycloak sh -lc '
/opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" \
  --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
'

USER_ID="$(
  docker compose exec -T keycloak sh -lc "
  /opt/keycloak/bin/kcadm.sh get users \
    -r ${REALM} \
    -q username=${USERNAME} \
    --fields id,username
  " \
  | python3 -c '
import json, sys

users = json.load(sys.stdin)

match = next(
    (
        user
        for user in users
        if user.get("username") == "viewer@proofmesh.local"
    ),
    None,
)

print(match["id"] if match else "")
'
)"

if [[ -z "${USER_ID}" ]]; then
  echo "Creating demo viewer..."

  docker compose exec -T keycloak sh -lc "
  /opt/keycloak/bin/kcadm.sh create users \
    -r ${REALM} \
    -s username='\"${USERNAME}\"' \
    -s email='\"${EMAIL}\"' \
    -s firstName='\"Demo\"' \
    -s lastName='\"Viewer\"' \
    -s enabled=true \
    -s emailVerified=true
  "

  USER_ID="$(
    docker compose exec -T keycloak sh -lc "
    /opt/keycloak/bin/kcadm.sh get users \
      -r ${REALM} \
      -q username=${USERNAME} \
      --fields id,username
    " \
    | python3 -c '
import json, sys

users = json.load(sys.stdin)

match = next(
    user
    for user in users
    if user.get("username") == "viewer@proofmesh.local"
)

print(match["id"])
'
  )"
else
  echo "Demo viewer already exists."
fi

echo "Setting demo viewer password..."

docker compose exec -T keycloak \
  /opt/keycloak/bin/kcadm.sh set-password \
  -r "${REALM}" \
  --userid "${USER_ID}" \
  --new-password "${DEMO_VIEWER_PASSWORD}"

echo "Assigning ${ROLE} role..."

docker compose exec -T keycloak sh -lc "
/opt/keycloak/bin/kcadm.sh add-roles \
  -r ${REALM} \
  --uid ${USER_ID} \
  --rolename ${ROLE}
"

echo "Keycloak subject: ${USER_ID}"

echo "Seeding ProofMesh organization membership..."

docker compose exec -T postgres psql \
  -U "${POSTGRES_USER:-proofmesh}" \
  -d "${POSTGRES_DB:-proofmesh}" \
  -v ON_ERROR_STOP=1 \
  -v oidc_subject="${USER_ID}" \
  <<'SQL'
INSERT INTO proofmesh.organizations (
    id,
    slug,
    name,
    status
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'proofmesh-demo',
    'ProofMesh Demo',
    'ACTIVE'
)
ON CONFLICT (id)
DO UPDATE SET
    slug = EXCLUDED.slug,
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;


DELETE FROM proofmesh.organization_memberships
WHERE user_id IN (
    SELECT id
    FROM proofmesh.operator_users
    WHERE email = 'viewer@proofmesh.local'
);


DELETE FROM proofmesh.operator_users
WHERE email = 'viewer@proofmesh.local';


INSERT INTO proofmesh.operator_users (
    id,
    oidc_subject,
    display_name,
    email,
    status
)
VALUES (
    '20000000-0000-0000-0000-000000000001',
    :'oidc_subject',
    'Demo Viewer',
    'viewer@proofmesh.local',
    'ACTIVE'
);


INSERT INTO proofmesh.organization_memberships (
    organization_id,
    user_id,
    status
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'ACTIVE'
);
SQL

echo
echo "Demo viewer provisioning complete."
echo "Username: ${USERNAME}"
echo "OIDC subject: ${USER_ID}"
