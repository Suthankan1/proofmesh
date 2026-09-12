package dbmigrate_test

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/dbmigrate"
)

var testConnStr string

func TestMain(m *testing.M) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:18.6-alpine",
		tcpostgres.WithDatabase("proofmesh_migrate_test"),
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

	code := m.Run()

	_ = pgContainer.Terminate(context.Background())
	os.Exit(code)
}

func TestNew_InvalidURL(t *testing.T) {
	for _, raw := range []string{"", "   ", "\t"} {
		m, err := dbmigrate.New(raw)
		if err == nil {
			t.Fatalf("expected error for empty URL %q, got nil", raw)
		}
		if !errors.Is(err, dbmigrate.ErrInvalidConfig) {
			t.Fatalf("expected ErrInvalidConfig, got %v", err)
		}
		if m != nil {
			t.Fatal("expected nil Migrator on error")
		}
	}
}

func TestPing_SanitizedFailure(t *testing.T) {
	const canarySecret = "CANARY_SECRET_PASSWORD_XYZ"
	canaryURL := fmt.Sprintf("postgres://user:%s@127.0.0.1:59999/canary_db?sslmode=disable", canarySecret)

	m, err := dbmigrate.New(canaryURL)
	if err != nil {
		t.Fatalf("unexpected error initializing migrator: %v", err)
	}
	defer m.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	err = m.Ping(ctx)
	if err == nil {
		t.Fatal("expected ping error on unreachable port, got nil")
	}
	if !errors.Is(err, dbmigrate.ErrConnectionFailed) {
		t.Fatalf("expected ErrConnectionFailed, got %v", err)
	}

	errStr := err.Error()
	if strings.Contains(errStr, canarySecret) {
		t.Fatalf("canary secret leaked in error: %s", errStr)
	}
	if strings.Contains(errStr, "127.0.0.1:59999") {
		t.Fatalf("database host leaked in error: %s", errStr)
	}
}

func TestContextCancellation_Sanitized(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	m, err := dbmigrate.New(testConnStr)
	if err != nil {
		t.Fatalf("failed to create migrator: %v", err)
	}
	defer m.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	err = m.Up(ctx)
	if err == nil {
		t.Fatal("expected error on cancelled context, got nil")
	}
	if !errors.Is(err, dbmigrate.ErrMigrationFailed) {
		t.Fatalf("expected ErrMigrationFailed, got %v", err)
	}
}

func TestIntegration_FreshDBAndSchemaVerification(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	db, err := sql.Open("pgx", testConnStr)
	if err != nil {
		t.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	// Ensure clean database state
	_, err = db.Exec("DROP TABLE IF EXISTS execution_grant_claims CASCADE; DROP TABLE IF EXISTS goose_db_version CASCADE;")
	if err != nil {
		t.Fatalf("failed to reset tables: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	m, err := dbmigrate.New(testConnStr)
	if err != nil {
		t.Fatalf("failed to create migrator: %v", err)
	}
	defer m.Close()

	if err := m.Ping(ctx); err != nil {
		t.Fatalf("ping failed: %v", err)
	}

	if err := m.Up(ctx); err != nil {
		t.Fatalf("migration Up failed: %v", err)
	}

	// 1. Verify execution_grant_claims table exists
	var tableCount int
	err = db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'execution_grant_claims'").Scan(&tableCount)
	if err != nil || tableCount != 1 {
		t.Fatalf("expected table execution_grant_claims to exist, count=%d, err=%v", tableCount, err)
	}

	// 2. Verify all required columns exist
	requiredColumns := []string{
		"grant_id",
		"organization_id",
		"agent_id",
		"governed_action_id",
		"governance_decision_id",
		"tool_name",
		"operation_name",
		"payload_hash",
		"expires_at",
		"claimed_at",
	}
	for _, col := range requiredColumns {
		var colCount int
		err = db.QueryRowContext(ctx,
			"SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'execution_grant_claims' AND column_name = $1",
			col,
		).Scan(&colCount)
		if err != nil || colCount != 1 {
			t.Errorf("expected column %q to exist in execution_grant_claims, count=%d, err=%v", col, colCount, err)
		}
	}

	// 3. Verify primary key on grant_id
	var pkCount int
	err = db.QueryRowContext(ctx, `
		SELECT COUNT(*)
		FROM information_schema.table_constraints tc
		JOIN information_schema.key_column_usage kcu
		  ON tc.constraint_name = kcu.constraint_name
		WHERE tc.table_name = 'execution_grant_claims'
		  AND tc.constraint_type = 'PRIMARY KEY'
		  AND kcu.column_name = 'grant_id'
	`).Scan(&pkCount)
	if err != nil || pkCount != 1 {
		t.Fatalf("expected primary key on grant_id, count=%d, err=%v", pkCount, err)
	}

	// 4. Verify goose_db_version table exists and record for version 1 is applied
	var versionCount int
	err = db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM goose_db_version WHERE version_id = 1 AND is_applied = true").Scan(&versionCount)
	if err != nil || versionCount != 1 {
		t.Fatalf("expected goose_db_version to record version 1 applied, count=%d, err=%v", versionCount, err)
	}
}

func TestIntegration_SerialIdempotency(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	db, err := sql.Open("pgx", testConnStr)
	if err != nil {
		t.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	_, err = db.Exec("DROP TABLE IF EXISTS execution_grant_claims CASCADE; DROP TABLE IF EXISTS goose_db_version CASCADE;")
	if err != nil {
		t.Fatalf("failed to reset tables: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	m, err := dbmigrate.New(testConnStr)
	if err != nil {
		t.Fatalf("failed to create migrator: %v", err)
	}
	defer m.Close()

	// First run applies all migrations
	if err := m.Up(ctx); err != nil {
		t.Fatalf("first migration run failed: %v", err)
	}

	// Second run must succeed as a clean no-op
	if err := m.Up(ctx); err != nil {
		t.Fatalf("second migration run failed: %v", err)
	}

	var version1Count int
	err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM goose_db_version WHERE version_id = 1 AND is_applied = true").Scan(&version1Count)
	if err != nil || version1Count != 1 {
		t.Fatalf("expected exactly 1 applied record for version 1 after rerun, count=%d, err=%v", version1Count, err)
	}

	var maxVersion int64
	err = db.QueryRowContext(ctx, "SELECT MAX(version_id) FROM goose_db_version WHERE is_applied = true").Scan(&maxVersion)
	if err != nil || maxVersion != 1 {
		t.Fatalf("expected max version 1 after rerun, max=%d, err=%v", maxVersion, err)
	}
}

func TestIntegration_VersionTableBlockedUntilOuterLock(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	db, err := sql.Open("pgx", testConnStr)
	if err != nil {
		t.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	// 1. Fresh DB
	_, err = db.Exec("DROP TABLE IF EXISTS execution_grant_claims CASCADE; DROP TABLE IF EXISTS goose_db_version CASCADE;")
	if err != nil {
		t.Fatalf("failed to reset tables: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// 2. Open an independent SQL connection
	testConn, err := db.Conn(ctx)
	if err != nil {
		t.Fatalf("failed to get dedicated test connection: %v", err)
	}
	defer testConn.Close()

	// 3. Acquire the SAME ProofMesh migration advisory lock manually on testConn
	locker, err := dbmigrate.NewMigrationLockerForTest()
	if err != nil {
		t.Fatalf("failed to create migration locker: %v", err)
	}

	if err := locker.SessionLock(ctx, testConn); err != nil {
		t.Fatalf("failed to acquire test advisory lock: %v", err)
	}

	// 4. Start Migrator.Up concurrently
	m, err := dbmigrate.New(testConnStr)
	if err != nil {
		t.Fatalf("failed to create migrator: %v", err)
	}
	defer m.Close()

	upErrCh := make(chan error, 1)
	go func() {
		upErrCh <- m.Up(ctx)
	}()

	// 5. Wait long enough to prove it is blocked on the lock
	select {
	case err := <-upErrCh:
		t.Fatalf("Migrator.Up exited prematurely while lock was held, err: %v", err)
	case <-time.After(300 * time.Millisecond):
		// Expected: still blocked
	}

	// 6. While outer test lock is still held, assert tables DO NOT exist
	for _, tableName := range []string{"goose_db_version", "execution_grant_claims"} {
		var exists bool
		err = db.QueryRowContext(ctx,
			"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = $1)",
			tableName,
		).Scan(&exists)
		if err != nil {
			t.Fatalf("failed to query information_schema for %q: %v", tableName, err)
		}
		if exists {
			t.Fatalf("CRITICAL REGRESSION: table %q exists before migration lock was acquired", tableName)
		}
	}

	// 7. Release the held lock
	if err := locker.SessionUnlock(ctx, testConn); err != nil {
		t.Fatalf("failed to release test advisory lock: %v", err)
	}

	// 8. Await Migrator.Up
	select {
	case upErr := <-upErrCh:
		if upErr != nil {
			t.Fatalf("Migrator.Up failed after lock was released: %v", upErr)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for Migrator.Up to complete after lock release")
	}

	// 9 & 10. Assert both version table and domain schema now exist and are correct
	for _, tableName := range []string{"goose_db_version", "execution_grant_claims"} {
		var exists bool
		err = db.QueryRowContext(ctx,
			"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = $1)",
			tableName,
		).Scan(&exists)
		if err != nil {
			t.Fatalf("failed to query information_schema for %q: %v", tableName, err)
		}
		if !exists {
			t.Fatalf("expected table %q to exist after migration, but it was not found", tableName)
		}
	}

	var versionCount int
	err = db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM goose_db_version WHERE version_id = 1 AND is_applied = true").Scan(&versionCount)
	if err != nil || versionCount != 1 {
		t.Fatalf("expected goose_db_version to record version 1 applied, count=%d, err=%v", versionCount, err)
	}
}

func TestIntegration_LockWaitContext_TimeoutAndRecovery(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	db, err := sql.Open("pgx", testConnStr)
	if err != nil {
		t.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	// 1. Fresh DB
	_, err = db.Exec("DROP TABLE IF EXISTS execution_grant_claims CASCADE; DROP TABLE IF EXISTS goose_db_version CASCADE;")
	if err != nil {
		t.Fatalf("failed to reset tables: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// 2. Hold the same advisory lock on an independent session
	testConn, err := db.Conn(ctx)
	if err != nil {
		t.Fatalf("failed to get dedicated test connection: %v", err)
	}
	defer testConn.Close()

	locker, err := dbmigrate.NewMigrationLockerForTest()
	if err != nil {
		t.Fatalf("failed to create migration locker: %v", err)
	}

	if err := locker.SessionLock(ctx, testConn); err != nil {
		t.Fatalf("failed to acquire test advisory lock: %v", err)
	}

	// 3. Call Migrator.Up with a short timeout context
	m, err := dbmigrate.New(testConnStr)
	if err != nil {
		t.Fatalf("failed to create migrator: %v", err)
	}
	defer m.Close()

	shortCtx, shortCancel := context.WithTimeout(ctx, 150*time.Millisecond)
	defer shortCancel()

	err = m.Up(shortCtx)

	// 4. Expect bounded failure with ErrMigrationFailed
	if err == nil {
		t.Fatal("expected migration error on lock acquisition timeout, got nil")
	}
	if !errors.Is(err, dbmigrate.ErrMigrationFailed) {
		t.Fatalf("expected ErrMigrationFailed, got %v", err)
	}

	// 5. Assert no raw DB/lock error text leaks
	errStr := err.Error()
	for _, forbidden := range []string{"advisory", "pg_", "connection", "lock", "timeout", "deadline", "5432"} {
		if strings.Contains(strings.ToLower(errStr), forbidden) {
			t.Fatalf("raw error text leaked in error message %q (contains %q)", errStr, forbidden)
		}
	}

	// 6. Assert before releasing the manually held lock: no goose_db_version, no execution_grant_claims
	for _, tableName := range []string{"goose_db_version", "execution_grant_claims"} {
		var exists bool
		err = db.QueryRowContext(ctx,
			"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = $1)",
			tableName,
		).Scan(&exists)
		if err != nil {
			t.Fatalf("failed to query information_schema for %q: %v", tableName, err)
		}
		if exists {
			t.Fatalf("CRITICAL REGRESSION: table %q exists after timed-out migration lock wait", tableName)
		}
	}

	// 7. Release lock
	if err := locker.SessionUnlock(ctx, testConn); err != nil {
		t.Fatalf("failed to release test advisory lock: %v", err)
	}

	// 8. Run migration with a normal context and prove success
	normalCtx, normalCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer normalCancel()

	if err := m.Up(normalCtx); err != nil {
		t.Fatalf("expected migration to succeed after lock release, got %v", err)
	}

	for _, tableName := range []string{"goose_db_version", "execution_grant_claims"} {
		var exists bool
		err = db.QueryRowContext(normalCtx,
			"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = $1)",
			tableName,
		).Scan(&exists)
		if err != nil {
			t.Fatalf("failed to query information_schema for %q: %v", tableName, err)
		}
		if !exists {
			t.Fatalf("expected table %q to exist after successful migration", tableName)
		}
	}

	var versionCount int
	err = db.QueryRowContext(normalCtx,
		"SELECT COUNT(*) FROM goose_db_version WHERE version_id = 1 AND is_applied = true").Scan(&versionCount)
	if err != nil || versionCount != 1 {
		t.Fatalf("expected goose_db_version to record version 1 applied, count=%d, err=%v", versionCount, err)
	}
}

func TestIntegration_ConcurrentUpCalls(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	db, err := sql.Open("pgx", testConnStr)
	if err != nil {
		t.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	// Reset database to test concurrent fresh initialization
	_, err = db.Exec("DROP TABLE IF EXISTS execution_grant_claims CASCADE; DROP TABLE IF EXISTS goose_db_version CASCADE;")
	if err != nil {
		t.Fatalf("failed to reset tables: %v", err)
	}

	const concurrency = 5
	errs := make([]error, concurrency)
	var wg sync.WaitGroup
	wg.Add(concurrency)

	start := make(chan struct{})

	for i := 0; i < concurrency; i++ {
		go func(idx int) {
			defer wg.Done()
			<-start

			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()

			m, err := dbmigrate.New(testConnStr)
			if err != nil {
				errs[idx] = err
				return
			}
			defer m.Close()

			errs[idx] = m.Up(ctx)
		}(i)
	}

	close(start)
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Errorf("concurrent runner %d returned error: %v", i, err)
		}
	}

	ctx := context.Background()
	var versionCount int
	err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM goose_db_version WHERE version_id = 1 AND is_applied = true").Scan(&versionCount)
	if err != nil || versionCount != 1 {
		t.Fatalf("expected exactly 1 applied version 1 after concurrent runs, count=%d, err=%v", versionCount, err)
	}

	var tableCount int
	err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'execution_grant_claims'").Scan(&tableCount)
	if err != nil || tableCount != 1 {
		t.Fatalf("expected table execution_grant_claims to exist, count=%d, err=%v", tableCount, err)
	}
}
