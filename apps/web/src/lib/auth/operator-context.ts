import "server-only";

import {
  getIdentity,
  getOrganizationContext,
} from "@/lib/api/control-plane-client";
import { getKeycloakAccessToken } from "@/lib/auth/keycloak-access-token";

const proofMeshAuthorities = [
  "ROLE_PLATFORM_ADMIN",
  "ROLE_APPROVER",
  "ROLE_SECURITY_OPERATOR",
  "ROLE_VIEWER",
] as const;

export type ProofMeshAuthority =
  (typeof proofMeshAuthorities)[number];

function isProofMeshAuthority(
  authority: string,
): authority is ProofMeshAuthority {
  return (
    proofMeshAuthorities as readonly string[]
  ).includes(authority);
}

export type OperatorContext = {
  subject: string;
  authorities: readonly string[];
  proofMeshAuthorities:
    readonly ProofMeshAuthority[];

  organization: {
    userId: string;
    organizationId: string;
    organizationSlug: string;
  };
};

export async function getOperatorContext(
  requestHeaders: Headers,
): Promise<OperatorContext> {
  const accessToken =
    await getKeycloakAccessToken(
      requestHeaders,
    );

  const [
    identity,
    organization,
  ] = await Promise.all([
    getIdentity(accessToken),
    getOrganizationContext(accessToken),
  ]);

  if (
    identity.subject !==
      organization.oidcSubject
  ) {
    throw new Error(
      "Identity and organization context subjects do not match.",
    );
  }

  const roles =
    identity.authorities.filter(
      isProofMeshAuthority,
    );

  return {
    subject: identity.subject,
    authorities: identity.authorities,
    proofMeshAuthorities: roles,

    organization: {
      userId: organization.userId,
      organizationId:
        organization.organizationId,
      organizationSlug:
        organization.organizationSlug,
    },
  };
}