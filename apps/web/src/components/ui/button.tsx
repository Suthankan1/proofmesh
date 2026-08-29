import type { ComponentProps } from "react";

import { cn } from "@/lib/styles/cn";

const variants = {
  primary:
    "bg-brand text-white hover:bg-brand-hover",
  secondary:
    "border border-border-strong bg-surface text-foreground hover:bg-surface-subtle",
  danger:
    "border border-danger-foreground/15 bg-danger-bg text-danger-foreground hover:bg-danger-foreground/10",
  ghost:
    "bg-transparent text-foreground-secondary hover:bg-surface-subtle hover:text-foreground",
} as const;

const sizes = {
  sm: "h-8 px-3 text-xs",
  md: "h-9 px-4 text-sm",
} as const;

export type ButtonVariant = keyof typeof variants;
export type ButtonSize = keyof typeof sizes;

type ButtonProps = ComponentProps<"button"> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
};

export function Button({
  className,
  variant = "primary",
  size = "md",
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-md font-semibold",
        "transition-colors duration-150",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2",
        "focus-visible:ring-offset-surface",
        "disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
        variants[variant],
        sizes[size],
        className,
      )}
      {...props}
    />
  );
}