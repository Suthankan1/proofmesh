import "server-only";

import { auth } from "@/lib/auth/auth";

const KEYCLOAK_PROVIDER_ID = "keycloak";

export class KeycloakAccountNotLinkedError extends Error {
  constructor() {
    super(
      "The authenticated user does not have a linked Keycloak account.",
    );

    this.name = "KeycloakAccountNotLinkedError";
  }
}

export class KeycloakAccessTokenUnavailableError extends Error {
  constructor() {
    super(
      "A Keycloak access token is unavailable for the authenticated user.",
    );

    this.name = "KeycloakAccessTokenUnavailableError";
  }
}

export async function getKeycloakAccessToken(
  requestHeaders: Headers,
): Promise<string> {
  const accounts =
    await auth.api.listUserAccounts({
      headers: requestHeaders,
    });

  const keycloakAccount =
    accounts.find(
      (account) =>
        account.providerId ===
        KEYCLOAK_PROVIDER_ID,
    );

  if (!keycloakAccount) {
    throw new KeycloakAccountNotLinkedError();
  }

  const token =
    await auth.api.getAccessToken({
        headers: requestHeaders,

        body: {
        accountId: keycloakAccount.id,
        },
    });

  if (!token.accessToken) {
    throw new KeycloakAccessTokenUnavailableError();
  }

  return token.accessToken;
}