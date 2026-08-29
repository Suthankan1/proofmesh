import type { ReactNode } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { AppShell } from "@/components/layout/app-shell";
import { auth } from "@/lib/auth/auth";
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
  const requestHeaders =
    await headers();

  const session =
    await auth.api.getSession({
      headers: requestHeaders,
    });

  if (!session) {
    redirect("/sign-in");
  }

  const operator =
    await getOperatorContext(
      requestHeaders,
    );

  return (
    <AppShell
      organizationName={
        operator.organization
          .organizationSlug
      }
      roleLabel={formatRoleLabel(
        operator.proofMeshAuthorities,
      )}
    >
      {children}
    </AppShell>
  );
}