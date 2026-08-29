import "server-only";
import { betterAuth } from "better-auth";
import {
  genericOAuth,
  keycloak,
} from "better-auth/plugins";

import { authDatabase } from "@/lib/auth/database";
import { serverEnv } from "@/lib/config/server-env";

export const auth = betterAuth({
  appName: "ProofMesh",

  baseURL: serverEnv.betterAuth.url,
  secret: serverEnv.betterAuth.secret,

  database: authDatabase,

  account: {
    encryptOAuthTokens: true,
  },

  plugins: [
    genericOAuth({
      config: [
        keycloak({
          clientId: serverEnv.keycloak.clientId,
          clientSecret: serverEnv.keycloak.clientSecret,
          issuer: serverEnv.keycloak.issuer,

          scopes: [
            "openid",
            "profile",
            "email",
          ],

          pkce: true,
        }),
      ],
    }),
  ],
});