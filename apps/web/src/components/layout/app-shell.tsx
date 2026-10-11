import type { ReactNode } from "react";

import { ApplicationHeader } from "@/components/layout/application-header";
import { NavigationItem } from "@/components/layout/navigation-item";
import { navigationGroups } from "@/components/layout/navigation";
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

        <details className="border-b border-border bg-surface px-6 py-3 lg:hidden">
          <summary className="cursor-pointer font-semibold">Navigation</summary>
          <nav aria-label="Mobile primary" className="mt-3 grid gap-2 sm:grid-cols-2">
            {navigationGroups.map(group => group.items.map(item => <NavigationItem key={item.href} href={item.href} label={item.label} />))}
          </nav>
        </details>
        <main className="px-6 py-8 lg:px-8 lg:py-10">
          <div className="mx-auto max-w-[90rem]">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}