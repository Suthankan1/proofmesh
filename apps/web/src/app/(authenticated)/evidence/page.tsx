import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/layout/page-header";
import { SurfaceCard } from "@/components/ui/surface-card";
import { getEvidence } from "@/lib/api/control-plane-client";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { getKeycloakAccessToken, KeycloakReauthenticationRequiredError } from "@/lib/auth/keycloak-access-token";
import { getOperatorContext } from "@/lib/auth/operator-context";
import { ControlPlaneFailure } from "@/features/system/control-plane-failure";
export default async function EvidencePage({ searchParams }: { searchParams: Promise<{ action?: string }> }) {
  const { action } = await searchParams;
  let evidence;
  let failure: "FORBIDDEN" | "CONTRACT_VIOLATION" | undefined;
  try {
    const requestHeaders = await headers();
    const operator = await getOperatorContext(requestHeaders);
    if (!operator.proofMeshAuthorities.some(r => r === "ROLE_SECURITY_OPERATOR" || r === "ROLE_PLATFORM_ADMIN")) failure = "FORBIDDEN";
    if (!failure && action && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(action)) {
      evidence = await getEvidence(action, await getKeycloakAccessToken(requestHeaders));
      if (evidence.organizationId !== operator.organization.organizationId || evidence.action.organization_id !== evidence.organizationId || evidence.action.id !== action) failure = "CONTRACT_VIOLATION";
    }
  } catch (error) {
    if (error instanceof KeycloakReauthenticationRequiredError) redirect("/auth/reauthenticate");
    if (error instanceof ControlPlaneError) return <ControlPlaneFailure kind={error.kind} />;
    throw error;
  }
  if (failure) return <ControlPlaneFailure kind={failure} />;
  return <><PageHeader eyebrow="Assurance" title="Evidence" description="Inspect exact-action provenance and observed gateway outcomes. An unknown outcome never proves failure or authorizes a retry." />
    <SurfaceCard className="mt-8 p-6"><form method="get" className="flex flex-wrap gap-3"><label htmlFor="action">Action ID</label><input id="action" name="action" required defaultValue={action} className="min-w-80 rounded border border-border p-2" /><button className="rounded bg-brand px-4 py-2 text-white">Inspect evidence</button></form>
      {action && !evidence && <p role="alert" className="mt-4">Enter a valid action UUID.</p>}
      {evidence && <><p className="mt-4">Read-only snapshot. Archive copies can be saved with the local evidence archive command.</p><pre className="mt-4 overflow-x-auto rounded bg-surface-subtle p-4 text-xs">{JSON.stringify(evidence, null, 2)}</pre></>}
    </SurfaceCard></>;
}
