import type {
  ProofMeshAuthority,
} from "@/lib/auth/operator-context";

const labels:
  Record<ProofMeshAuthority, string> = {
    ROLE_PLATFORM_ADMIN:
      "Platform Admin",

    ROLE_APPROVER:
      "Approver",

    ROLE_SECURITY_OPERATOR:
      "Security Operator",

    ROLE_VIEWER:
      "Viewer",
  };

export function formatRoleLabel(
  authorities:
    readonly ProofMeshAuthority[],
): string {
  if (authorities.length === 0) {
    return "No ProofMesh role";
  }

  return authorities
    .map(
      (authority) =>
        labels[authority],
    )
    .join(" · ");
}