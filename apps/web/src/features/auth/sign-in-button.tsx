"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { authClient } from "@/lib/auth/auth-client";

export function SignInButton() {
  const [errorMessage, setErrorMessage] =
    useState<string | null>(null);

  const [isPending, setIsPending] =
    useState(false);

  async function handleSignIn() {
    setErrorMessage(null);
    setIsPending(true);

    const { error } =
      await authClient.signIn.social({
        provider: "keycloak",
        callbackURL: "/dashboard",
        errorCallbackURL: "/sign-in?error=oauth",
      });

    if (error) {
      setErrorMessage(
        "Authentication could not be started. Please try again.",
      );

      setIsPending(false);
    }
  }

  return (
    <div>
      <Button
        type="button"
        className="w-full"
        disabled={isPending}
        onClick={handleSignIn}
      >
        {isPending
          ? "Redirecting…"
          : "Continue with Keycloak"}
      </Button>

      {errorMessage ? (
        <p
          role="alert"
          className="mt-3 text-sm text-danger-foreground"
        >
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}