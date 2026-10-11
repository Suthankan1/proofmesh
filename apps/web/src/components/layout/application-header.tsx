import { SignOutButton } from "@/features/auth/sign-out-button";

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
      <div className="flex min-h-16 flex-wrap items-center justify-between gap-3 px-6 py-3 lg:px-8">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-foreground">
            {organizationName}
          </p>

          <p className="mt-0.5 text-xs text-foreground-tertiary">
            Current organization
          </p>
        </div>

        <div className="flex min-w-0 flex-wrap items-center gap-3">
          <span className="max-w-full break-words rounded-md bg-surface-subtle px-2.5 py-1 text-xs font-semibold text-foreground-secondary">
            {roleLabel}
          </span>

          <SignOutButton />
        </div>
      </div>
    </header>
  );
}