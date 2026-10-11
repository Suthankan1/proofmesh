"use server";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { resolveApproval } from "@/lib/api/control-plane-client";
import { ControlPlaneError } from "@/lib/api/control-plane-error";
import { getKeycloakAccessToken, KeycloakReauthenticationRequiredError } from "@/lib/auth/keycloak-access-token";
import { getOperatorContext } from "@/lib/auth/operator-context";

async function resolve(operation: "approve" | "reject", data: FormData) {
  const id = data.get("id"), rationale = data.get("rationale");
  if (typeof id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id) || typeof rationale !== "string" || !rationale.trim() || rationale.length > 1000) redirect("/approvals?error=invalid");
  try {
    const requestHeaders = await headers();
    const operator = await getOperatorContext(requestHeaders);
    if (!operator.proofMeshAuthorities.includes("ROLE_APPROVER")) redirect("/approvals?error=forbidden");
    await resolveApproval(id, operation, rationale, await getKeycloakAccessToken(requestHeaders));
  } catch (error) {
    if (error instanceof KeycloakReauthenticationRequiredError) redirect("/auth/reauthenticate");
    if (error instanceof ControlPlaneError) redirect(`/approvals/${id}?error=rejected`);
    throw error;
  }
  revalidatePath("/approvals");
  redirect(`/approvals/${id}`);
}
export async function approveAction(data: FormData) { await resolve("approve", data); }
export async function rejectAction(data: FormData) { await resolve("reject", data); }
