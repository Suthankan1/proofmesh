#!/usr/bin/env python3

import base64
import hashlib
import http.server
import json
import os
import secrets
import socketserver
import sys
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path


ISSUER = os.getenv(
    "OIDC_ISSUER_URI",
    "http://localhost:8081/realms/proofmesh",
)

CLIENT_ID = "proofmesh-web"
CLIENT_SECRET = os.getenv("KEYCLOAK_WEB_CLIENT_SECRET")

REDIRECT_URI = "http://localhost:3000/callback"
CALLBACK_HOST = "127.0.0.1"
CALLBACK_PORT = 3000

TOKEN_FILE = Path(".tmp/proofmesh-access-token")


if not CLIENT_SECRET:
    print(
        "KEYCLOAK_WEB_CLIENT_SECRET must be set.",
        file=sys.stderr,
    )
    sys.exit(1)


def base64url_without_padding(value: bytes) -> str:
    return (
        base64.urlsafe_b64encode(value)
        .decode("ascii")
        .rstrip("=")
    )


code_verifier = base64url_without_padding(
    secrets.token_bytes(64)
)

code_challenge = base64url_without_padding(
    hashlib.sha256(
        code_verifier.encode("ascii")
    ).digest()
)

state = secrets.token_urlsafe(32)


authorization_parameters = {
    "client_id": CLIENT_ID,
    "response_type": "code",
    "redirect_uri": REDIRECT_URI,
    "scope": "openid profile email",
    "state": state,
    "code_challenge": code_challenge,
    "code_challenge_method": "S256",
}

authorization_url = (
    f"{ISSUER}/protocol/openid-connect/auth?"
    + urllib.parse.urlencode(
        authorization_parameters
    )
)


authorization_result = {}


class CallbackHandler(
    http.server.BaseHTTPRequestHandler
):

    def do_GET(self):
        parsed = urllib.parse.urlparse(
            self.path
        )

        if parsed.path != "/callback":
            self.send_response(404)
            self.end_headers()
            return

        parameters = urllib.parse.parse_qs(
            parsed.query
        )

        returned_state = parameters.get(
            "state",
            [None],
        )[0]

        if returned_state != state:
            authorization_result["error"] = (
                "Invalid OAuth state"
            )

            self.send_response(400)
            self.end_headers()

            self.wfile.write(
                b"Invalid OAuth state."
            )
            return

        error = parameters.get(
            "error",
            [None],
        )[0]

        if error:
            authorization_result["error"] = (
                error
            )

            self.send_response(400)
            self.end_headers()

            self.wfile.write(
                b"Authentication failed."
            )
            return

        code = parameters.get(
            "code",
            [None],
        )[0]

        if not code:
            authorization_result["error"] = (
                "Authorization code missing"
            )

            self.send_response(400)
            self.end_headers()

            self.wfile.write(
                b"Authorization code missing."
            )
            return

        authorization_result["code"] = code

        self.send_response(200)
        self.send_header(
            "Content-Type",
            "text/plain; charset=utf-8",
        )
        self.end_headers()

        self.wfile.write(
            b"ProofMesh login succeeded. "
            b"You can close this browser tab."
        )

    def log_message(
        self,
        format,
        *args,
    ):
        return


class ReusableTCPServer(
    socketserver.TCPServer
):
    allow_reuse_address = True


print(
    "Opening ProofMesh Keycloak login...",
    file=sys.stderr,
)

print(
    f"Callback: {REDIRECT_URI}",
    file=sys.stderr,
)

try:
    server = ReusableTCPServer(
        (
            CALLBACK_HOST,
            CALLBACK_PORT,
        ),
        CallbackHandler,
    )
except OSError as exception:
    print(
        f"Cannot listen on port {CALLBACK_PORT}: "
        f"{exception}",
        file=sys.stderr,
    )
    print(
        "Check whether another process is using "
        "localhost:3000.",
        file=sys.stderr,
    )
    sys.exit(1)


webbrowser.open(authorization_url)

with server:
    server.handle_request()


if "error" in authorization_result:
    print(
        "Authentication failed: "
        + authorization_result["error"],
        file=sys.stderr,
    )
    sys.exit(1)


authorization_code = (
    authorization_result["code"]
)


token_parameters = urllib.parse.urlencode(
    {
        "grant_type": "authorization_code",
        "client_id": CLIENT_ID,
        "code": authorization_code,
        "redirect_uri": REDIRECT_URI,
        "code_verifier": code_verifier,
    }
).encode("utf-8")


basic_credentials = base64.b64encode(
    f"{CLIENT_ID}:{CLIENT_SECRET}".encode(
        "utf-8"
    )
).decode("ascii")


token_request = urllib.request.Request(
    f"{ISSUER}/protocol/openid-connect/token",
    data=token_parameters,
    method="POST",
    headers={
        "Content-Type":
            "application/x-www-form-urlencoded",
        "Authorization":
            f"Basic {basic_credentials}",
    },
)


try:
    with urllib.request.urlopen(
        token_request,
        timeout=10,
    ) as response:
        token_response = json.load(response)

except urllib.error.HTTPError as exception:
    body = exception.read().decode(
        "utf-8",
        errors="replace",
    )

    print(
        f"Token exchange failed "
        f"({exception.code}): {body}",
        file=sys.stderr,
    )
    sys.exit(1)


access_token = token_response.get(
    "access_token"
)

if not access_token:
    print(
        "Keycloak did not return an access token.",
        file=sys.stderr,
    )
    sys.exit(1)


TOKEN_FILE.parent.mkdir(
    parents=True,
    exist_ok=True,
)

TOKEN_FILE.write_text(
    access_token,
    encoding="utf-8",
)

TOKEN_FILE.chmod(0o600)


print(
    f"Access token saved to {TOKEN_FILE}",
    file=sys.stderr,
)

print(
    "Token was intentionally not printed.",
    file=sys.stderr,
)
