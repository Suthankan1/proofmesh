package postgres_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	postgres "github.com/Suthankan1/proofmesh/apps/gateway/internal/executionauthority/postgres"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

var (
	testConnStr string
)

func TestMain(m *testing.M) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:18.6-alpine",
		tcpostgres.WithDatabase("proofmesh_test"),
		tcpostgres.WithUsername("postgres"),
		tcpostgres.WithPassword("postgres"),
		tcpostgres.BasicWaitStrategies(),
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to start testcontainer: %v\n", err)
		os.Exit(1)
	}

	connStr, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to get connection string: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}
	testConnStr = connStr

	// Apply migration
	migrationSQL, err := readMigrationSQL()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to read migration SQL: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}

	setupPool, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to connect to test postgres: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}

	if _, err := setupPool.Exec(ctx, migrationSQL); err != nil {
		fmt.Fprintf(os.Stderr, "failed to apply migration: %v\n", err)
		setupPool.Close()
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}
	setupPool.Close()

	code := m.Run()

	termCtx, termCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer termCancel()
	_ = pgContainer.Terminate(termCtx)

	os.Exit(code)
}

func readMigrationSQL() (string, error) {
	candidates := []string{
		"../../../migrations/0001_create_execution_grant_claims.sql",
		"../../migrations/0001_create_execution_grant_claims.sql",
		"../migrations/0001_create_execution_grant_claims.sql",
		"migrations/0001_create_execution_grant_claims.sql",
	}
	for _, c := range candidates {
		if data, err := os.ReadFile(c); err == nil {
			return string(data), nil
		}
	}
	return "", fmt.Errorf("could not locate 0001_create_execution_grant_claims.sql in candidate paths")
}

func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		t.Fatalf("failed to create pgx pool: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func truncateClaims(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := pool.Exec(ctx, "TRUNCATE TABLE execution_grant_claims;"); err != nil {
		t.Fatalf("failed to truncate execution_grant_claims: %v", err)
	}
}

// --- Fakes for PEP Enforcer integration ---

type fakeVerifier struct {
	mu          sync.Mutex
	returnGrant executiongrant.VerifiedExecutionGrant
	returnErr   error
}

func (f *fakeVerifier) Verify(_ context.Context, _ string) (executiongrant.VerifiedExecutionGrant, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.returnErr != nil {
		return executiongrant.VerifiedExecutionGrant{}, f.returnErr
	}
	return f.returnGrant, nil
}

type recordingExecutor struct {
	mu          sync.Mutex
	calls       int
	lastCall    pep.BoundToolCall
	returnRes   pep.ToolResult
	returnErr   error
	capturedRaw []byte
}

func (r *recordingExecutor) Execute(_ context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls++
	r.lastCall = call
	r.capturedRaw = bytes.Clone(call.Payload())
	if r.returnErr != nil {
		return pep.ToolResult{}, r.returnErr
	}
	return r.returnRes, nil
}

type fixedClock struct {
	now time.Time
}

func (c *fixedClock) Now() time.Time {
	return c.now
}

func newValidFixtures() (
	executiongrant.VerifiedExecutionGrant,
	executionattempt.ExecutionAttempt,
	[]byte, // canonical payload
	string, // payload hash
) {
	orgID := uuid.New()
	agentID := uuid.New()
	actionID := uuid.New()
	decisionID := uuid.New()
	grantID := uuid.New()
	toolName := "ledger"
	opName := "post_entry"

	rawPayload := []byte(`{"account":"acc_987","amount":4200}`)
	canonicalPayload, computedHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		panic(err)
	}

	exp := time.Now().Add(10 * time.Minute).UTC().Truncate(time.Microsecond)

	grant := executiongrant.VerifiedExecutionGrant{
		GrantID:              grantID,
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        opName,
		PayloadHash:          computedHash,
		Issuer:               "https://controlplane.proofmesh.internal",
		Audience:             "proofmesh-gateway",
		IssuedAt:             exp.Add(-30 * time.Second),
		ExpiresAt:            exp,
	}

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              bytes.Clone(rawPayload),
	}

	return grant, attempt, canonicalPayload, computedHash
}

// --- Unit & Constructor Tests ---

func TestNewAuthority_Validation(t *testing.T) {
	t.Parallel()

	auth, err := postgres.NewAuthority(nil)
	if !errors.Is(err, postgres.ErrNilPool) {
		t.Fatalf("expected ErrNilPool on nil pool, got %v", err)
	}
	if auth != nil {
		t.Fatal("expected nil Authority on error")
	}

	pool := newTestPool(t)
	auth, err = postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("unexpected error constructing Authority: %v", err)
	}
	if auth == nil {
		t.Fatal("expected non-nil Authority")
	}
}

func TestAuthority_Claim_DirectValidation(t *testing.T) {
	t.Parallel()

	ctx := context.Background()

	// Nil receiver check
	var nilAuth *postgres.Authority
	res, err := nilAuth.Claim(ctx, pep.BoundToolCall{})
	if !errors.Is(err, postgres.ErrNilPool) {
		t.Fatalf("expected ErrNilPool on nil receiver, got %v", err)
	}
	if res != pep.ClaimResultUnknown {
		t.Fatalf("expected ClaimResultUnknown, got %v", res)
	}

	// Uninitialized authority (nil pool)
	uninit := &postgres.Authority{}
	res, err = uninit.Claim(ctx, pep.BoundToolCall{})
	if !errors.Is(err, postgres.ErrNilPool) {
		t.Fatalf("expected ErrNilPool on uninitialized authority, got %v", err)
	}
	if res != pep.ClaimResultUnknown {
		t.Fatalf("expected ClaimResultUnknown, got %v", res)
	}

	// Zero BoundToolCall validation
	pool := newTestPool(t)
	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	res, err = auth.Claim(ctx, pep.BoundToolCall{})
	if !errors.Is(err, postgres.ErrInvalidClaim) {
		t.Fatalf("expected ErrInvalidClaim on zero BoundToolCall, got %v", err)
	}
	if res != pep.ClaimResultUnknown {
		t.Fatalf("expected ClaimResultUnknown, got %v", res)
	}
}

// --- Positive Durable Claim and Replay Tests ---

func TestAuthority_Claim_PositiveAndReplay(t *testing.T) {
	pool := newTestPool(t)
	truncateClaims(t, pool)

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	grant, attempt, canonicalPayload, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{
		returnRes: pep.ToolResult{Payload: []byte(`{"status":"success"}`)},
	}

	enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	// First execution -> ClaimAcquired -> tool executed
	res1, err := enforcer.Execute(context.Background(), "token", attempt)
	if err != nil {
		t.Fatalf("first execution failed: %v", err)
	}
	if !bytes.Equal(res1.Payload, []byte(`{"status":"success"}`)) {
		t.Fatalf("unexpected result: %s", res1.Payload)
	}
	if executor.calls != 1 {
		t.Fatalf("expected 1 executor call, got %d", executor.calls)
	}
	if !bytes.Equal(executor.capturedRaw, canonicalPayload) {
		t.Fatalf("executor did not receive canonical payload: got %s, want %s", executor.capturedRaw, canonicalPayload)
	}

	// Second execution with exact same grant -> ClaimReplay -> ErrExecutionReplay
	res2, err := enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrExecutionReplay) {
		t.Fatalf("expected ErrExecutionReplay, got %v", err)
	}
	if len(res2.Payload) != 0 {
		t.Fatalf("expected empty payload on replay, got %s", res2.Payload)
	}
	// Invariant: Executor must NOT be called a second time
	if executor.calls != 1 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called %d times; expected exactly 1", executor.calls)
	}
}

// --- Persisted Metadata Verification ---

func TestAuthority_Claim_PersistedMetadata(t *testing.T) {
	pool := newTestPool(t)
	truncateClaims(t, pool)

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{}`)}}

	enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	startTime := time.Now().UTC().Add(-2 * time.Second)
	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if err != nil {
		t.Fatalf("execute failed: %v", err)
	}
	endTime := time.Now().UTC().Add(2 * time.Second)

	// Query PostgreSQL directly for the persisted row
	var (
		storedGrantID    uuid.UUID
		storedOrgID      uuid.UUID
		storedAgentID    uuid.UUID
		storedActionID   uuid.UUID
		storedDecisionID uuid.UUID
		storedToolName   string
		storedOpName     string
		storedHash       string
		storedExpiresAt  time.Time
		storedClaimedAt  time.Time
	)

	query := `
	SELECT grant_id, organization_id, agent_id, governed_action_id, governance_decision_id,
	       tool_name, operation_name, payload_hash, expires_at, claimed_at
	FROM execution_grant_claims
	WHERE grant_id = $1
	`
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = pool.QueryRow(ctx, query, grant.GrantID).Scan(
		&storedGrantID,
		&storedOrgID,
		&storedAgentID,
		&storedActionID,
		&storedDecisionID,
		&storedToolName,
		&storedOpName,
		&storedHash,
		&storedExpiresAt,
		&storedClaimedAt,
	)
	if err != nil {
		t.Fatalf("failed to query claimed row: %v", err)
	}

	if storedGrantID != grant.GrantID {
		t.Errorf("grant_id mismatch: got %s, want %s", storedGrantID, grant.GrantID)
	}
	if storedOrgID != grant.OrganizationID {
		t.Errorf("organization_id mismatch: got %s, want %s", storedOrgID, grant.OrganizationID)
	}
	if storedAgentID != grant.AgentID {
		t.Errorf("agent_id mismatch: got %s, want %s", storedAgentID, grant.AgentID)
	}
	if storedActionID != grant.GovernedActionID {
		t.Errorf("governed_action_id mismatch: got %s, want %s", storedActionID, grant.GovernedActionID)
	}
	if storedDecisionID != grant.GovernanceDecisionID {
		t.Errorf("governance_decision_id mismatch: got %s, want %s", storedDecisionID, grant.GovernanceDecisionID)
	}
	if storedToolName != grant.ToolName {
		t.Errorf("tool_name mismatch: got %s, want %s", storedToolName, grant.ToolName)
	}
	if storedOpName != grant.OperationName {
		t.Errorf("operation_name mismatch: got %s, want %s", storedOpName, grant.OperationName)
	}
	if storedHash != grant.PayloadHash {
		t.Errorf("payload_hash mismatch: got %s, want %s", storedHash, grant.PayloadHash)
	}
	wantExpiresAt := grant.ExpiresAt.Truncate(time.Microsecond)
	if !storedExpiresAt.Equal(wantExpiresAt) {
		t.Errorf("expires_at mismatch: got %s, want %s", storedExpiresAt, wantExpiresAt)
	}
	if storedClaimedAt.IsZero() {
		t.Fatal("claimed_at must not be zero")
	}
	if storedClaimedAt.Before(startTime) || storedClaimedAt.After(endTime) {
		t.Errorf("claimed_at %v not between %v and %v", storedClaimedAt, startTime, endTime)
	}
}

// --- Schema Inspection / Payload Non-Persistence ---

func TestAuthority_Schema_PayloadNonPersistence(t *testing.T) {
	pool := newTestPool(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	rows, err := pool.Query(ctx, `
		SELECT column_name
		FROM information_schema.columns
		WHERE table_name = 'execution_grant_claims'
	`)
	if err != nil {
		t.Fatalf("failed to query information_schema: %v", err)
	}
	defer rows.Close()

	forbiddenColumns := map[string]bool{
		"payload":           true,
		"canonical_payload": true,
		"compact_token":     true,
		"jwt":               true,
		"signature":         true,
		"jwk":               true,
		"token":             true,
	}

	var foundColumns []string
	for rows.Next() {
		var col string
		if err := rows.Scan(&col); err != nil {
			t.Fatalf("scan failed: %v", err)
		}
		foundColumns = append(foundColumns, col)
		if forbiddenColumns[col] {
			t.Fatalf("FORBIDDEN SENSITIVE COLUMN FOUND in execution_grant_claims: %q", col)
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("rows iteration failed: %v", err)
	}
	if len(foundColumns) == 0 {
		t.Fatal("no columns found for table execution_grant_claims; migration may not have run")
	}
}

// --- Durable Across Independent Authority / Pools ---

func TestAuthority_Claim_DurableAcrossInstances(t *testing.T) {
	poolA := newTestPool(t)
	poolB := newTestPool(t)
	truncateClaims(t, poolA)

	authA, err := postgres.NewAuthority(poolA)
	if err != nil {
		t.Fatalf("failed to create authority A: %v", err)
	}
	authB, err := postgres.NewAuthority(poolB)
	if err != nil {
		t.Fatalf("failed to create authority B: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executorA := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{"a":true}`)}}
	executorB := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{"b":true}`)}}

	enforcerA, err := pep.NewEnforcer(verifier, authA, executorA, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer A: %v", err)
	}
	enforcerB, err := pep.NewEnforcer(verifier, authB, executorB, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer B: %v", err)
	}

	// First execution via Authority A / Pool A
	resA, err := enforcerA.Execute(context.Background(), "token", attempt)
	if err != nil {
		t.Fatalf("execution A failed: %v", err)
	}
	if !bytes.Equal(resA.Payload, []byte(`{"a":true}`)) {
		t.Fatalf("unexpected result payload from A: %s", resA.Payload)
	}
	if executorA.calls != 1 {
		t.Fatalf("expected executor A calls == 1, got %d", executorA.calls)
	}

	// Second execution via Authority B / Pool B with same GrantID
	_, err = enforcerB.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrExecutionReplay) {
		t.Fatalf("expected ErrExecutionReplay on enforcer B, got %v", err)
	}
	if executorB.calls != 0 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor B called on replay: %d", executorB.calls)
	}
}

// --- Durable Across Pool Recreation ---

func TestAuthority_Claim_DurableAcrossPoolRecreation(t *testing.T) {
	ctx := context.Background()

	pool1, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		t.Fatalf("failed to create pool 1: %v", err)
	}
	truncateClaims(t, pool1)

	auth1, err := postgres.NewAuthority(pool1)
	if err != nil {
		t.Fatalf("failed to create authority 1: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor1 := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{}`)}}

	enforcer1, err := pep.NewEnforcer(verifier, auth1, executor1, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer 1: %v", err)
	}

	// Claim through pool1
	_, err = enforcer1.Execute(ctx, "token", attempt)
	if err != nil {
		t.Fatalf("enforcer 1 execute failed: %v", err)
	}
	if executor1.calls != 1 {
		t.Fatalf("expected executor1 calls == 1, got %d", executor1.calls)
	}

	// Close pool1 completely
	pool1.Close()

	// Open a new pool2 to the same database
	pool2, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		t.Fatalf("failed to create pool 2: %v", err)
	}
	defer pool2.Close()

	auth2, err := postgres.NewAuthority(pool2)
	if err != nil {
		t.Fatalf("failed to create authority 2: %v", err)
	}
	executor2 := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{}`)}}
	enforcer2, err := pep.NewEnforcer(verifier, auth2, executor2, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer 2: %v", err)
	}

	// Attempt same GrantID through pool2
	_, err = enforcer2.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrExecutionReplay) {
		t.Fatalf("expected ErrExecutionReplay on recreated pool, got %v", err)
	}
	if executor2.calls != 0 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor 2 called on replay: %d", executor2.calls)
	}
}

// --- Cross-Pool Concurrency Test ---

func TestAuthority_Claim_CrossPoolConcurrency(t *testing.T) {
	poolA := newTestPool(t)
	poolB := newTestPool(t)
	truncateClaims(t, poolA)

	authA, err := postgres.NewAuthority(poolA)
	if err != nil {
		t.Fatalf("failed to create auth A: %v", err)
	}
	authB, err := postgres.NewAuthority(poolB)
	if err != nil {
		t.Fatalf("failed to create auth B: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}

	executor := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{"status":"ok"}`)}}

	enforcerA, err := pep.NewEnforcer(verifier, authA, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer A: %v", err)
	}
	enforcerB, err := pep.NewEnforcer(verifier, authB, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer B: %v", err)
	}

	const concurrency = 30
	var wg sync.WaitGroup
	wg.Add(concurrency)

	startCh := make(chan struct{})
	results := make([]error, concurrency)

	for i := 0; i < concurrency; i++ {
		idx := i
		// Alternate between Enforcer A and Enforcer B
		enf := enforcerA
		if idx%2 == 1 {
			enf = enforcerB
		}

		go func() {
			defer wg.Done()
			<-startCh
			_, err := enf.Execute(context.Background(), "token", attempt)
			results[idx] = err
		}()
	}

	// Release all goroutines simultaneously
	close(startCh)
	wg.Wait()

	var acquiredCount int
	var replayCount int
	var otherErrors []error

	for _, err := range results {
		if err == nil {
			acquiredCount++
		} else if errors.Is(err, pep.ErrExecutionReplay) {
			replayCount++
		} else {
			otherErrors = append(otherErrors, err)
		}
	}

	if len(otherErrors) > 0 {
		t.Fatalf("unexpected operational errors during concurrent claims: %v", otherErrors)
	}

	// CRITICAL INVARIANT: Exactly 1 acquired, exactly concurrency-1 replayed
	if acquiredCount != 1 {
		t.Fatalf("CRITICAL CONCURRENCY INVARIANT VIOLATION: expected exactly 1 acquired, got %d", acquiredCount)
	}
	if replayCount != concurrency-1 {
		t.Fatalf("expected exactly %d replayed, got %d", concurrency-1, replayCount)
	}
	if executor.calls != 1 {
		t.Fatalf("CRITICAL INVARIANT VIOLATION: executor called %d times; expected exactly 1", executor.calls)
	}
}

// --- Different GrantIDs Concurrency ---

func TestAuthority_Claim_DifferentGrantIDsConcurrency(t *testing.T) {
	pool := newTestPool(t)
	truncateClaims(t, pool)

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create auth: %v", err)
	}

	const distinctGrants = 10
	var wg sync.WaitGroup
	wg.Add(distinctGrants)

	startCh := make(chan struct{})
	results := make([]error, distinctGrants)

	for i := 0; i < distinctGrants; i++ {
		idx := i
		grant, attempt, _, _ := newValidFixtures()
		clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
		verifier := &fakeVerifier{returnGrant: grant}
		executor := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{}`)}}
		enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
		if err != nil {
			t.Fatalf("failed to create enforcer: %v", err)
		}

		go func() {
			defer wg.Done()
			<-startCh
			_, err := enforcer.Execute(context.Background(), "token", attempt)
			results[idx] = err
		}()
	}

	close(startCh)
	wg.Wait()

	for i, err := range results {
		if err != nil {
			t.Fatalf("distinct grant #%d failed unexpectedly: %v", i, err)
		}
	}
}

// --- Executor Failure Consumes Claim ---

func TestAuthority_Claim_ExecutorFailureRemainsConsumed(t *testing.T) {
	pool := newTestPool(t)
	truncateClaims(t, pool)

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{
		returnErr: errors.New("simulated tool failure after side effect"),
	}

	enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	// First execution fails at executor stage
	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrToolExecutionFailed) {
		t.Fatalf("expected ErrToolExecutionFailed, got %v", err)
	}
	if executor.calls != 1 {
		t.Fatalf("expected executor calls == 1, got %d", executor.calls)
	}

	// Retry with exact same GrantID must be rejected as REPLAY
	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrExecutionReplay) {
		t.Fatalf("expected ErrExecutionReplay on retry after executor failure, got %v", err)
	}
	// Executor must NOT have been called a second time
	if executor.calls != 1 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called %d times; failed execution must remain replay-blocked", executor.calls)
	}
}

// --- Database Failure / Closed Pool ---

func TestAuthority_Claim_DatabaseFailure(t *testing.T) {
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	// Close pool immediately to simulate database operational failure
	pool.Close()

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	// Through PEP enforcer: must return ErrExecutionAuthorityFailed
	_, err = enforcer.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrExecutionAuthorityFailed) {
		t.Fatalf("expected ErrExecutionAuthorityFailed on DB failure, got %v", err)
	}
	if executor.calls != 0 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called on DB failure: %d", executor.calls)
	}

	// Direct call to authority: must return ClaimResultUnknown and ErrClaimFailed
	// (Note: we test with a zero BoundToolCall or a mock bound call. Since BoundToolCall fields
	// are private to package pep, we test zero BoundToolCall validation directly.)
	res, err := auth.Claim(ctx, pep.BoundToolCall{})
	if res != pep.ClaimResultUnknown {
		t.Fatalf("expected ClaimResultUnknown, got %v", res)
	}
	if !errors.Is(err, postgres.ErrInvalidClaim) {
		t.Fatalf("expected ErrInvalidClaim, got %v", err)
	}
}

// --- Context Cancellation ---

func TestAuthority_Claim_ContextCancellation(t *testing.T) {
	pool := newTestPool(t)
	truncateClaims(t, pool)

	auth, err := postgres.NewAuthority(pool)
	if err != nil {
		t.Fatalf("failed to create authority: %v", err)
	}

	grant, attempt, _, _ := newValidFixtures()
	clock := &fixedClock{now: grant.ExpiresAt.Add(-10 * time.Second)}
	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, auth, executor, clock)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	// Pre-canceled context
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err = enforcer.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrExecutionCanceled) {
		t.Fatalf("expected ErrExecutionCanceled, got %v", err)
	}
	if executor.calls != 0 {
		t.Fatalf("executor should not have been called, got %d", executor.calls)
	}

	// Assert no row was created in database
	var count int
	err = pool.QueryRow(context.Background(), "SELECT COUNT(*) FROM execution_grant_claims WHERE grant_id = $1", grant.GrantID).Scan(&count)
	if err != nil {
		t.Fatalf("count query failed: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected 0 claims inserted on canceled context, got %d", count)
	}
}
