import Link from "next/link";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/layout/page-header";
import { SurfaceCard } from "@/components/ui/surface-card";
import { getApprovals } from "@/lib/api/control-plane-client";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { getKeycloakAccessToken, KeycloakReauthenticationRequiredError } from "@/lib/auth/keycloak-access-token";
import { ControlPlaneFailure } from "@/features/system/control-plane-failure";

export default async function ApprovalsPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const query = await searchParams;
  let approvals;
  try { approvals = await getApprovals(await getKeycloakAccessToken(await headers())); }
  catch (error) {
    if (error instanceof KeycloakReauthenticationRequiredError) redirect("/auth/reauthenticate");
    if (error instanceof ControlPlaneError) return <ControlPlaneFailure kind={error.kind} />;
    throw error;
  }
  return <>
    <PageHeader eyebrow="Operations" title="Approvals" description="Review the exact action before deciding. Approval authorizes an agent retry." />
    {query.error ? <p role="alert" className="mt-4 text-danger-foreground">The request was rejected. Check your permissions and try again.</p> : null}
    <SurfaceCard className="mt-8 p-6">
      {approvals.length === 0 ? <p>No pending approvals.</p> : <ul className="divide-y divide-border">{approvals.map(approval => <li key={approval.id} className="py-4">
        <Link className="font-semibold text-brand underline" href={`/approvals/${approval.id}`}>{approval.toolName} / {approval.operationName}</Link>
        <p className="mt-1 text-sm">Agent {approval.agentId}</p>
        <p className="text-sm">Valid until <time dateTime={approval.expiresAt}>{approval.expiresAt}</time></p>
      </li>)}</ul>}
      {approvals.length === 100 ? <p className="mt-4 text-sm">Showing the first 100 pending approvals.</p> : null}
    </SurfaceCard>
  </>;
}
