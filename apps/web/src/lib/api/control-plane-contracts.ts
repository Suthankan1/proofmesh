import { z } from "zod";

const javaUuidSchema = z.string().regex(
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
  "Invalid UUID",
);

export const identityResponseSchema = z.object({
  subject: z.string().min(1),
  authorities: z.array(z.string()),
});

export type IdentityResponse =
  z.infer<typeof identityResponseSchema>;

export const organizationContextSchema = z.object({
  userId: javaUuidSchema,
  organizationId: javaUuidSchema,
  organizationSlug: z.string().min(1),
  oidcSubject: z.string().min(1),
});

export type OrganizationContext =
  z.infer<typeof organizationContextSchema>;
export const approvalResponseSchema = z.object({
  id: javaUuidSchema, organizationId: javaUuidSchema, agentId: javaUuidSchema,
  governedActionId: javaUuidSchema, governanceDecisionId: javaUuidSchema,
  toolName: z.string(), operationName: z.string(), payloadHash: z.string().regex(/^[0-9a-f]{64}$/),
  status: z.enum(["PENDING", "APPROVED", "REJECTED", "EXPIRED"]),
  requestedAt: z.string(), expiresAt: z.string(), validAuthorization: z.boolean(),
  requestPayloadJson: z.string().nullable(),
});
export const approvalListSchema = z.array(approvalResponseSchema);

export const consoleResponseSchema = z.object({
  organization: z.object({ id: javaUuidSchema, slug: z.string(), name: z.string(), status: z.enum(["ACTIVE", "SUSPENDED"]) }),
  pendingApprovals: z.number().int().nonnegative(),
  agents: z.array(z.object({ id: javaUuidSchema, name: z.string(), status: z.enum(["ACTIVE", "DISABLED"]) })).max(100),
  policies: z.array(z.object({ id: javaUuidSchema, name: z.string(), version: z.number().int(), state: z.enum(["DRAFT", "PUBLISHED"]), definition: z.record(z.string(), z.unknown()), definitionHash: z.string().nullable() })).max(100),
  actions: z.array(z.object({ id: javaUuidSchema, agentId: javaUuidSchema, toolName: z.string(), operationName: z.string(), payloadHash: z.string().regex(/^[0-9a-f]{64}$/), createdAt: z.string(), outcome: z.enum(["ALLOW", "DENY", "REQUIRE_APPROVAL"]).nullable(), riskScore: z.number().int().min(0).max(100).nullable() })).max(100),
});
export type ConsoleSnapshot = z.infer<typeof consoleResponseSchema>;
export const evidenceResponseSchema = z.object({
  schemaVersion: z.literal(1), organizationId: javaUuidSchema, exportedAt: z.string(),
  action: z.object({ id: javaUuidSchema, organization_id: javaUuidSchema }).passthrough(),
  executions: z.array(z.record(z.string(), z.unknown())),
}).passthrough();
