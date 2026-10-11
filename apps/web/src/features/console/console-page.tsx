import Link from "next/link";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/layout/page-header";
import { SurfaceCard } from "@/components/ui/surface-card";
import { getConsole } from "@/lib/api/control-plane-client";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { getKeycloakAccessToken, KeycloakReauthenticationRequiredError } from "@/lib/auth/keycloak-access-token";
import { ControlPlaneFailure } from "@/features/system/control-plane-failure";

const titles = { dashboard: "Dashboard", actions: "Governed actions", agents: "Agents", policies: "Policies", tools: "Governed operations", organization: "Organization" } as const;
export async function ConsolePage({ section }: { section: keyof typeof titles }) {
  let data;
  try { data = await getConsole(await getKeycloakAccessToken(await headers())); }
  catch (error) {
    if (error instanceof KeycloakReauthenticationRequiredError) redirect("/auth/reauthenticate");
    if (error instanceof ControlPlaneError) return <ControlPlaneFailure kind={error.kind} />;
    throw error;
  }
  const operations = [...new Set(data.actions.map(a => `${a.toolName} / ${a.operationName}`))];
  return <>
    <PageHeader eyebrow={data.organization.name} title={titles[section]} description="Authoritative tenant data. Lists show up to the 100 most recent records; refresh to see changes." />
    <SurfaceCard className="mt-8 p-6">
      {section === "dashboard" && <div className="grid gap-6 sm:grid-cols-3">
        <div><p>Recent governed actions</p><strong className="text-3xl">{data.actions.length}</strong><p><Link href="/actions" className="text-brand underline">Review actions</Link></p></div>
        <div><p>Pending, unexpired approvals</p><strong className="text-3xl">{data.pendingApprovals}</strong><p><Link href="/approvals" className="text-brand underline">Review approvals</Link></p></div>
        <div><p>Registered agents shown</p><strong className="text-3xl">{data.agents.length}</strong><p><Link href="/agents" className="text-brand underline">View agents</Link></p></div>
      </div>}
      {section === "actions" && <div className="overflow-x-auto"><table className="w-full text-left text-sm"><caption className="mb-4 text-left">Governance outcomes authorize evaluation; they do not prove tool execution. Evidence records observed execution separately.</caption><thead><tr><th className="p-2">Action</th><th className="p-2">Operation</th><th className="p-2">Decision</th><th className="p-2">Risk</th><th className="p-2">Created</th></tr></thead><tbody>{data.actions.map(a => <tr key={a.id} className="border-t border-border"><td className="p-2"><Link className="text-brand underline" href={`/evidence?action=${a.id}`}>{a.id}</Link></td><td className="p-2">{a.toolName} / {a.operationName}</td><td className="p-2">{a.outcome ?? "No decision recorded"}</td><td className="p-2">{a.riskScore ?? "Unavailable"}</td><td className="p-2"><time dateTime={a.createdAt}>{a.createdAt}</time></td></tr>)}</tbody></table>{!data.actions.length && <p>No governed actions yet.</p>}</div>}
      {section === "agents" && <ul className="divide-y divide-border">{data.agents.map(a => <li key={a.id} className="py-4"><strong>{a.name}</strong><p>{a.status}</p><p className="break-all text-sm">{a.id}</p></li>)}{!data.agents.length && <li>No registered agents.</li>}</ul>}
      {section === "policies" && <ul className="divide-y divide-border">{data.policies.map(p => <li key={p.id} className="py-4"><strong>{p.name} · version {p.version}</strong><p>{p.state}</p><p className="break-all text-sm">Definition hash: {p.definitionHash ?? "Draft has no publication hash"}</p><details className="mt-2"><summary>Review definition</summary><pre className="overflow-x-auto p-4 text-xs">{JSON.stringify(p.definition, null, 2)}</pre></details></li>)}{!data.policies.length && <li>No policy versions.</li>}</ul>}
      {section === "tools" && <><p className="mb-4">Operations observed in recent governed actions. Gateway routing is configured locally; this list grants no execution authority.</p><ul>{operations.map(o => <li key={o} className="py-2">{o}</li>)}</ul>{!operations.length && <p>No operations observed.</p>}</>}
      {section === "organization" && <dl className="space-y-3"><div><dt>Name</dt><dd>{data.organization.name}</dd></div><div><dt>Slug</dt><dd>{data.organization.slug}</dd></div><div><dt>Status</dt><dd>{data.organization.status}</dd></div><div><dt>Tenant ID</dt><dd className="break-all">{data.organization.id}</dd></div></dl>}
    </SurfaceCard>
  </>;
}
