# TUFCI Variant Comparison

Six variants built on two axes:

|                | Full (P1–P7) | Search-Strategy (P2+P3) | Computation (P4–P7) |
|----------------|:---:|:---:|:---:|
| **Best-First** | V1  | V3  | V5  |
| **DFS**        | V2  | V4  | V6  |

---

## Per-variant pruning matrix

| Rule | V1 | V2 | V3 | V4 | V5 | V6 |
|------|:--:|:--:|:--:|:--:|:--:|:--:|
| P1  Phase-2 early termination | on | on | off | off | off | off |
| P2  Main-loop threshold | break | cont | break | cont | cont | cont |
| P3  Item-support break | on | on | on | on | off | off |
| P4  Subset upper-bound | on | on | off | off | on | on |
| P5  Bound-based filter | on | on | off | off | on | on |
| P6  Tidset-size shortcut | on | on | off | off | on | on |
| P7  Tidset closure skip | on | on | off | off | on | on |
| Search | BFS | DFS | BFS | DFS | BFS | DFS |

---

## Axis 1 — Search Strategy: BFS vs DFS *(columns)*

| Aspect | BFS (V1, V3, V5) | DFS (V2, V4, V6) |
|--------|-------------------|-------------------|
| Data structure | `PriorityQueue` | `Deque` (stack, LIFO) |
| Candidate order | Highest support first | Most recently added first |
| P2 action | `break` (V1 only) or `continue` | `continue` |

Only V1 uses P2 `break`. That rule requires the PQ guarantee: "if the top
candidate is below threshold, all remaining are too." No other variant has it.

---

## Axis 2 — Pruning Level *(rows)*

### Search-strategy pruning (P1, P2-break, P3)
- **P1** – Phase 2 singleton loop early termination. Relies on the singleton
  list sorted DESC (Phase 1 output). Not BFS-specific, but controls *what*
  Phase 2 explores. Enabled in V1/V2 only.
- **P3** – Item-loop break when `support(i) < threshold`. Relies on
  `frequentItems` sorted DESC. Not BFS-specific, but controls *what* the
  item loop explores. Enabled in V1–V4.
- **P2 break** – Global Phase 3 termination. The only rule that structurally
  requires BFS. Enabled in V1 and V3 only.

### Computation pruning (P4–P7)
- **P4** – Tightens upper bound using cached 2-itemset supports.
- **P5** – Filters extensions whose P4-tightened bound < threshold.
- **P6** – Skips the expensive convolution when `|tidset| < threshold`.
- **P7** – Skips the closure check when `|tidset| < supX`.

All four are enabled in V1/V2 (full) and V5/V6 (computation-only).

---

## P1 impact (from closure_checks.csv)

| Dataset  | k  | V1 (P1 on) | V3/V4 (P1 off) |
|----------|---:|-----------:|---------------:|
| foodmart | 10 | 12         | 1559           |
| foodmart | 50 | 79         | 1559           |
| chess    | 10 | 16         | 88–90          |
| mushroom | 10 | 15         | 124–127        |

P1 is critical on large-vocabulary datasets (foodmart: 1559 items).
Without it, every singleton is closure-checked regardless of heap state.

---

## Pins in code

Each source file has two `★ KEY` comments:
- `★ KEY [Search]` — the P2 `break` / `continue` in Phase 3
- `★ KEY [Pruning]` — the closure-check method call (full / search-only / computation-only)
# NguyenHuyThien
# NguyenHuyThien_TUFCI
