# Running the first pilot

gta's deterministic part is tested; whether real models follow its briefs is not (see
[overview.md](../design/overview.md)). A pilot answers that on one real service before anyone relies on gta. Plan
about one week for one developer.

## Before you start

- [ ] A service with at least one REST flow whose behaviour someone on the team can confirm. Start with REST: it is the
      most exercised path.
- [ ] `./gtaw doctor` is green or warns only about things you accept (plan §12).
- [ ] The model per tier is chosen in `platforms/<platform>/mapping.yaml` (`models:`; `null` uses the model picked in
      the chat). Use a different model family for the reviewer than for the test author if you can.
- [ ] In the IDE: the gta agents are visible, a subagent can be dispatched (or set `dispatch: "inline"`), `./gtaw next`
      runs without a prompt and `./gtaw abort` asks for confirmation.
- [ ] A branch for the pilot. gta commits its tests; nothing is pushed.

## What to run

1. `/gta spec <flow>` on one flow: read the spec without writing tests. Is it what the code does? Are the questions
   the ones a reviewer would ask?
2. `/gta <flow>` on the same flow, to the end of the session.
3. `/gta` (service mode) on two or three more flows, one of them not REST if the service has one.
4. Change the code of a tested flow on a branch and run `/gta pr`.

## What to record

Per flow, in a table like this (gtaw does not time anything yet; note the clock and the model's usage panel):

| Flow | Scenarios | Questions asked | Answered with `OK` | Your replies in total | Minutes to the first verified test | Minutes for the flow | Model requests or cost | Tests passed / defects found / needed you |
|---|---|---|---|---|---|---|---|---|

And each time an agent leaves the protocol, one line: the node (analyze, author, review), what it did (edited another
file, ran git, skipped `gtaw submit`, changed an expected value, asked you something gtaw should have asked), and what
gtaw did about it (reverted, refused, did not notice).

Where to look afterwards:

- `.gta/reports/` — the session reports; `.gta/specs/` — the specs and your recorded answers;
- `.gta/specs/INDEX.md` and `.gta/specs/<domain>/<flow>.cases.md` — one row per scenario with its test and result;
- `.gta/lessons.md` — what gtaw repaired by rule and what failed the can-fail check;
- `git log` on the pilot branch — one commit per item.

## What decides the outcome

| Question | Good enough | If not |
|---|---|---|
| Do the agents follow the briefs? | gtaw never has to revert more than a few edits per flow, and nothing slips past it | fix the agent or skill text in `core/`; a deviation gtaw did not notice is a bug in gtaw |
| Are the specs right? | you would sign them after answering the questions | improve `gta-writing-behavior-specs` and `gta-mapping-flows` |
| Are seeded or known defects found? | a wrong behaviour you know of becomes a question or a pinned defect, never a green test | the adversarial evals (`evals/e2e/run-adversarial.sh`) need a case for it |
| Is the cost acceptable? | time and requests per flow fit how the team would use it | candidates: one author run per flow instead of per scenario, reviewing only p0 scenarios (not built; decide with the numbers) |
| Do the skills' examples compile on your stack? | the first test of each kind needs no manual fix | correct the skill; note the Boot and library versions |

Send the table, the deviations and the answers to these five questions back to the gta maintainers: they decide what
changes before a second service.
