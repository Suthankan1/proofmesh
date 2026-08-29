import type { ReactNode } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { AppShell } from "@/components/layout/app-shell";
import { ControlPlaneFailure } from "@/features/system/control-plane-failure";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { auth } from "@/lib/auth/auth";
import {
  KeycloakReauthenticationRequiredError,
} from "@/lib/auth/keycloak-access-token";
import {
  getOperatorContext,
} from "@/lib/auth/operator-context";
import {
  formatRoleLabel,
} from "@/lib/auth/role-label";

type AuthenticatedLayoutProps = {
  children: ReactNode;
};

export default async function AuthenticatedLayout({
  children,
}: AuthenticatedLayoutProps) {
  const requestHeaders = await headers();

  const session = await auth.api.getSession({
    headers: requestHeaders,
  });

  if (!session) {
    redirect("/sign-in");
  }

  let operator;

  try {
    operator = await getOperatorContext(
      requestHeaders,
    );
  } catch (error) {
    if (
      error instanceof
      KeycloakReauthenticationRequiredError
    ) {
      redirect("/auth/reauthenticate");
    }

    if (error instanceof ControlPlaneError) {
      return (
        <ControlPlaneFailure
          kind={error.kind}
        />
      );
    }

    throw error;
  }

  return (
    <AppShell
      organizationName={
        operator.organization.organizationSlug
      }
      roleLabel={formatRoleLabel(
        operator.proofMeshAuthorities,
      )}
    >
      {children}
    </AppShell>
  );
}