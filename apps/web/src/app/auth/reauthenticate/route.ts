import { headers } from "next/headers";
import { NextResponse } from "next/server";

import { auth } from "@/lib/auth/auth";

export async function GET(
  request: Request,
) {
  const requestHeaders =
    await headers();

  await auth.api.signOut({
    headers: requestHeaders,
  });

  return NextResponse.redirect(
    new URL(
      "/sign-in?reason=session-expired",
      request.url,
    ),
  );
}