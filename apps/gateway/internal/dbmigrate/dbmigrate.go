package dbmigrate

import (
	"context"
	"database/sql"
	"errors"
	"io/fs"
	"strings"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"

	"github.com/Suthankan1/proofmesh/apps/gateway/migrations"
)

// Sentinel errors returned by dbmigrate operations.
// Raw database errors, connection strings, credentials, migration SQL,
// and driver details are strictly sanitized and never returned across this boundary.
var (
	ErrInvalidConfig    = errors.New("dbmigrate: invalid configuration")
	ErrConnectionFailed = errors.New("dbmigrate: database connection failed")
	ErrMigrationFailed  = errors.New("dbmigrate: migration execution failed")
)

// Runner defines the authoritative contract for applying database migrations.
type Runner interface {
	Up(ctx context.Context) error
}

// Migrator implements Runner using PostgreSQL and goose migration engine.
type Migrator struct {
	db   *sql.DB
	fsys fs.FS
}

// Ensure Migrator implements Runner.
var _ Runner = (*Migrator)(nil)

// New creates a new Migrator targeting the provided PostgreSQL connection string
// using embedded gateway migration scripts.
func New(dbURL string) (*Migrator, error) {
	return NewWithFS(dbURL, migrations.FS)
}

// NewWithFS creates a new Migrator targeting the provided PostgreSQL connection string
// using a specified migration filesystem.
func NewWithFS(dbURL string, fsys fs.FS) (*Migrator, error) {
	trimmed := strings.TrimSpace(dbURL)
	if trimmed == "" {
		return nil, ErrInvalidConfig
	}

	if fsys == nil {
		fsys = migrations.FS
	}

	db, err := sql.Open("pgx", trimmed)
	if err != nil {
		return nil, ErrInvalidConfig
	}

	return &Migrator{
		db:   db,
		fsys: fsys,
	}, nil
}

// Ping verifies that the target PostgreSQL database is reachable within context bounds.
func (m *Migrator) Ping(ctx context.Context) error {
	if m == nil || m.db == nil {
		return ErrInvalidConfig
	}
	if err := m.db.PingContext(ctx); err != nil {
		return ErrConnectionFailed
	}
	return nil
}

// proofmeshMigrationLockID is the deterministic 64-bit advisory lock ID shared by all
// ProofMesh gateway migration runners to serialize the entire migration lifecycle
// (pre-provider initialization, version-table bootstrap, discovery, and execution).
// Derived from the compile-time ASCII string "PROOFMES" (0x50524f4f464d4553 = 5787796990494786901).
const proofmeshMigrationLockID int64 = 0x50524f4f464d4553

func newMigrationLocker() (lock.SessionLocker, error) {
	return lock.NewPostgresSessionLocker(
		lock.WithLockID(proofmeshMigrationLockID),
		lock.WithLockTimeout(1, 300),
		lock.WithUnlockTimeout(1, 30),
	)
}

// Up applies all pending database migrations within an outer PostgreSQL session advisory lock.
// It is safe for concurrent invocations and idempotent across serial runs.
func (m *Migrator) Up(ctx context.Context) error {
	if m == nil || m.db == nil {
		return ErrInvalidConfig
	}

	conn, err := m.db.Conn(ctx)
	if err != nil {
		return ErrMigrationFailed
	}
	defer conn.Close()

	locker, err := newMigrationLocker()
	if err != nil {
		return ErrMigrationFailed
	}

	if err := locker.SessionLock(ctx, conn); err != nil {
		return ErrMigrationFailed
	}
	defer func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = locker.SessionUnlock(cleanupCtx, conn)
	}()

	provider, err := goose.NewProvider(
		goose.DialectPostgres,
		m.db,
		m.fsys,
		goose.WithLogger(goose.NopLogger()),
	)
	if err != nil {
		return ErrMigrationFailed
	}

	if _, err := provider.Up(ctx); err != nil {
		return ErrMigrationFailed
	}

	return nil
}

// Close releases any database resources held by the Migrator.
func (m *Migrator) Close() error {
	if m == nil || m.db == nil {
		return nil
	}
	if err := m.db.Close(); err != nil {
		return ErrConnectionFailed
	}
	return nil
}

// Run is a convenience function that initializes a Migrator, applies all pending
// migrations within the given context, and closes the connection.
func Run(ctx context.Context, dbURL string) error {
	m, err := New(dbURL)
	if err != nil {
		return err
	}
	defer m.Close()

	if err := m.Ping(ctx); err != nil {
		return err
	}

	return m.Up(ctx)
}
