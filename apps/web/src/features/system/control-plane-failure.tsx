import type {
  ControlPlaneErrorKind,
} from "@/lib/api/control-plane-error";

import { SignOutButton } from "@/features/auth/sign-out-button";
import { SurfaceCard } from "@/components/ui/surface-card";

type ControlPlaneFailureProps = {
  kind: ControlPlaneErrorKind;
};

type FailureContent = {
  eyebrow: string;
  title: string;
  description: string;
};

const failureContent: Record<
  ControlPlaneErrorKind,
  FailureContent
> = {
  UNAUTHORIZED: {
    eyebrow: "Authentication required",
    title: "Your operator identity could not be verified.",
    description:
      "ProofMesh could not establish a trusted operator identity with the control plane. Sign in again to continue.",
  },

  FORBIDDEN: {
    eyebrow: "Access denied",
    title: "You do not have access to this workspace.",
    description:
      "Your identity is valid, but the ProofMesh control plane did not authorize access to this resource.",
  },

  UNAVAILABLE: {
    eyebrow: "Service unavailable",
    title: "The ProofMesh control plane is unavailable.",
    description:
      "The operator console cannot establish authoritative runtime context right now. Protected functionality remains unavailable until the control plane recovers.",
  },

  UPSTREAM_ERROR: {
    eyebrow: "Control plane error",
    title: "The control plane could not complete this request.",
    description:
      "ProofMesh received an unexpected upstream failure. Protected functionality remains unavailable until the issue is resolved.",
  },

  CONTRACT_VIOLATION: {
    eyebrow: "Integration failure",
    title: "The control plane returned an invalid response.",
    description:
      "The operator console could not safely interpret the control-plane response. Access has been stopped rather than continuing with untrusted data.",
  },
};

export function ControlPlaneFailure({
  kind,
}: ControlPlaneFailureProps) {
  const content = failureContent[kind];

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas px-6 py-12">
      <div className="w-full max-w-lg">
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
            {content.eyebrow}
          </p>

          <h1 className="mt-3 text-2xl font-semibold tracking-tight text-foreground">
            {content.title}
          </h1>

          <p className="mt-3 text-sm leading-6 text-foreground-secondary">
            {content.description}
          </p>

          <div className="mt-7 flex flex-wrap items-center gap-3">
            <a
              href="/dashboard"
              className="inline-flex min-h-9 items-center justify-center rounded-md border border-border px-3 py-2 text-sm font-semibold text-foreground transition hover:bg-surface-subtle"
            >
              Try again
            </a>

            <SignOutButton />
          </div>
        </SurfaceCard>

        <p className="mt-5 text-center text-xs leading-5 text-foreground-tertiary">
          ProofMesh fails closed when authoritative
          control-plane context cannot be established.
        </p>
      </div>
    </main>
  );
}