package dbmigrate

import "github.com/pressly/goose/v3/lock"

// NewMigrationLockerForTest exposes the internal session locker for integration regression tests.
func NewMigrationLockerForTest() (lock.SessionLocker, error) {
	return newMigrationLocker()
}

// MigrationLockIDForTest exposes the internal fixed lock ID for integration regression tests.
const MigrationLockIDForTest = proofmeshMigrationLockID
