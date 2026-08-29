import type { ReactNode } from "react";

import { AppShell } from "@/components/layout/app-shell";

type AuthenticatedLayoutProps = {
  children: ReactNode;
};

export default function AuthenticatedLayout({
  children,
}: AuthenticatedLayoutProps) {
  return (
    <AppShell
      organizationName="ProofMesh Demo"
      roleLabel="Identity integration pending"
    >
      {children}
    </AppShell>
  );
}