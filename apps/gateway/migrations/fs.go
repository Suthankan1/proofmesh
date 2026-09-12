package migrations

import "embed"

// FS provides embedded filesystem access to authoritative gateway database migration scripts.
//
//go:embed *.sql
var FS embed.FS
