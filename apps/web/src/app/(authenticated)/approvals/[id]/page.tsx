import Link from "next/link";
import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { Button } from "@/components/ui/button";
import { SurfaceCard } from "@/components/ui/surface-card";
import { PageHeader } from "@/components/layout/page-header";
import { getApproval } from "@/lib/api/control-plane-client";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { getKeycloakAccessToken, KeycloakReauthenticationRequiredError } from "@/lib/auth/keycloak-access-token";
import { getOperatorContext } from "@/lib/auth/operator-context";
import { ControlPlaneFailure } from "@/features/system/control-plane-failure";
import { approveAction, rejectAction } from "../actions";

export default async function ApprovalDetail({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ error?: string }> }) {
  const { id } = await params;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) notFound();
  const query = await searchParams;
  let approval, operator;
  try {
    const requestHeaders = await headers();
    const token = await getKeycloakAccessToken(requestHeaders);
    [approval, operator] = await Promise.all([getApproval(id, token), getOperatorContext(requestHeaders)]);
  } catch (error) {
    if (error instanceof KeycloakReauthenticationRequiredError) redirect("/auth/reauthenticate");
    if (error instanceof ControlPlaneError) {
      if (error.status === 404) notFound();
      return <ControlPlaneFailure kind={error.kind} />;
    }
    throw error;
  }
  const canResolve = operator.proofMeshAuthorities.includes("ROLE_APPROVER") && approval.status === "PENDING";
  return <>
    <PageHeader eyebrow="Exact action review" title={`${approval.toolName} / ${approval.operationName}`} description={`Approval status: ${approval.status}`} />
    <Link href="/approvals" className="mt-4 inline-block text-brand underline">All pending approvals</Link>
    {query.error ? <p role="alert" className="mt-4 text-danger-foreground">The decision was rejected. Current authoritative status is shown below.</p> : null}
    <SurfaceCard className="mt-6 p-6">
      <dl className="grid gap-3 break-all text-sm">
        <div><dt className="font-semibold">Agent</dt><dd>{approval.agentId}</dd></div>
        <div><dt className="font-semibold">Action</dt><dd>{approval.governedActionId}</dd></div>
        <div><dt className="font-semibold">Payload hash</dt><dd>{approval.payloadHash}</dd></div>
        <div><dt className="font-semibold">Valid until</dt><dd><time dateTime={approval.expiresAt}>{approval.expiresAt}</time></dd></div>
      </dl>
      <h2 className="mt-6 font-semibold">Exact request payload</h2>
      <pre className="mt-3 overflow-auto whitespace-pre-wrap break-all rounded bg-canvas p-4 text-sm">{approval.requestPayloadJson}</pre>
      {canResolve ? <form className="mt-6 space-y-3">
        <input type="hidden" name="id" value={id} />
        <label className="block font-semibold" htmlFor="rationale">Decision rationale</label>
        <textarea id="rationale" name="rationale" required maxLength={1000} className="min-h-24 w-full rounded border border-border bg-surface p-3" />
        <div className="flex gap-3"><Button type="submit" formAction={approveAction}>Approve exact action</Button><Button type="submit" variant="danger" formAction={rejectAction}>Reject action</Button></div>
      </form> : <p className="mt-6 text-sm">Only an approver can resolve a pending, time-valid request.</p>}
    </SurfaceCard>
  </>;
}
