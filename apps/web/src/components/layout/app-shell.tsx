import type { ReactNode } from "react";

import { ApplicationHeader } from "@/components/layout/application-header";
import { Sidebar } from "@/components/layout/sidebar";

type AppShellProps = {
  children: ReactNode;
  organizationName: string;
  roleLabel: string;
};

export function AppShell({
  children,
  organizationName,
  roleLabel,
}: AppShellProps) {
  return (
    <div className="min-h-screen bg-canvas lg:flex">
      <Sidebar />

      <div className="min-w-0 flex-1">
        <ApplicationHeader
          organizationName={organizationName}
          roleLabel={roleLabel}
        />

        <main className="px-6 py-8 lg:px-8 lg:py-10">
          <div className="mx-auto max-w-[90rem]">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}