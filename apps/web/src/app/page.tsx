const states = [
  {
    label: "ALLOW",
    className: "bg-success-bg text-success-foreground",
  },
  {
    label: "REQUIRE_APPROVAL",
    className: "bg-warning-bg text-warning-foreground",
  },
  {
    label: "DENY",
    className: "bg-danger-bg text-danger-foreground",
  },
  {
    label: "EXECUTION_UNKNOWN",
    className: "bg-info-bg text-info-foreground",
  },
] as const;

export default function Home() {
  return (
    <main className="min-h-screen bg-canvas px-6 py-10 text-foreground lg:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="border-b border-border pb-7">
          <p className="text-sm font-semibold tracking-[0.16em] text-brand uppercase">
            ProofMesh
          </p>

          <h1 className="mt-3 text-3xl font-semibold tracking-tight">
            Frontend foundation
          </h1>

          <p className="mt-2 max-w-2xl text-sm leading-6 text-foreground-secondary">
            Precision Light tokens are now connected to the Next.js
            application. This is an engineering verification surface,
            not the final dashboard.
          </p>
        </header>

        <section className="mt-8 grid gap-4 md:grid-cols-2">
          <article className="rounded-lg border border-border bg-surface p-6 shadow-surface">
            <p className="text-xs font-semibold tracking-[0.12em] text-foreground-tertiary uppercase">
              Surface hierarchy
            </p>

            <h2 className="mt-3 text-lg font-semibold">
              Quiet by default
            </h2>

            <p className="mt-2 text-sm leading-6 text-foreground-secondary">
              Canvas, surface, text, border and elevation are expressed
              through semantic ProofMesh tokens rather than page-specific
              color values.
            </p>
          </article>

          <article className="rounded-lg border border-border bg-surface p-6 shadow-surface">
            <p className="text-xs font-semibold tracking-[0.12em] text-foreground-tertiary uppercase">
              Runtime semantics
            </p>

            <h2 className="mt-3 text-lg font-semibold">
              Status has meaning
            </h2>

            <div className="mt-4 flex flex-wrap gap-2">
              {states.map((state) => (
                <span
                  key={state.label}
                  className={`rounded-md px-2.5 py-1 text-xs font-semibold ${state.className}`}
                >
                  {state.label}
                </span>
              ))}
            </div>
          </article>
        </section>

        <section className="mt-4 rounded-lg border border-border bg-surface-subtle p-6">
          <p className="text-sm font-medium">
            Next milestone
          </p>

          <p className="mt-1 text-sm text-foreground-secondary">
            Reusable ProofMesh UI primitives and the authenticated
            application shell.
          </p>
        </section>
      </div>
    </main>
  );
}