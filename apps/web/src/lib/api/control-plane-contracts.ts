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
