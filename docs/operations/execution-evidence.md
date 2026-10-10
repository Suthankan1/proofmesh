# Gateway execution evidence

Gateway migration 0002 adds evidence to durable single-use grant claims. Apply migrations with the existing one-shot runner before starting a gateway. No startup or readiness migrations are introduced.

New claims begin `ADMITTED`. Before invoking the protected tool, the gateway must persist `STARTED` against the exact tenant/agent/action/decision/tool/operation/payload binding. If this write fails, the tool is not called and the grant remains spent. A received successful HTTP result becomes `RESULT_RECEIVED`, storing only SHA-256 of the response bytes and their length. The hash identifies the observed bytes; it does not authenticate a tool or prove the business effect occurred.

Any executor error becomes `UNKNOWN`: timeouts, transport errors and unsuccessful responses cannot prove absence of remote side effects. Crashes or failed terminal evidence writes leave `STARTED`, which also means outcome unknown. `ADMITTED` means admission recorded without persisted invocation intent. Rows from before migration are `LEGACY_UNKNOWN`; their execution outcomes cannot be reconstructed from admission alone.

Terminal recording has a bounded two-second context independent of a caller disconnect. If recording fails, no successful response is returned and the durable grant remains spent. Never automatically retry an unknown outcome. Reconcile with the protected tool using its own business idempotency/audit mechanisms. Exactly-once remote effects across network failures are not promised.

Database constraints enforce evidence shape and forward-only transitions. A row trigger prevents changing original replay bindings, rewriting terminal evidence, or deleting claims. Database owners can bypass ordinary constraints and TRUNCATE; use least-privilege application credentials. No retention deletion or source-history cleanup is implemented. No JWT, request/response body or raw internal error is persisted in these evidence columns. Keep database access tenant-scoped when building later operator/export adapters.
