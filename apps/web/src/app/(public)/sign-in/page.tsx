import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { SurfaceCard } from "@/components/ui/surface-card";
import { SignInButton } from "@/features/auth/sign-in-button";
import { auth } from "@/lib/auth/auth";

export default async function SignInPage() {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (session) {
    redirect("/dashboard");
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas px-6 py-12">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <p className="text-xl font-semibold tracking-tight text-foreground">
            ProofMesh
          </p>

          <p className="mt-1 text-xs font-semibold tracking-[0.14em] text-foreground-tertiary uppercase">
            Control Plane
          </p>
        </div>

        <SurfaceCard className="p-7">
          <p className="text-xs font-semibold tracking-[0.12em] text-brand uppercase">
            Operator access
          </p>

          <h1 className="mt-3 text-2xl font-semibold tracking-tight text-foreground">
            Sign in
          </h1>

          <p className="mt-2 text-sm leading-6 text-foreground-secondary">
            Authenticate through the ProofMesh identity provider
            to access runtime governance and assurance workflows.
          </p>

          <div className="mt-7">
            <SignInButton />
          </div>
        </SurfaceCard>

        <p className="mt-5 text-center text-xs leading-5 text-foreground-tertiary">
          Authentication establishes identity only.
          Authorization remains enforced by the ProofMesh
          control plane.
        </p>
      </div>
    </main>
  );
}