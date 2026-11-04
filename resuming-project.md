# Resuming the SLParcels project

**Status: the AWS production stack was torn down on 2026-11-04.** The code,
Terraform, domains and a final database snapshot were kept so the project can
be brought back. This document records exactly what is left in AWS, what was
lost for good, and the ordered steps to get production running again.

Companion docs:

- [`PREP.md`](PREP.md) — one-time AWS account setup (SSO, state bucket, GitHub OIDC). Already done; nothing here needs repeating unless the AWS account itself is recreated.
- [`docs/superpowers/specs/2026-04-29-aws-deployment-design.md`](docs/superpowers/specs/2026-04-29-aws-deployment-design.md) — architecture and the original deploy sequence.
- [`README.md` → Production deployment](README.md#production-deployment) — how the stack is operated once it is up.

---

## 1. What the teardown did

A targeted `terraform destroy` removed every module except `dns` (103
resources). Before the destroy, deletion protection was switched off on RDS
and the ALB, the storage bucket and ECR repos were emptied by hand, and
Amplify auto-build was disabled.

### Still in AWS (account `486208158127`, `us-east-1`)

| Thing | Where | Notes |
|---|---|---|
| Route 53 hosted zones `slparcels.com`, `slpa.app` + ACM certs | Terraform state, `module.dns` | Registrar NS delegation is still valid, so the domains keep working without re-verification. ~$1/mo. |
| Final DB snapshot `slpa-prod-postgres-final-20260429-192349` | RDS → Snapshots (manual) | **Data as of 2026-11-04.** The date in the name is from the original apply, not the snapshot time. |
| Older manual snapshot `slpa-prod-postgres-pre-wipe-20260502` | RDS → Snapshots | Pre-launch data; probably not useful. |
| Terraform state bucket `slpa-prod-tfstate` | S3 | State key `prod/terraform.tfstate`. Only `module.dns` resources remain in it. |
| Hand-set secrets under `/slpa/prod/…` | SSM Parameter Store | `jwt/secret`, `escrow/terminal-shared-secret`, `bot/shared-secret`, `bot-1/*`, `bot-2/*`, `sl/primary-escrow-uuid`, `sl/trusted-owner-keys`, `notifications/sl-im/dispatcher-secret`. Free. |
| Amplify app `dil6fhehya5jf` (`slparcelauctions`) | Amplify console | Auto-build **off**. Still serves the last frontend build at `slparcels.com`; every API call fails because the backend is gone. Env vars (`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_WS_URL`) are intact. |
| GitHub OIDC identity provider | IAM | Created manually in PREP.md §10; Terraform reads it as a data source. |
| IAM Identity Center user `heath` + `slpa-prod` CLI profile | IAM Identity Center | Unchanged. |

### Gone for good

- **S3 storage bucket contents** — avatars, listing photos, dispute evidence. The bucket was versioned, but all versions and delete markers were purged before destroy. Any restored auctions/users will reference image keys that no longer exist.
- **ECR images** — both repos and every tagged image. CI rebuilds these.
- **CloudWatch logs**, alarm history, the dashboard.
- **Terraform-generated secrets** — `/slpa/prod/rds/{username,password}` and `/slpa/prod/redis/auth-token`. These are regenerated with new random values on the next apply (see the restore caveat in step 4).
- **Automated RDS backups** — deleted with the instance. Only the two manual snapshots survive.
- The `slpa-prod-gha-deploy` IAM role, so the `deploy backend` / `deploy bots` GitHub Actions workflows fail until Terraform recreates it (same name, same ARN, so the workflows need no edits).

---

## 2. Decisions to make before starting

1. **Restore the database or start fresh?** Restoring keeps users, auctions, wallets and escrow history but every photo/avatar link is dead. Starting fresh means Flyway builds an empty schema and everyone re-registers. If in doubt, start fresh — the snapshot stays available either way.
2. **Same account and region?** This doc assumes yes. A new account means redoing `PREP.md` end-to-end first.
3. **Are the bot SL accounts still alive?** Linden Lab can lock long-idle accounts. Log `SLPABot1` / `SLPABot2` in with a viewer once before relying on them (creds are in SSM `/slpa/prod/bot-N/`).

---

## 3. Local prerequisites

```bash
aws sso login --profile slpa-prod
aws --profile slpa-prod sts get-caller-identity     # confirm AdministratorAccess

terraform version                                    # >= 1.5; 1.15.1 was used for the teardown
cd infra && AWS_PROFILE=slpa-prod terraform init     # re-attaches to the S3 backend
terraform state list                                 # should show only module.dns.*
```

Work from a checkout of `main` for the Terraform apply (infra applies happen
from `main`, never a feature branch — see the PR-flow rule in `CLAUDE.md`).

---

## 4. Bring the infrastructure back

### 4a. Write `infra/terraform.tfvars` (gitignored)

```hcl
alarm_email            = "heath.barcus@gmail.com"

# Declared without defaults but no longer referenced by any module (the
# Amplify module that used them was removed). Any string satisfies them.
codestar_connection_arn = "unused"
github_pat              = "unused"

# Leave at 0 for the first apply — there is no image in ECR yet and the
# backend task would crash-loop. Bumped in step 6.
backend_desired_count = 0

bot_active_count = 2
```

Everything else can stay at the launch-lite defaults in `infra/variables.tf`
(db.t4g.micro, cache.t4g.micro, fck-nat instance, 0.5 vCPU backend).

### 4b. Optional: restore from the snapshot

Skip this sub-step if starting fresh.

`infra/data/rds.tf` has no `snapshot_identifier` today. Add one via a PR
(feature → `dev` → `main`), so the restore is reproducible:

```hcl
resource "aws_db_instance" "main" {
  # ...
  snapshot_identifier = "slpa-prod-postgres-final-20260429-192349"

  lifecycle {
    ignore_changes = [final_snapshot_identifier, snapshot_identifier]
  }
}
```

Caveats:

- `engine_version` in the config (17.5) must be ≥ the snapshot's engine version. Check with `aws rds describe-db-snapshots --db-snapshot-identifier … --query 'DBSnapshots[0].EngineVersion'`.
- The snapshot carries the **old** master password. Terraform generates a new `random_password` and writes it to SSM; the AWS provider applies the new password to the restored instance after creation, but verify it. If the backend logs `password authentication failed` on first boot, run:
  ```bash
  aws --profile slpa-prod rds modify-db-instance \
    --db-instance-identifier slpa-prod-postgres \
    --master-user-password "$(aws --profile slpa-prod ssm get-parameter --name /slpa/prod/rds/password --with-decryption --query Parameter.Value --output text)" \
    --apply-immediately
  ```
- `allocated_storage` must be ≥ the snapshot's (20 GB). Default is fine.

### 4c. Apply

```bash
cd infra
AWS_PROFILE=slpa-prod terraform plan -out=tfplan      # expect ~100 to add, 0 to destroy
AWS_PROFILE=slpa-prod terraform apply tfplan
```

This takes 10–20 minutes (RDS and ElastiCache are the slow parts). It
recreates the VPC, NAT instance, RDS, Redis, S3 bucket, ALB with the
`slpa.app` apex record, ECS cluster + services (backend at 0 tasks, bots at
1 each), ECR repos, log groups, alarms, budgets, SNS topic and the GitHub
deploy role.

The bot services will start and immediately fail because their ECR repo is
empty. That is expected until step 5.

---

## 5. Rebuild and push the images

The ECR repos are empty and the task definitions point at the `initial` tag.
Run both deploy workflows manually from GitHub → Actions:

- **deploy backend** → Run workflow (branch `main`)
- **deploy bots** → Run workflow (branch `main`)

Each builds from `main`, pushes `<sha>` to ECR, registers a new task-def
revision and updates the service. The backend workflow will wait on service
stability and **time out, because desired count is still 0** — that is fine,
the image and task-def revision are what matter.

Alternatively push a commit touching `backend/**` and `bot/**` to `main`
through the normal PR flow.

---

## 6. Start the backend

Set `backend_desired_count = 1` in `terraform.tfvars` and apply again.

> **Divergence warning.** Terraform owns the ECS task definition and still
> has `backend_image_tag = "initial"` (see the note in
> `infra/compute/ecs_backend.tf`). This apply will register a revision that
> points at `:initial`, which does not exist in ECR. Either:
>
> - set `backend_image_tag` and `bot_image_tag` in tfvars to the git SHA the
>   workflows just pushed (`aws ecr describe-images --repository-name slpa/backend --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags'`), **or**
> - apply, then immediately re-run the two deploy workflows with
>   `workflow_dispatch` to roll the services back onto the SHA-tagged image.
>
> The first option is cleaner.

Then watch it come up:

```bash
aws --profile slpa-prod ecs describe-services --cluster slpa-prod \
  --services slpa-prod-backend slpa-prod-bot-1 slpa-prod-bot-2 \
  --query 'services[].{name:serviceName,desired:desiredCount,running:runningCount}'
aws --profile slpa-prod logs tail /aws/ecs/slpa-backend --since 5m --format short
curl -fsS https://slpa.app/api/v1/health
```

A fresh database runs every Flyway migration on first boot; a restored one
picks up from the last applied version. `SlStartupValidator` and
`BotStartupValidator` will refuse to start if any hand-set SSM secret is
missing — the list in section 1 is what they expect.

---

## 7. Frontend

```bash
aws --profile slpa-prod amplify update-branch --app-id dil6fhehya5jf --branch-name main --enable-auto-build
aws --profile slpa-prod amplify start-job --app-id dil6fhehya5jf --branch-name main --job-type RELEASE
aws --profile slpa-prod amplify list-jobs --app-id dil6fhehya5jf --branch-name main --max-items 3
```

Do this **after** the backend health check passes — the build prerenders
against the live API and fails on a dead one (see Frontend SSR caveats in
`CLAUDE.md`). Custom domain `slparcels.com` and its records in the kept
hosted zone are untouched, so no DNS work is needed.

---

## 8. Post-launch checklist

- [ ] Confirm the SNS alert email subscription — AWS sends a fresh confirmation link to `alarm_email` because the topic was recreated.
- [ ] Check that both bots report healthy at `GET /api/v1/admin/bot-pool/health` (or the Admin → Infrastructure page) and that they can log in to SL.
- [ ] Re-run the Postman `SLPA` collection smoke flow against prod.
- [ ] If the database was restored, reconcile any auctions that were mid-escrow on 2026-11-04 — escrow deadlines will have expired and `OwnershipCheckTask` will flag listings whose parcels changed hands while the site was down.
- [ ] Re-deploy the in-world LSL terminals only if the terminal shared secret was rotated; otherwise the existing objects keep working once `slpa.app` resolves again.
- [ ] Update the README "Live endpoints" section and remove the paused-project note.

---

## 9. If instead the decision is to shut down permanently

The remaining monthly cost is about $1. To reach zero:

```bash
# Delete the two manual snapshots (data is unrecoverable after this)
aws --profile slpa-prod rds delete-db-snapshot --db-snapshot-identifier slpa-prod-postgres-final-20260429-192349
aws --profile slpa-prod rds delete-db-snapshot --db-snapshot-identifier slpa-prod-postgres-pre-wipe-20260502

# Destroy the hosted zones + certs (domains stop resolving)
cd infra && AWS_PROFILE=slpa-prod terraform destroy

# Delete the Amplify app, then the state bucket and SSM parameters by hand
aws --profile slpa-prod amplify delete-app --app-id dil6fhehya5jf
```

Let the domain registrations lapse or transfer them separately.
