import { NavigationItem } from "@/components/layout/navigation-item";
import { navigationGroups } from "@/components/layout/navigation";

export function Sidebar() {
  return (
    <aside className="hidden w-[15rem] shrink-0 border-r border-border bg-surface lg:block">
      <div className="sticky top-0 flex h-screen flex-col">
        <div className="border-b border-border px-5 py-6">
          <p className="text-lg font-semibold tracking-tight text-foreground">
            ProofMesh
          </p>

          <p className="mt-1 text-[0.6875rem] font-semibold tracking-[0.14em] text-foreground-tertiary uppercase">
            Control Plane
          </p>
        </div>

        <nav
          aria-label="Primary"
          className="flex-1 space-y-6 overflow-y-auto px-4 py-5"
        >
          {navigationGroups.map((group) => (
            <div key={group.label}>
              <p className="mb-2 px-3 text-[0.6875rem] font-semibold tracking-[0.12em] text-foreground-tertiary uppercase">
                {group.label}
              </p>

              <div className="space-y-1">
                {group.items.map((item) => (
                  <NavigationItem
                    key={item.href}
                    href={item.href}
                    label={item.label}
                  />
                ))}
              </div>
            </div>
          ))}
        </nav>
      </div>
    </aside>
  );
}