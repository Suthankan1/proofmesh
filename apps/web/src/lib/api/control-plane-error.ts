export type ControlPlaneErrorKind =
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "UNAVAILABLE"
  | "UPSTREAM_ERROR"
  | "CONTRACT_VIOLATION";

type ControlPlaneErrorOptions = {
  kind: ControlPlaneErrorKind;
  status: number;
  path: string;
};

export class ControlPlaneError extends Error {
  readonly kind: ControlPlaneErrorKind;
  readonly status: number;
  readonly path: string;

  constructor(
    message: string,
    {
      kind,
      status,
      path,
    }: ControlPlaneErrorOptions,
  ) {
    super(message);

    this.name = "ControlPlaneError";
    this.kind = kind;
    this.status = status;
    this.path = path;
  }
}