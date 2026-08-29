import type { ComponentProps } from "react";

import { cn } from "@/lib/styles/cn";

type SurfaceCardProps = ComponentProps<"section">;

export function SurfaceCard({
  className,
  ...props
}: SurfaceCardProps) {
  return (
    <section
      className={cn(
        "rounded-lg border border-border bg-surface shadow-surface",
        className,
      )}
      {...props}
    />
  );
}