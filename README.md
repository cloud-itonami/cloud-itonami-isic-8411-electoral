# cloud-itonami-isic-8411-electoral

Open Business Blueprint for **ISIC Rev.5 8411**, electoral-administration
subject: **the receiving side of an election** — filing receipt, formal
review, deficiency notice and entry in the public register.

This repository publishes an electoral-administration actor as an OSS
business that any qualified operator can fork, deploy, run, improve and
sell, so an electoral authority keeps its own filing records and audit
trail instead of renting a closed election-administration SaaS.

It is the **counterpart** of
[`cloud-itonami-isic-9492`](https://github.com/cloud-itonami/cloud-itonami-isic-9492)
(political organizations). 9492 stands on the campaign's side of the
counter and asks *what must we submit, and is this campaign method even
lawful here*. This repo stands on the authority's side and asks *what do
we receive, what does the formal review cover, and was it in time*.

**Both read the same table.** The per-jurisdiction filing catalog lives
once, in [`kotoba-lang/senkyo`](https://github.com/kotoba-lang/senkyo).
If the two sides kept separate catalogs, "what a campaign is told to
submit" and "what an authority is set up to receive" would drift apart
silently, and the drift would surface as a candidate losing a right on a
technicality nobody could trace. That failure mode is designed out, not
guarded against.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as every prior actor
in this fleet. Here it is **ElectoralOps-LLM ⊣ Electoral Administration
Governor**.

> **Why an actor layer at all?** An LLM is good at normalizing a filing
> record, assembling a formal-review checklist and drafting a deficiency
> notice. It has **no notion of which authority is set up to receive a
> given filing, when the statutory deadline actually falls, or where the
> line between a formal review and a substantive determination runs**.
> In electoral administration those three gaps are not inconveniences:
> a missed deadline forfeits a right, receiving a filing at the wrong
> authority voids it, and a substantive determination made by software
> is a determination made by nobody accountable.

## IMPORTANT: SCOPE BOUNDARIES

**This actor is EXPLICITLY NOT an election authority, adjudicator or
returning officer — it is administrative support only.**

### What this actor DOES

- Filing receipt record-keeping (`:filing/receive`)
- Formal review against the jurisdiction's own published review items
  (`:review/formal`)
- Deficiency-notice drafting — listing which review items are still
  outstanding (`:deficiency/notice`)
- Entry in the filing register, human-approved, with an unsigned
  receipt draft (`:actuation/publish-receipt`)
- Immutable audit ledger for every one of the above

### What this actor DOES NOT (hard boundaries, permanently out of scope)

These are **permanently forbidden**. They are not gated by risk level,
they cannot be escalated for human override, and the proposal vocabulary
has no path to construct them —
`electoralops.intake/out-of-scope-intents` is a closed set checked by the
governor before anything else runs:

- **Refusing a filing** (`:acceptance-refusal`) — the actor can report
  that review items are outstanding; deciding *not to accept* is a human
  officer's act
- **Assessing a candidate or party** (`:candidate-assessment`) — no
  substantive judgement about who may stand
- **Determining a result** (`:result-determination`) — no counting, no
  declaring
- **Adjudicating eligibility or ballot validity**
  (`:eligibility-adjudication`, inherited from `senkyo`)
- Everything `senkyo` already forbids on the campaign side — voter
  targeting, vote-persuasion scripting, candidate ranking, turnout
  operations, distribution dispatch, opinion profiling — because a
  receiving authority reaching for any of them is worse, not better,
  than a campaign doing so

The set **extends** the shared library's boundary rather than replacing
it, and a test pins that: everything `senkyo.screen/out-of-scope-intents`
forbids is still forbidden here.

## The governor: six HARD checks

A human approver **cannot** override any of these.

| # | Check | Why it exists |
|---|---|---|
| 1 | `:no-procedure-basis` | The procedure is not in `senkyo`. Absence of a catalog entry is not permission to invent receipt requirements. |
| 2 | `:wrong-receiving-authority` | Japan's 供託 is received by the 法務局, not the election commission. An actor that receives filings on the wrong counter voids them. |
| 3 | `:filing-deadline-not-satisfied` | The deadline is **recomputed** from `senkyo` against the filing's own `:submitted-epoch-day`. `:past` holds — and so do `:unresolved`, `:unknown-filing` and `:no-basis`. |
| 4 | `:formal-review-incomplete` | Entry in the register requires **every** review item the jurisdiction publishes, not a majority. |
| 5 | `:out-of-scope-intent` | See boundaries above. |
| 6 | `:already-published` | Double entry, blocked off a dedicated `:published?` boolean rather than a `:status` value. |

Plus one SOFT gate: `:actuation/publish-receipt` always escalates to a
human, and `electoralops.phase` independently never auto-commits it.
Two layers agree.

### Not knowing a deadline is not the same as there being none

This is the discipline the receiving side most needs and most easily
loses. `senkyo.procedure/due-epoch-day` returns three different things —
an integer, `:no-deadline` (this layer genuinely does not decide, e.g.
US ballot access is state law), and `:unknown-anchor` (nobody supplied
the polling date). Collapsing the third into the second turns "we did not
check" into "there was nothing to check", and the filing goes in the
register anyway.

So `electoralops.intake/blocking-deadline-statuses` blocks
`:past`, `:unresolved`, `:unknown-filing` **and** `:no-basis`, and
passes only `:within` and `:no-deadline`. Election anchor days are
injected per run (`:anchors` in the actor context), not configured on the
actor — because the dates change every election, and an actor carrying
last election's dates would answer confidently and wrongly.

## Actuation

**Entering a filing in the public register is never autonomous, at any
phase, by construction.** It has legal effect on a candidate's standing.
Two independent layers enforce this: `electoralops.governor`'s
high-stakes gate, and `electoralops.phase`'s table, which never puts
`:actuation/publish-receipt` in any phase's `:auto` set — including
phase 3. `:review/formal` and `:deficiency/notice` are likewise never
auto-eligible: a formal review that auto-commits is a formal review
nobody read, and a deficiency notice imposes a correction burden on a
candidate that cannot be taken back once sent.

The only auto-eligible op is `:filing/receive` — recording that a
document arrived does not itself change anyone's standing.

## Run

```bash
clojure -M:dev:run     # one clean lifecycle + five HARD-hold cases
clojure -M:test        # 33 tests / 160 assertions
clojure -M:lint        # clj-kondo, errors fail
```

The demo's point is not that it runs; it is that it **stops**:

```
3. 受理台帳へ登載 (clean, 必ず承認待ち)   -> :escalate  (承認後 :commit)
5. 期限徒過の収支報告を受理               -> :hold
6. 形式審査が未了のまま登載               -> :hold
7. 未収録法域の手続きを審査               -> :hold
8. 選管が受領者でない手続き (供託=法務局)   -> :hold
```

## Layout

| File | Role |
|---|---|
| `src/electoralops/intake.kotoba` | **The receiving-side reading of `senkyo.procedure`** — receivable-here, formal-review items, independent deadline recomputation, the extended out-of-scope set |
| `src/electoralops/store.kotoba` | **Store** protocol — `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + receipt register |
| `src/electoralops/registry.kotoba` | Filing-receipt draft records (unsigned — signature is the authority's own act) + `submitted-within-deadline?` ground-truth recompute |
| `src/electoralops/electoralopsllm.kotoba` | **ElectoralOps-LLM** — `mock-advisor` ‖ `llm-advisor` |
| `src/electoralops/governor.kotoba` | **Electoral Administration Governor** — 6 HARD checks + 1 soft |
| `src/electoralops/phase.kotoba` | **Phase 0→3** — read-only → assisted intake → assisted review → supervised |
| `src/electoralops/operation.kotoba` | **OperationActor** — langgraph StateGraph |
| `src/electoralops/sim.kotoba` | demo driver |
| `test/electoralops/*_test.clj` | governor contract · intake/deadline semantics · phase invariants · store parity |

## Jurisdiction coverage (honest)

`electoralops.intake/coverage` returns `senkyo`'s procedure coverage
verbatim — this repo does not recount it. Currently **5** jurisdictions
(JPN, USA, GBR, DEU, CAN) / **14** procedures out of ~194 jurisdictions
worldwide, and `:needs-source-check` names every procedure whose
`:proc/confidence` is not `:high`.

**Read that list before relying on a deadline.** A day's error in an
electoral deadline forfeits a right; several entries carry deadline
figures that are correct at the statute level but whose exact day count
moves with amendments. Extending coverage is additive — one map entry in
`senkyo.procedure/procedures`, citing a real official source. Never
fabricate a jurisdiction's requirements to make coverage look bigger.

## Business-process coverage (honest)

| Covered | Not covered |
|---|---|
| Filing receipt, formal review, deficiency notice, register entry — each governed, spec-cited and audited | Real integration with any national election system; the actual act of accepting or refusing a filing |
| Independent deadline recomputation against the jurisdiction's own statutory anchor | Counting, tabulation, result declaration — permanently out of scope |
| Immutable audit ledger for every decision, commit or hold | Substantive eligibility determination — permanently out of scope |

Whoever deploys a live instance is the electoral authority, supplies the
real statutory authority, and bears that jurisdiction's liability. The
software supplies the governed, spec-cited, audited execution scaffold so
that authority does not have to build the compliance layer from scratch.

## License

AGPL-3.0-or-later.
