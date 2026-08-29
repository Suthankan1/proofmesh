import "server-only";

import type { ZodType } from "zod";

import {
  identityResponseSchema,
  organizationContextSchema,
  type IdentityResponse,
  type OrganizationContext,
} from "@/lib/api/control-plane-contracts";
import {
  ControlPlaneError,
} from "@/lib/api/control-plane-error";
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
      "The ProofMesh control plane is unavailable.",
      {
        kind: "UNAVAILABLE",
        status: 503,
        path,
      },
    );
  }

  if (response.status === 401) {
    throw new ControlPlaneError(
      "The ProofMesh control plane rejected the operator identity.",
      {
        kind: "UNAUTHORIZED",
        status: 401,
        path,
      },
    );
  }

  if (response.status === 403) {
    throw new ControlPlaneError(
      "The operator is not authorized to access this resource.",
      {
        kind: "FORBIDDEN",
        status: 403,
        path,
      },
    );
  }

  if (response.status >= 500) {
    throw new ControlPlaneError(
      "The ProofMesh control plane returned an upstream error.",
      {
        kind: "UPSTREAM_ERROR",
        status: response.status,
        path,
      },
    );
  }

  if (!response.ok) {
    throw new ControlPlaneError(
      `The ProofMesh control plane rejected the request with HTTP ${response.status}.`,
      {
        kind: "UPSTREAM_ERROR",
        status: response.status,
        path,
      },
    );
  }

  let body: unknown;

  try {
    body = await response.json();
  } catch {
    throw new ControlPlaneError(
      "The ProofMesh control plane returned invalid JSON.",
      {
        kind: "CONTRACT_VIOLATION",
        status: 502,
        path,
      },
    );
  }

  const result = schema.safeParse(body);

  if (!result.success) {
    console.error(
      "Control-plane response contract validation failed",
      {
        path,
        issues: result.error.issues.map(
          (issue) => ({
            path: issue.path.join("."),
            code: issue.code,
            message: issue.message,
          }),
        ),
      },
    );

    throw new ControlPlaneError(
      `The ProofMesh control plane returned an unexpected response contract for ${path}.`,
      {
        kind: "CONTRACT_VIOLATION",
        status: 502,
        path,
      },
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