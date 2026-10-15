package main

import (
	"fmt"
	"testing"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

func TestLedgerDedupe(t *testing.T) {
	l := newCommandLedger(10)
	if s, _ := l.begin("a"); s != ledgerNew {
		t.Fatal("want new")
	}
	if s, _ := l.begin("a"); s != ledgerRunning {
		t.Fatal("want running")
	}
	term := &pb.JobStatusUpdate{JobId: "a", Status: pb.JobStatus_COMPLETED}
	l.finish("a", term)
	if s, got := l.begin("a"); s != ledgerDone || got != term {
		t.Fatal("want done with stored terminal")
	}
}

func TestLedgerEvictsOnlyFinished(t *testing.T) {
	l := newCommandLedger(2)
	l.begin("run") // stays running
	for i := 0; i < 5; i++ {
		id := fmt.Sprintf("j%d", i)
		l.begin(id)
		l.finish(id, &pb.JobStatusUpdate{JobId: id})
	}
	if s, _ := l.begin("run"); s != ledgerRunning {
		t.Fatal("running entry must not be evicted")
	}
	if s, _ := l.begin("j0"); s != ledgerNew {
		t.Fatal("oldest finished entry should have been evicted")
	}
}

func TestOutboxBoundedAndDeduped(t *testing.T) {
	o := newOutbox(2)
	o.add(&pb.JobStatusUpdate{JobId: "a", Message: "1"})
	o.add(&pb.JobStatusUpdate{JobId: "a", Message: "2"})
	o.add(&pb.JobStatusUpdate{JobId: "b"})
	o.add(&pb.JobStatusUpdate{JobId: "c"})
	got := o.takeAll()
	if len(got) != 2 || got[0].JobId != "b" || got[1].JobId != "c" {
		t.Fatalf("got %v", got)
	}
	if len(o.takeAll()) != 0 {
		t.Fatal("outbox should be empty")
	}
}
