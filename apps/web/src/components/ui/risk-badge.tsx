import { cn } from "@/lib/styles/cn";

export type RiskLevel =
  | "LOW"
  | "MEDIUM"
  | "HIGH"
  | "CRITICAL";

const riskStyles: Record<RiskLevel, string> = {
  LOW:
    "bg-success-bg text-success-foreground",

  MEDIUM:
    "bg-info-bg text-info-foreground",

  HIGH:
    "bg-warning-bg text-warning-foreground",

  CRITICAL:
    "bg-danger-bg text-danger-foreground",
};

type RiskBadgeProps = {
  risk: RiskLevel;
  className?: string;
};

export function RiskBadge({
  risk,
  className,
}: RiskBadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex min-h-6 items-center rounded-md px-2 py-1",
        "text-[0.6875rem] font-semibold tracking-[0.04em]",
        riskStyles[risk],
        className,
      )}
    >
      {risk}
    </span>
  );
}