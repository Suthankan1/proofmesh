"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { authClient } from "@/lib/auth/auth-client";

export function SignOutButton() {
  const [isPending, setIsPending] =
    useState(false);

  async function handleSignOut() {
    setIsPending(true);

    try {
      await authClient.signOut({
        callbackURL: "/sign-in",
      });
    } finally {
      setIsPending(false);
    }
  }

  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      disabled={isPending}
      onClick={handleSignOut}
    >
      {isPending
        ? "Signing out…"
        : "Sign out"}
    </Button>
  );
}