import "server-only";

function requireEnv(name: string): string {
  const value = process.env[name];

  if (!value) {
    throw new Error(
      `Missing required server environment variable: ${name}`,
    );
  }

  return value;
}

function requirePort(name: string): number {
  const rawValue = requireEnv(name);
  const port = Number(rawValue);

  if (
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) {
    throw new Error(
      `Invalid port in server environment variable: ${name}`,
    );
  }

  return port;
}

export const serverEnv = {
  betterAuth: {
    url: requireEnv("BETTER_AUTH_URL"),
    secret: requireEnv("BETTER_AUTH_SECRET"),
  },

  authDatabase: {
    host: requireEnv("PROOFMESH_AUTH_DB_HOST"),
    port: requirePort("PROOFMESH_AUTH_DB_PORT"),
    database: requireEnv("PROOFMESH_AUTH_DB_NAME"),
    user: requireEnv("PROOFMESH_AUTH_DB_USER"),
    password: requireEnv("PROOFMESH_AUTH_DB_PASSWORD"),
  },

  keycloak: {
    issuer: requireEnv("KEYCLOAK_ISSUER"),
    clientId: requireEnv("KEYCLOAK_WEB_CLIENT_ID"),
    clientSecret: requireEnv("KEYCLOAK_WEB_CLIENT_SECRET"),
  },

  controlPlane: {
    baseUrl: requireEnv("CONTROL_PLANE_BASE_URL"),
  },
} as const;