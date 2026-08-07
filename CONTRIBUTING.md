# Contributing

`cloud-itonami-8411` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/*` libraries. This repo holds the
business blueprint and operator contracts.

```bash
clojure -X:test   # in the referenced kotoba-lang libs
clojure -M:lint
```

## Rules
- Do not commit real operating, personal or credential data.
- Keep robot dispatch, records and disclosures behind the Public Administration Governor.
- Treat workflows as high-risk: add tests for robot-safety gating,
  record integrity, disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
