import type { ReactNode } from "react";

type PageHeaderProps = {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
};

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: PageHeaderProps) {
  return (
    <header className="flex flex-col gap-4 border-b border-border pb-6 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        {eyebrow ? (
          <p className="text-xs font-semibold tracking-[0.14em] text-brand uppercase">
            {eyebrow}
          </p>
        ) : null}

        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-foreground">
          {title}
        </h1>

        {description ? (
          <p className="mt-2 max-w-3xl text-sm leading-6 text-foreground-secondary">
            {description}
          </p>
        ) : null}
      </div>

      {actions ? (
        <div className="flex shrink-0 items-center gap-2">
          {actions}
        </div>
      ) : null}
    </header>
  );
}