import { cn } from "@/lib/styles/cn";

export type ProofMeshStatus =
  | "ALLOW"
  | "DENY"
  | "REQUIRE_APPROVAL"
  | "EXECUTION_UNKNOWN"
  | "SUCCEEDED"
  | "FAILED"
  | "PENDING"
  | "VERIFIED";

const statusStyles: Record<ProofMeshStatus, string> = {
  ALLOW:
    "bg-success-bg text-success-foreground",
  SUCCEEDED:
    "bg-success-bg text-success-foreground",
  VERIFIED:
    "bg-success-bg text-success-foreground",

  REQUIRE_APPROVAL:
    "bg-warning-bg text-warning-foreground",
  PENDING:
    "bg-warning-bg text-warning-foreground",

  DENY:
    "bg-danger-bg text-danger-foreground",
  FAILED:
    "bg-danger-bg text-danger-foreground",

  EXECUTION_UNKNOWN:
    "bg-info-bg text-info-foreground",
};

type StatusBadgeProps = {
  status: ProofMeshStatus;
  className?: string;
};

export function StatusBadge({
  status,
  className,
}: StatusBadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex min-h-6 items-center rounded-md px-2 py-1",
        "text-[0.6875rem] font-semibold tracking-[0.04em]",
        statusStyles[status],
        className,
      )}
    >
      {status}
    </span>
  );
}