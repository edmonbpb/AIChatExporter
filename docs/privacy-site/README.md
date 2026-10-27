# Temporary privacy-policy site

Live at:

- https://dqn32v.bot-keep.xyz/privacy.html
- https://dqn32v.bot-keep.xyz/privacy (same page, short path)

It exists for one reason: the Google OAuth consent screen needs a public privacy-policy URL.

## How it is served

The Botkeep workload `edmondev` (`be035102-d115-4c7b-8831-7631e7e72b6a`, nodejs 22) runs the Shiro
relay. Two additive GET routes were added to its `index.js` (13 lines, no existing line changed) and
`privacy.html` was uploaded next to it. The relay's own `/` and `/v1/health` are untouched.

The original `index.js` is kept at `docs/reports/raw/_bk_index.js` for rollback.

## Updating the policy

`privacy.html` here is a copy of `docs/privacy-policy.html`. After editing the policy:

1. `cp docs/privacy-policy.html docs/privacy-site/privacy.html`
2. Upload it to the workload (`PUT /workloads/<id>/file` with `path=/privacy.html`); no restart is
   needed because the file is read per request.

## Removing it later

Delete the two routes from `index.js` and the `privacy.html` file, then restart the workload.
