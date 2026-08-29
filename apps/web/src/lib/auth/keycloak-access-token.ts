import "server-only";

import {
  isAPIError,
} from "better-auth/api";

import { auth } from "@/lib/auth/auth";

export class KeycloakAccountNotLinkedError extends Error {
  constructor() {
    super(
      "The authenticated user does not have a linked Keycloak account.",
    );

    this.name = "KeycloakAccountNotLinkedError";
  }
}

export class KeycloakReauthenticationRequiredError extends Error {
  constructor() {
    super(
      "The Keycloak session can no longer provide a valid access token.",
    );

    this.name = "KeycloakReauthenticationRequiredError";
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
        account.providerId === "keycloak",
    );

  if (!keycloakAccount) {
    throw new KeycloakAccountNotLinkedError();
  }

  try {
    const token =
      await auth.api.getAccessToken({
        headers: requestHeaders,

        body: {
          accountId: keycloakAccount.id,
        },
      });

    if (!token.accessToken) {
      throw new KeycloakReauthenticationRequiredError();
    }

    return token.accessToken;
  } catch (error) {
    if (
      error instanceof
        KeycloakReauthenticationRequiredError
    ) {
      throw error;
    }

    if (
      isAPIError(error) &&
      error.body?.code ===
        "FAILED_TO_GET_ACCESS_TOKEN"
    ) {
      throw new KeycloakReauthenticationRequiredError();
    }

    throw error;
  }
}