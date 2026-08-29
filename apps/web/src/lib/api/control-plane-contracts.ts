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