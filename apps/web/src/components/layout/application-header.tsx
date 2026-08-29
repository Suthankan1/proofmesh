type ApplicationHeaderProps = {
  organizationName: string;
  roleLabel: string;
};

export function ApplicationHeader({
  organizationName,
  roleLabel,
}: ApplicationHeaderProps) {
  return (
    <header className="border-b border-border bg-surface">
      <div className="flex min-h-16 items-center justify-between gap-6 px-6 lg:px-8">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-foreground">
            {organizationName}
          </p>

          <p className="mt-0.5 text-xs text-foreground-tertiary">
            Current organization
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-3">
          <span className="rounded-md bg-surface-subtle px-2.5 py-1 text-xs font-semibold text-foreground-secondary">
            {roleLabel}
          </span>
        </div>
      </div>
    </header>
  );
}