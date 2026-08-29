import "server-only";

import type { ZodType } from "zod";

import {
  identityResponseSchema,
  organizationContextSchema,
  type IdentityResponse,
  type OrganizationContext,
} from "@/lib/api/control-plane-contracts";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { serverEnv } from "@/lib/config/server-env";

const REQUEST_TIMEOUT_MS = 5_000;

async function getFromControlPlane<T>(
  path: string,
  accessToken: string,
  schema: ZodType<T>,
): Promise<T> {
  const url = new URL(
    path,
    serverEnv.controlPlane.baseUrl,
  );

  let response: Response;

  try {
    response = await fetch(url, {
      method: "GET",

      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${accessToken}`,
      },

      cache: "no-store",

      signal: AbortSignal.timeout(
        REQUEST_TIMEOUT_MS,
      ),
    });
  } catch {
    throw new ControlPlaneError(
      "The ProofMesh control plane could not be reached.",
      0,
      path,
    );
  }

  if (!response.ok) {
    throw new ControlPlaneError(
      `The ProofMesh control plane returned HTTP ${response.status}.`,
      response.status,
      path,
    );
  }

  const body: unknown =
    await response.json();

  const result =
    schema.safeParse(body);

  if (!result.success) {
    console.error(
      "Control-plane response contract validation failed",
      {
        path,
        issues: result.error.issues.map((issue) => ({
          path: issue.path.join("."),
          code: issue.code,
          message: issue.message,
        })),
      },
    );

    throw new ControlPlaneError(
      `The ProofMesh control plane returned an unexpected response contract for ${path}.`,
      502,
      path,
    );
  }

  return result.data;
}

export function getIdentity(
  accessToken: string,
): Promise<IdentityResponse> {
  return getFromControlPlane(
    "/api/v1/identity/me",
    accessToken,
    identityResponseSchema,
  );
}

export function getOrganizationContext(
  accessToken: string,
): Promise<OrganizationContext> {
  return getFromControlPlane(
    "/api/v1/identity/context",
    accessToken,
    organizationContextSchema,
  );
}