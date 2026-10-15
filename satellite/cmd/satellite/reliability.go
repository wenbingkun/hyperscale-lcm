package main

import (
	"log"
	"sync"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

const (
	defaultLedgerSize = 1024
	defaultOutboxSize = 256
)

func isTerminal(s pb.JobStatus) bool {
	return s == pb.JobStatus_COMPLETED || s == pb.JobStatus_FAILED
}

// commandLedger remembers command_ids seen by this process so a repeated
// delivery is never executed twice. In-memory only: a Satellite restart clears it.
type commandLedger struct {
	mu      sync.Mutex
	max     int
	entries map[string]*pb.JobStatusUpdate // nil value = still running
	order   []string
}

func newCommandLedger(max int) *commandLedger {
	return &commandLedger{max: max, entries: map[string]*pb.JobStatusUpdate{}}
}

type ledgerState int

const (
	ledgerNew ledgerState = iota
	ledgerRunning
	ledgerDone
)

// begin registers id as running when unseen. For a repeat it reports the
// state and, when done, the stored terminal update.
func (l *commandLedger) begin(id string) (ledgerState, *pb.JobStatusUpdate) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if term, ok := l.entries[id]; ok {
		if term == nil {
			return ledgerRunning, nil
		}
		return ledgerDone, term
	}
	l.entries[id] = nil
	l.order = append(l.order, id)
	// Evict the oldest finished entries only; running ones must stay.
	for len(l.order) > l.max {
		evicted := false
		for i, old := range l.order {
			if l.entries[old] != nil {
				delete(l.entries, old)
				l.order = append(l.order[:i], l.order[i+1:]...)
				evicted = true
				break
			}
		}
		if !evicted {
			break
		}
	}
	return ledgerNew, nil
}

func (l *commandLedger) finish(id string, terminal *pb.JobStatusUpdate) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, ok := l.entries[id]; ok {
		l.entries[id] = terminal
	}
}

// outbox holds terminal updates that could not be sent, one per job, bounded
// (oldest dropped). Best effort: lost on restart; Core's sweeper is the backstop.
type outbox struct {
	mu    sync.Mutex
	max   int
	order []string
	items map[string]*pb.JobStatusUpdate
}

func newOutbox(max int) *outbox {
	return &outbox{max: max, items: map[string]*pb.JobStatusUpdate{}}
}

func (o *outbox) add(u *pb.JobStatusUpdate) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, ok := o.items[u.JobId]; !ok {
		o.order = append(o.order, u.JobId)
	}
	o.items[u.JobId] = u
	for len(o.order) > o.max {
		log.Printf("⚠️ Outbox full; dropping unsent terminal status of job %s", o.order[0])
		delete(o.items, o.order[0])
		o.order = o.order[1:]
	}
}

// takeAll empties the outbox, returning updates oldest first.
func (o *outbox) takeAll() []*pb.JobStatusUpdate {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := make([]*pb.JobStatusUpdate, 0, len(o.order))
	for _, id := range o.order {
		out = append(out, o.items[id])
	}
	o.order = nil
	o.items = map[string]*pb.JobStatusUpdate{}
	return out
}
