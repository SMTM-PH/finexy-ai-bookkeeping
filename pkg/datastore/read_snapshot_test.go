package datastore

import "testing"

func TestReadSnapshotIsolationRequiresRepeatableBaseline(t *testing.T) {
	for _, value := range []string{"REPEATABLE-READ", "repeatable read", " SERIALIZABLE "} {
		if !repeatableReadIsolation(value) {
			t.Fatalf("rejected repeatable isolation %q", value)
		}
	}
	for _, value := range []string{"", "READ-COMMITTED", "READ UNCOMMITTED", "unknown"} {
		if repeatableReadIsolation(value) {
			t.Fatalf("accepted insufficient isolation %q", value)
		}
	}
}
