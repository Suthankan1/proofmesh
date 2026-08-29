import { PageHeader } from "@/components/layout/page-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { SurfaceCard } from "@/components/ui/surface-card";

export default function DashboardPage() {
  return (
    <>
      <PageHeader
        eyebrow="Overview"
        title="Dashboard"
        description="Runtime governance, operator attention and assurance status."
      />

      <div className="mt-8 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <SurfaceCard className="p-5">
          <p className="text-xs font-semibold tracking-[0.1em] text-foreground-tertiary uppercase">
            Governed actions
          </p>

          <p className="mt-3 text-2xl font-semibold text-foreground">
            —
          </p>

          <p className="mt-2 text-xs text-foreground-tertiary">
            Runtime API not connected yet
          </p>
        </SurfaceCard>

        <SurfaceCard className="p-5">
          <p className="text-xs font-semibold tracking-[0.1em] text-foreground-tertiary uppercase">
            Pending approvals
          </p>

          <p className="mt-3 text-2xl font-semibold text-foreground">
            —
          </p>

          <p className="mt-2 text-xs text-foreground-tertiary">
            Runtime API not connected yet
          </p>
        </SurfaceCard>

        <SurfaceCard className="p-5">
          <p className="text-xs font-semibold tracking-[0.1em] text-foreground-tertiary uppercase">
            Execution unknown
          </p>

          <p className="mt-3 text-2xl font-semibold text-foreground">
            —
          </p>

          <p className="mt-2 text-xs text-foreground-tertiary">
            Runtime API not connected yet
          </p>
        </SurfaceCard>

        <SurfaceCard className="p-5">
          <p className="text-xs font-semibold tracking-[0.1em] text-foreground-tertiary uppercase">
            Open incidents
          </p>

          <p className="mt-3 text-2xl font-semibold text-foreground">
            —
          </p>

          <p className="mt-2 text-xs text-foreground-tertiary">
            Runtime API not connected yet
          </p>
        </SurfaceCard>
      </div>

      <div className="mt-4 grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <SurfaceCard className="min-h-72 p-6">
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-sm font-semibold text-foreground">
                Recent governed actions
              </p>

              <p className="mt-1 text-sm text-foreground-secondary">
                Real runtime data will appear here after the governed
                action read model is implemented.
              </p>
            </div>
          </div>

          <div className="mt-8 rounded-lg border border-dashed border-border-strong bg-surface-subtle p-8 text-center">
            <p className="text-sm font-medium text-foreground">
              No runtime data connected
            </p>

            <p className="mt-1 text-sm text-foreground-secondary">
              We intentionally avoid displaying invented governed actions.
            </p>
          </div>
        </SurfaceCard>

        <SurfaceCard className="min-h-72 p-6">
          <p className="text-sm font-semibold text-foreground">
            Runtime semantics
          </p>

          <p className="mt-1 text-sm text-foreground-secondary">
            ProofMesh distinguishes uncertain execution from failure.
          </p>

          <div className="mt-6 flex flex-col items-start gap-3">
            <StatusBadge status="ALLOW" />
            <StatusBadge status="REQUIRE_APPROVAL" />
            <StatusBadge status="DENY" />
            <StatusBadge status="EXECUTION_UNKNOWN" />
          </div>
        </SurfaceCard>
      </div>
    </>
  );
}