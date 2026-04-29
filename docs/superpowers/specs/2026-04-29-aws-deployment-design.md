# SLPA — AWS Deployment Design

**Date:** 2026-04-29
**Branch:** `dev`
**Status:** Design (pre-implementation)
**Companion docs:** [`AWS_CALCULATION.md`](../../../AWS_CALCULATION.md) (pricing inputs across four traffic tiers)

---

## 1. Goals + non-goals

### Goals

- Define the **production shape** of SLPA running on AWS: networking, compute, data, storage, secrets, DNS, CI/CD, observability.
- Ship at **launch-lite provisioning** — smallest viable instance/replica counts that work for zero users, with **upgrade levers** built into the IaC so production-tier provisioning is a `.tfvars` flip away.
- Replace the dev-only `ddl-auto: update` schema strategy with **Flyway migrations** as part of this work — first prod deployment is the forcing function.
- Match the local-test platform (**x86_64**) on Fargate so the deploy is not also a port.
- Keep monthly bill at launch under **$200/mo** to avoid burning runway before traffic is validated.
- Produce IaC and a runbook reproducible enough that the next prod deploy (after a disaster, after a region migration, after a fresh AWS account) is a documented procedure, not improvisation.

### Non-goals

- **Multi-region** — single region (`us-east-1`) at launch. Multi-region is a Phase 2 concern only if the user base demands it.
- **Multi-environment (staging + dev)** — prod-only at launch. Local docker-compose stack serves the dev role; staging is a documented future addition.
- **EKS / Kubernetes** — Fargate is the chosen compute primitive. EKS is overkill for one backend service plus five named bot workers.
- **Email channel** — `DEFERRED_WORK.md` records this as removed from roadmap; SL IM forwards offline IMs to email natively.
- **Multi-AZ everything from day one** — Multi-AZ is a documented upgrade lever, not a launch default.
- **WAF, X-Ray traces, Synthetics canaries** — documented one-line enables for when traffic justifies the cost.
- **Reserved instances / Savings Plans** — only worth committing once traffic shape is predictable.
- **Auto-scaling policies on the bot pool** — bots are a fixed-pool (5 named workers) by SL credential model, not autoscaled.

---

## 2. Architecture overview

```
                                         Namecheap (DNS for slparcelauctions.com → 301 forward)

         Users (browsers)                                                         You (operator)
              │                                                                         │
              │ HTTPS                                                                   │ aws cli
              ▼                                                                         │ aws ecs execute-command
   ┌───────────────────────────────────────────────────────────────────┐                │ terraform
   │ Route 53 (Namecheap NS-delegated for slparcels.com + slpa.app)    │                │
   └───────────────────────────────────────────────────────────────────┘                │
              │                                                                         │
       ┌──────┴──────┐                                                                  │
       ▼             ▼                                                                  │
   ┌──────────┐ ┌──────────┐  ┌──────────────────────────────────────────────────────┐  │
   │ Amplify  │ │   ALB    │  │              AWS Account: us-east-1                  │  │
   │(frontend)│ │ (public) │  └──────────────────────────────────────────────────────┘  │
   │slparcels │ │ slpa.app │                                                            │
   │  .com    │ └─────┬────┘                                                            │
   └────┬─────┘       │                                                                 │
        │             ▼                                                                 │
        │   ┌─────────────────────────────────────────────────────────────────────┐     │
        │   │  VPC 10.0.0.0/16 (us-east-1a + us-east-1b)                          │     │
        │   │                                                                     │     │
        │   │   Public subnets (one per AZ — 10.0.0.0/24, 10.0.1.0/24):           │     │
        │   │   ┌─ ALB (both AZs)                                                 │     │
        │   │   ├─ NAT instance (fck-nat on t4g.nano, single — us-east-1a)        │     │
        │   │   └─ S3 Gateway VPC Endpoint (free, both AZs)                       │     │
        │   │                                                                     │     │
        │   │   Private subnets (one per AZ — 10.0.10.0/24, 10.0.11.0/24):        │     │
        │   │   ┌─ ECS Fargate cluster                                            │     │
        │   │   │    ├─ slpa-backend service (1 task @ 0.25vCPU/0.5GB, x86_64)    │     │
        │   │   │    ├─ slpa-bot-1 service (1 task @ 0.25vCPU/0.5GB, x86_64) ┐    │     │
        │   │   │    ├─ slpa-bot-2 service (1 task)                          │ 5  │     │
        │   │   │    ├─ slpa-bot-3 service (1 task)                          ├─named   │
        │   │   │    ├─ slpa-bot-4 service (1 task)                          │ services │
        │   │   │    └─ slpa-bot-5 service (1 task)                          ┘    │     │
        │   │   ├─ RDS Postgres 17 (db.t4g.micro, single-AZ + 7d PITR)            │     │
        │   │   └─ ElastiCache Redis (cache.t4g.micro single-node)                │     │
        │   └─────────────────────────────────────────────────────────────────────┘     │
        │                          │                                                    │
        │                          │ outbound via NAT                                   │
        │                          ▼                                                    │
        │     SL World API ──── world.secondlife.com / cap.secondlife.com               │
        │     SL Grid     ────── (bots' LibreMetaverse connections)                     │
        │                                                                               │
        ▼                                                                               │
    S3 buckets:                                                                         │
      slpa-prod-avatars, slpa-prod-listing-photos, slpa-prod-dispute-evidence           │
      slpa-prod-tfstate (versioned, locked via DynamoDB)                                │
                                                                                        │
    Parameter Store (~25 secrets — JWT, RDS password, bot creds, SL trust UUIDs)        │
    ECR (one private repo per service: slpa/backend, slpa/bot)                          │
    CodeDeploy (blue/green for backend service)                                         │
    CloudWatch (logs 7d retention, ~12 alarms, Container Insights)                      │
    AWS Budgets (soft $200, hard $400)                                                  │
                                                                                        │
    GitHub Actions ───── OIDC role-assumption ────────────────────────────────────────► │
```

---

## 3. Decision ledger

Every brainstorm question, locked answer, and the rationale.

| # | Question | Answer | Rationale |
|---|---|---|---|
| 1 | Doc intent | **E** — full reference architecture + IaC | SLPA carries real escrow money; reproducibility matters at first incident |
| 2 | IaC tool | **Terraform** | Industry-default, precise `plan`/`apply`, broad ops familiarity |
| 3 | Environment count | **Prod-only** | Local docker-compose serves dev role; staging is a future addition |
| 4 | Compute (backend + bots) | **ECS Fargate everything** | Bots are stateful named workers; Fargate's task-per-credential model maps cleanly. Single control plane for all containers. |
| 5 | Frontend hosting | **AWS Amplify** | Vercel rejected on principled grounds; everything-in-AWS preference |
| 6 | RDS posture | **Postgres 17 Multi-AZ** target; **single-AZ at launch** | Multi-AZ is the production target; single-AZ saves $30/mo while at zero users. Upgrade lever flips it. |
| 7 | NAT posture | **NAT instance (fck-nat) single-AZ at launch**; two NAT Gateways at full prod | $5/mo vs $64/mo. NAT instance is the only egress; NAT failure means SL APIs unreachable but DB/cache still work. |
| 8 | Secrets | **Parameter Store (Standard)** | $0 vs $10/mo for Secrets Manager. Per-secret upgrade lever to Secrets Manager when rotation specifically matters. |
| 9 | CI/CD pipeline | **GitHub Actions → ECR → CodeDeploy blue/green** | Auto-rollback on alarm fire; small CodeDeploy cost (~$1/mo) for the safety net |
| 9b | Schema migrations | **Flyway in scope** for this deployment work | `ddl-auto: update` flagged in `DEFERRED_WORK.md` as launch-blocker. Convert dev + prod together to avoid drift. |
| 10a | Bot public exposure | **None** — `bots.slpa.app` not provisioned | ECS Exec covers ad-hoc health checks; no end-user reason for public bot DNS |
| 10b | `slparcelauctions.com` redirect | **Namecheap URL Redirect** (no AWS resources) | $0, no infra; saves Route 53 zone fee |
| 11 | Observability | **B** — production baseline (12 alarms + Container Insights) | CodeDeploy needs alarms for rollback; data-tier failures (full disk, NAT health) caught from day one |
| 12 | Region | **us-east-1** | Cheapest, most service availability, US-East end-user latency optimal, ACM/CloudFront pinned to us-east-1 anyway |
| 13a | S3 buckets | **One bucket** with key prefixes (`avatars/`, `listing-photos/`, `dispute-evidence/`); prefix-scoped lifecycle for dispute evidence | Matches existing `StorageConfigProperties` (single `bucket` field). Avoids backend refactor. |
| 13b | Asset serving | Backend proxies photo bytes (no CloudFront initially) | Optimize when backend CPU traces show photo bytes are meaningful |
| 13c | Terraform state backend | S3 versioned + DynamoDB lock | Conventional. ~$1/mo. Bucket has deletion protection. |
| 13d | Cost guardrails | AWS Budgets at $200 (soft) / $400 (hard) | Free; can't sneak up on you |
| 13e | Backups beyond RDS PITR | S3 versioning on `dispute-evidence` + Terraform state | Audit + recovery |
| 13f | Email | None (SL IM channel forwards to email natively) | `DEFERRED_WORK.md`: email channel removed from roadmap |
| 13g | ECR scanning | Enabled (`scan_on_push = true`) | Free; surfaces high-severity CVEs |
| 13h | WAF | None at launch; documented one-line enable | $5+/mo + per-request; not justified at zero users |
| 13i | VPC layout | 1 VPC, 2 AZs (RDS subnet group requirement), 1 public + 1 private subnet per AZ | Standard layout |
| 14 | CPU architecture | **x86_64** | Matches local test platform. Not revisited. |
| 15 | Cost shape | **Launch-lite defaults baked into IaC; production-tier as upgrade levers** | Pay-as-validated; no architectural rework to upgrade |

---

## 4. Component specifications

### 4.1 Networking

**VPC**
- CIDR `10.0.0.0/16`
- Two AZs: `us-east-1a`, `us-east-1b` (RDS subnet group requires ≥2 even when single-AZ instance)
- DNS support + DNS hostnames enabled

**Subnets** (4 total, /24 each)
- Public: `10.0.0.0/24` (us-east-1a), `10.0.1.0/24` (us-east-1b) — ALB, NAT instance
- Private: `10.0.10.0/24` (us-east-1a), `10.0.11.0/24` (us-east-1b) — Fargate tasks, RDS, ElastiCache

**Internet Gateway** — attached to VPC, public subnets route 0.0.0.0/0 to it.

**NAT** — single NAT instance, `fck-nat` AMI on `t4g.nano` in `us-east-1a` public subnet. Both private subnets route 0.0.0.0/0 through this single NAT. Self-healing via ASG with `min=max=desired=1`. Documented upgrade to two NAT Gateways under §6 (upgrade levers).

**VPC endpoints**
- S3 Gateway endpoint — free; routes Fargate → S3 traffic without NAT.
- (Deferred) Interface endpoints for ECR API/Docker, Secrets Manager, CloudWatch Logs — $7/mo each per AZ. Enable when NAT egress costs justify.

**Security groups**
- `sg-alb` — inbound 80 + 443 from `0.0.0.0/0`; outbound to `sg-backend` only
- `sg-backend` — inbound 8080 from `sg-alb` only; outbound to RDS, ElastiCache, internet (via NAT)
- `sg-bots` — no inbound (private health endpoint); outbound to ALB (for backend calls), internet (via NAT) for SL grid
- `sg-rds` — inbound 5432 from `sg-backend` + `sg-bots` only
- `sg-redis` — inbound 6379 from `sg-backend` only
- `sg-nat` — inbound any from private subnets; outbound any to internet

### 4.2 Compute — Backend (ECS Fargate)

**Cluster** — `slpa-prod`. Container Insights enabled.

**Service** — `slpa-backend`
- Task definition: 0.25 vCPU, 0.5 GB memory, x86_64 Linux, ephemeral storage 20 GB (default)
- Runtime platform: `LINUX/X86_64` (matches local Docker test environment)
- `desired_count = 1` at launch (upgrade lever to 2)
- Deployment controller: **CodeDeploy** (blue/green)
- Network: private subnets, `sg-backend`, `assign_public_ip = false`
- Load balancer: ALB target group on port 8080, healthcheck `GET /api/v1/health`
- ECS Exec enabled (`enable_execute_command = true`)
- Logging: `awslogs` driver → CloudWatch Logs `/aws/ecs/slpa-backend` (7-day retention)

**Container env**
- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<rds-writer-endpoint>:5432/slpa`
- `SPRING_DATASOURCE_USERNAME` from Parameter Store `/slpa/prod/rds/username`
- `SPRING_DATASOURCE_PASSWORD` from Parameter Store `/slpa/prod/rds/password`
- `SPRING_DATA_REDIS_HOST=<elasticache-primary-endpoint>`
- `SPRING_DATA_REDIS_PORT=6379`
- `JWT_SECRET` from Parameter Store `/slpa/prod/jwt/secret`
- `CORS_ALLOWED_ORIGIN=https://slparcels.com`
- `SLPA_STORAGE_ENDPOINT=https://s3.us-east-1.amazonaws.com`
- `SLPA_STORAGE_BUCKET=slpa-prod-storage` (single bucket; the app uses key prefixes `avatars/`, `listing-photos/`, `dispute-evidence/` — see `StorageConfigProperties.java`)
- `SLPA_STORAGE_REGION=us-east-1`
- `SPRING_DATA_REDIS_SSL_ENABLED=true` (ElastiCache encryption-in-transit; see §4.6)
- `SLPA_BOT_SHARED_SECRET` from Parameter Store `/slpa/prod/bot/shared-secret`
- `SLPA_PRIMARY_ESCROW_UUID` from Parameter Store `/slpa/prod/sl/primary-escrow-uuid`
- `SLPA_SL_TRUSTED_OWNER_KEYS` from Parameter Store `/slpa/prod/sl/trusted-owner-keys` (comma-separated)
- `LOGGING_LEVEL_ROOT=WARN`
- `LOGGING_LEVEL_COM_SLPARCELAUCTIONS=INFO` (keep app logs informative; suppress noisy framework logs)
- `SERVER_FORWARD_HEADERS_STRATEGY=framework` (so backend sees real client IP through the ALB)

**IAM task role** (`slpa-backend-task-role`)
- `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket` on `arn:aws:s3:::slpa-prod-storage` and `arn:aws:s3:::slpa-prod-storage/*`
- `ssm:GetParameter`, `ssm:GetParameters`, `ssm:GetParametersByPath` on `/slpa/prod/*`
- `kms:Decrypt` on the SSM-bound KMS key
- `ssmmessages:CreateControlChannel`, `ssmmessages:CreateDataChannel`, `ssmmessages:OpenControlChannel`, `ssmmessages:OpenDataChannel` (for ECS Exec)
- `logs:CreateLogStream`, `logs:PutLogEvents` on its log group

**IAM execution role** (`slpa-backend-task-execution-role`)
- `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:GetDownloadUrlForLayer`, `ecr:BatchGetImage` on `slpa/backend` repo
- `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`
- `ssm:GetParameters` on `/slpa/prod/*` (for `secrets[]` injection)

### 4.3 Compute — Bot pool (ECS Fargate, 5 named services)

**5 separate ECS services** — `slpa-bot-1` through `slpa-bot-5`. Each has its own task definition pinning a distinct credential set. `desired_count = 1` per service (named workers can't double-run with the same SL credentials).

At launch, only `slpa-bot-1` and `slpa-bot-2` are created. `slpa-bot-3`/`4`/`5` are commented-out / `count = 0` Terraform resources, ready to enable as Method-C verification + ownership-monitoring volume justifies. Each bot adds ~$8-15/mo of Fargate cost.

**Per-service task definition**
- 0.25 vCPU, 0.5 GB memory, x86_64 Linux
- Runtime platform: `LINUX/X86_64` (matches local test platform)
- Network: private subnets, `sg-bots`, no public IP
- ECS Exec enabled
- Deployment controller: **ECS rolling** (CodeDeploy blue/green is overkill for a stateful single-task service)
- Logging: `awslogs` → `/aws/ecs/slpa-bot-N`

**Per-bot env** (different per service — each pulls its own credential set)
- `Bot__Username` from Parameter Store `/slpa/prod/bot-N/username`
- `Bot__Password` from Parameter Store `/slpa/prod/bot-N/password`
- `Bot__BotUuid` from Parameter Store `/slpa/prod/bot-N/uuid`
- `Bot__StartLocation=last`
- `Backend__BaseUrl=http://<alb-internal-dns>:8080` (use the ALB DNS rather than service-discovery — keeps the bot-to-backend path identical to dev where docker compose proxies via service name)
- `Backend__SharedSecret` from Parameter Store `/slpa/prod/bot/shared-secret` (same value across all 5 bots)
- `RateLimit__TeleportsPerMinute=6`
- `LOGGING__LOGLEVEL__DEFAULT=Warning` (.NET logging key; suppresses INFO noise)
- `LOGGING__LOGLEVEL__SLPA=Information`

**IAM task role** (`slpa-bot-task-role`, one shared across all 5 services)
- `ssm:GetParameter`, `ssm:GetParameters`, `ssm:GetParametersByPath` on `/slpa/prod/bot/*` and `/slpa/prod/bot-*/...`
- `ssmmessages:*` for ECS Exec
- `logs:*` on its log groups
- No S3 access (bots don't write to user-facing buckets)

### 4.4 Compute — Frontend (AWS Amplify Hosting)

**Amplify app** — `slpa-frontend`
- Source: GitHub repo `TheCodeLlama/slparcelauctions`, branch `main`, monorepo subdirectory `frontend/`
- Framework: Next.js (Amplify auto-detects)
- Build settings (`amplify.yml`):
  ```yaml
  version: 1
  applications:
    - frontend:
        phases:
          preBuild:
            commands:
              - npm ci
          build:
            commands:
              - npm run build
        artifacts:
          baseDirectory: .next
          files:
            - '**/*'
        cache:
          paths:
            - node_modules/**/*
            - .next/cache/**/*
      appRoot: frontend
  ```
- Environment variables:
  - `NEXT_PUBLIC_API_URL=https://slpa.app`
  - `NODE_ENV=production`
- Custom domain: `slparcels.com` + `www.slparcels.com` (Amplify auto-issues + manages ACM cert in us-east-1)
- SSR enabled (Next.js 16 server components require it; the homepage uses `Promise.allSettled` over three featured-rail fetches)
- No WAF at launch

### 4.5 Data — RDS Postgres

**Instance**
- Engine: Postgres 17.x (latest minor available at deploy time)
- Class: `db.t4g.micro` at launch (upgrade lever to `db.t4g.small` → `db.t4g.medium` → `db.m6g.large`)
- Storage: gp3, 20 GB initial, autoscaling enabled to 100 GB ceiling
- Multi-AZ: **disabled** at launch (upgrade lever)
- Encryption at rest: enabled with default AWS-managed KMS key
- Network: DB subnet group across both private subnets (required for future Multi-AZ flip), `sg-rds`
- Public accessibility: **disabled**
- Backup retention: 7 days (PITR), backup window 04:00-05:00 UTC
- Maintenance window: Sun 06:00-07:00 UTC (low-traffic for SLPA's likely user base)
- Performance Insights: **disabled** at launch (free 7d tier available; enable at moderate-tier upgrade)
- Database Insights (CloudWatch): disabled at launch (enable at moderate tier)
- Deletion protection: **enabled**
- Final snapshot identifier on destroy: `slpa-prod-final-{timestamp}`

**Database** — `slpa` (created by Terraform via `aws_db_instance.db_name`)

**Master credentials** — generated by Terraform (`random_password`), stored in Parameter Store as `SecureString`. Application reads from `/slpa/prod/rds/username` and `/slpa/prod/rds/password`.

**Pre-deploy snapshot** (CI step) — every successful CodeDeploy run for the backend triggers `aws rds create-db-snapshot --db-snapshot-identifier slpa-prod-predeploy-{git-sha}` *before* the new task starts. Free; gives a known-good rollback point even if PITR is also on.

### 4.6 Data — ElastiCache Redis

**Replication group** — `slpa-redis-prod` (single-node at launch)
- Engine: Redis 7.x
- Node type: `cache.t4g.micro` at launch (upgrade lever)
- Number of nodes: 1 at launch (upgrade lever to 2 with replication)
- Multi-AZ: **disabled** at launch (requires ≥2 nodes; upgrade lever)
- Automatic failover: **disabled** at launch (requires Multi-AZ; upgrade lever)
- Network: subnet group across both private subnets, `sg-redis`
- Encryption at rest: enabled
- Encryption in transit: enabled (TLS). **`application-prod.yml` must be updated** to set `spring.data.redis.ssl.enabled: true` — the existing config does not set this and will fail to connect to encrypted ElastiCache. Action item in §8 pre-launch checklist.
- Auth token: enabled, stored in Parameter Store `/slpa/prod/redis/auth-token`
- Snapshot retention: 5 days
- Snapshot window: 03:00-04:00 UTC
- Maintenance window: Sun 05:00-06:00 UTC

### 4.7 Storage — S3

The backend uses **a single bucket with key prefixes**, not multiple buckets. `StorageConfigProperties` (record at `backend/src/main/java/com/slparcelauctions/backend/storage/StorageConfigProperties.java`) has a single `bucket` field and the existing code writes objects under prefixes `avatars/`, `listing-photos/`, and `dispute-evidence/` within that one bucket. The IaC mirrors this shape.

**Buckets** (both in `us-east-1`, both with public-access-blocked, TLS-only access policy, SSE-S3 encryption):

| Bucket | Versioning | Lifecycle |
|---|---|---|
| `slpa-prod-storage` | **on** (required for `dispute-evidence/` prefix recovery; storage cost is small at SLPA scale) | Two prefix-scoped rules:<br>• `dispute-evidence/` → transition to Glacier Deep Archive at 365 days, expire at 7 years<br>• Non-current versions across all prefixes → delete after 90 days |
| `slpa-prod-tfstate` | **on** | none; **deletion protection** via bucket policy |

**Public access** — both buckets block all public access. Backend reads/writes via IAM role; no presigned URLs at launch (the backend proxies bytes through `GET /api/v1/auctions/{id}/photos/{photoId}/bytes`).

**Bucket-policy snippets** (TLS deny):
```json
{
  "Sid": "DenyInsecureTransport",
  "Effect": "Deny",
  "Principal": "*",
  "Action": "s3:*",
  "Resource": ["arn:aws:s3:::<bucket>", "arn:aws:s3:::<bucket>/*"],
  "Condition": { "Bool": { "aws:SecureTransport": "false" } }
}
```

**S3 Gateway VPC Endpoint** — attached to both private subnets, free. Routes all Fargate→S3 traffic without traversing NAT.

### 4.8 Secrets — Parameter Store (Standard tier)

**Parameter naming convention** — `/slpa/prod/<service>/<key>`. Examples:

| Path | Type | Source |
|---|---|---|
| `/slpa/prod/rds/username` | SecureString | Terraform-generated |
| `/slpa/prod/rds/password` | SecureString | Terraform-generated (`random_password`) |
| `/slpa/prod/redis/auth-token` | SecureString | Terraform-generated |
| `/slpa/prod/jwt/secret` | SecureString | Generated once via `openssl rand -base64 64` and pasted into Terraform tfvars (NOT committed) |
| `/slpa/prod/bot/shared-secret` | SecureString | Generated via `openssl rand -hex 32` |
| `/slpa/prod/sl/primary-escrow-uuid` | String | Real `SLPAEscrow Resident` UUID |
| `/slpa/prod/sl/trusted-owner-keys` | StringList | Comma-separated list of trusted SL owner UUIDs |
| `/slpa/prod/bot-1/username` | SecureString | `SLPABot1 Resident` |
| `/slpa/prod/bot-1/password` | SecureString | bot-1's SL account password |
| `/slpa/prod/bot-1/uuid` | String | bot-1's SL avatar UUID |
| `/slpa/prod/bot-2/...` through `/slpa/prod/bot-5/...` | same shape | per-bot |

**Bootstrap process** — see §6 (first-deploy runbook). Sensitive values (SL passwords, escrow UUID) are entered via `aws ssm put-parameter` outside Terraform — they are not committed to repo or `tfvars`. Terraform creates the *parameter resource* but reads its value via `data` source, so the value can be set/rotated outside of `terraform apply`.

**KMS encryption** — all SecureStrings use the default AWS-managed `alias/aws/ssm` key. Customer-managed KMS key is a documented upgrade lever (~$1/month per CMK + per-API-call charges).

**Upgrade lever — Secrets Manager for RDS rotation** — when worth ~$0.40/month per secret, flip the RDS password parameter to a Secrets Manager secret with rotation Lambda. One-line per-secret change in IaC.

### 4.9 DNS + TLS

**Route 53 hosted zones** (Namecheap NS-delegated):
- `slparcels.com`
- `slpa.app`

**Records:**

| FQDN | Type | Target |
|---|---|---|
| `slparcels.com` | ALIAS | Amplify distribution |
| `www.slparcels.com` | ALIAS | Amplify distribution |
| `slpa.app` | ALIAS | ALB DNS name (apex ALIAS supported on `.app` zones) |

**`slparcelauctions.com`** — stays on Namecheap DNS entirely. Configured at Namecheap with **URL Redirect Record** (Permanent / 301), `@` and `www` both → `https://slparcels.com`. No AWS resources.

**ACM certificates:**
- Wildcard `*.slpa.app` + apex `slpa.app`, issued in `us-east-1`, attached to ALB
- `slparcels.com` + `www.slparcels.com`, issued automatically by Amplify in `us-east-1` (Amplify-managed)
- DNS validation for both (Terraform creates the validation CNAMEs)

**Note** — `bots.slpa.app` is intentionally not a record. Bot health checks use ECS Exec; no public DNS surface.

### 4.10 CI/CD pipeline

**GitHub Actions workflows:**

#### `.github/workflows/deploy-backend.yml`
- **Trigger:** push to `main` with changes under `backend/**`
- **Jobs:**
  1. Build Spring Boot fat-jar (`./mvnw clean package -DskipTests`)
  2. Run tests (`./mvnw test`) — failure aborts deploy
  3. Build Docker image (linux/amd64) tagged with git SHA
  4. Authenticate to AWS via OIDC role (`arn:aws:iam::<account>:role/github-actions-deploy`)
  5. Push image to ECR `slpa/backend:<sha>` and `slpa/backend:latest`
  6. **Pre-deploy RDS snapshot:** `aws rds create-db-snapshot --db-snapshot-identifier slpa-prod-predeploy-<sha>`
  7. Update task definition with new image tag (Terraform-managed task def template + `aws ecs register-task-definition`)
  8. Trigger CodeDeploy: `aws deploy create-deployment --application-name slpa-backend --deployment-group-name prod --revision <task-def-arn>`
  9. Wait for deployment to complete (or fail) — exits with deployment status

#### `.github/workflows/deploy-bot.yml`
- **Trigger:** push to `main` with changes under `bot/**`
- **Jobs:** similar shape, but builds `slpa/bot` image and updates each of `slpa-bot-1` through `slpa-bot-5` (or only the active subset) via `aws ecs update-service --force-new-deployment`. No CodeDeploy (rolling deploy is fine for stateful single-task services).

#### `.github/workflows/terraform.yml`
- **Trigger:** `workflow_dispatch` (manual only). NEVER auto-applied on code push.
- **Jobs:**
  1. `terraform fmt -check`, `terraform validate`
  2. `terraform plan -out=tfplan` — output posted as PR comment for review
  3. (Manual approval gate) `terraform apply tfplan`

#### Frontend deployment
- Amplify watches `main` branch directly (configured in Amplify console / Terraform). Push to `main` with changes under `frontend/**` triggers a new build + deploy. No GitHub Actions involvement.

**ECR**
- One repo per service: `slpa/backend`, `slpa/bot`
- Image scanning: `scan_on_push = true` (free, surfaces high-severity CVEs)
- Lifecycle policy: keep last 10 images, expire older ones
- Storage cost ~$2-5/mo at SLPA's image churn

**OIDC trust** — IAM role `github-actions-deploy` trusts the GitHub OIDC provider, scoped to the `TheCodeLlama/slparcelauctions` repo's `main` branch. No long-lived access keys in GitHub Secrets. Permissions: ECR push, ECS update-service, RDS create-snapshot, CodeDeploy create-deployment.

**CodeDeploy (backend only)**
- Deployment type: **blue/green**
- Traffic shifting: `CodeDeployDefault.ECSAllAtOnce` at launch (simpler; no canary). Upgrade lever to `CodeDeployDefault.ECSCanary10Percent5Minutes` when traffic justifies caution.
- Rollback alarms: backend 5xx rate > 5% (1 min), p95 latency > 2s (5 min), unhealthy host count ≥ 1 (1 min). Any one alarm fires → automatic rollback to blue.

### 4.11 Database migrations — Flyway (in scope for this work)

**Why** — `DEFERRED_WORK.md` flagged `Auction.title NOT NULL backfill on first production deploy` and similar items. Hibernate `ddl-auto: update` cannot reliably handle NOT-NULL column additions on existing tables in prod. `AuctionTitleDevTouchUp` and `MaturityRatingDevTouchUp` exist as dev-side workarounds that don't translate to prod.

**Decision** — convert dev + prod to Flyway in the same change. Dev/prod schema drift is the worse failure mode.

**Implementation steps** (executed during this deployment work, not at first prod deploy):

1. **Add Flyway dependency** to `backend/pom.xml`:
   ```xml
   <dependency>
     <groupId>org.flywaydb</groupId>
     <artifactId>flyway-core</artifactId>
   </dependency>
   ```
2. **Generate baseline migration** — boot the backend against an empty Postgres with `ddl-auto: create`, capture the resulting schema with `pg_dump --schema-only`, save as `backend/src/main/resources/db/migration/V1__initial_schema.sql`.
3. **Update `application.yml`:**
   ```yaml
   spring:
     jpa:
       hibernate:
         ddl-auto: validate  # was: update (dev/default)
     flyway:
       enabled: true
       locations: classpath:db/migration
   ```
4. **Update `application-prod.yml`:** confirm `spring.flyway.baseline-on-migrate: true` is present (already there per current README).
5. **Remove `AuctionTitleDevTouchUp` and `MaturityRatingDevTouchUp`** — their work is done by `V1__initial_schema.sql` columns. Separate commit, flagged in PR description.
6. **Update `CONVENTIONS.md`** — replace "schema changes via JPA `ddl-auto`" with "schema changes via new `db/migration/V<N>__description.sql`."
7. **Test locally** — drop the docker-compose Postgres volume, bring backend up, confirm Flyway runs and the schema is identical to pre-conversion.
8. **CI step** — no change needed. Spring Boot runs Flyway at startup; migrations apply before the app accepts requests.

**Risk mitigation for first prod deploy:**
- Pre-deploy RDS snapshot (already in CI per §4.5)
- The first deploy applies `V1__initial_schema.sql` against the empty prod DB — straightforward, no baselining drama
- `baseline-on-migrate: true` is the safety net if a prod DB were ever populated outside Flyway (not relevant for first deploy, but kept on for future runs)

**Rollback** — if migration fails, app refuses to start (`ddl-auto: validate` enforces). CodeDeploy alarm-based rollback returns to old task def, which still uses old image with old expected schema. Operator: restore RDS from the pre-deploy snapshot, fix the migration, redeploy.

### 4.12 Observability

**Container Insights** — enabled on `slpa-prod` cluster. Adds per-task CPU/memory/network/disk metrics + log/metric correlation. ~$3-5/mo flat.

**CloudWatch alarms** (12 standard-resolution, 60s):

| Alarm | Threshold | Used by CodeDeploy? |
|---|---|---|
| `slpa-backend-5xx-rate` | > 5% over 1 min | Yes (rollback) |
| `slpa-backend-p95-latency` | > 2s over 5 min | Yes |
| `slpa-backend-unhealthy-hosts` | ≥ 1 over 1 min | Yes |
| `slpa-backend-ecs-task-failures` | ≥ 1 in 5 min | No (alert only) |
| `slpa-rds-cpu` | > 80% over 5 min | No |
| `slpa-rds-free-storage-low` | < 20% capacity | No |
| `slpa-rds-connections-high` | > 80% of max | No |
| `slpa-redis-cpu` | > 80% over 5 min | No |
| `slpa-redis-memory-pressure` | < 10% free | No |
| `slpa-nat-instance-cpu` | > 80% over 5 min | No |
| `slpa-bot-pool-task-running` | running ≠ desired (any bot service) | No |
| `slpa-budget-soft` | (AWS Budgets) | No |

**SNS topic** — `slpa-prod-alerts`, subscribed to operator email. All alarms publish to this topic.

**Dashboards** — one CloudWatch dashboard at launch (`slpa-prod-overview`) showing: backend request rate / latency / 5xx, RDS CPU / connections / free storage, ElastiCache CPU / memory, bot pool task counts, NAT instance CPU.

**Logs**
- Log groups: `/aws/ecs/slpa-backend`, `/aws/ecs/slpa-bot-1` ... `/aws/ecs/slpa-bot-5`, `/aws/rds/instance/slpa/postgresql`
- Retention: 7 days (set on every group at creation; default is "never expire")
- No Infrequent Access tier
- No S3 export

**X-Ray traces, Synthetics canaries, AWS WAF** — out of scope for launch. Documented as one-line IaC enables.

### 4.13 Cost guardrails

**AWS Budgets:**
- `slpa-prod-soft-budget` — $200/mo, alert at 100% actual spend → SNS topic → email
- `slpa-prod-hard-budget` — $400/mo, alert at 100% actual + at 80% forecasted → SNS topic → email

Budgets are free; no infra cost.

### 4.14 Backup + DR

| Asset | Backup mechanism | Retention | RTO target |
|---|---|---|---|
| RDS Postgres | PITR + automatic snapshots | 7 days | < 30 min restore from snapshot |
| RDS Postgres | Pre-deploy manual snapshot (CI) | indefinite (until manually pruned) | < 30 min |
| ElastiCache Redis | Automatic snapshots | 5 days | session/cache state is rebuildable; no recovery target |
| S3 `dispute-evidence` | Bucket versioning + 7-year lifecycle to Glacier | 7 years | minutes (S3 read) |
| S3 `tfstate` | Bucket versioning | indefinite | minutes |
| Other S3 buckets | None | n/a | accept loss of avatars/photos in catastrophic case (re-uploadable by users) |
| GitHub repo | GitHub native redundancy | n/a | n/a |
| Terraform state | S3 versioned + DynamoDB lock | indefinite | minutes |

**No AWS Backup service** at launch. RDS PITR + S3 versioning cover the cases that matter.

**No cross-region DR.** Out of scope per non-goals.

---

## 5. Launch-lite vs. production-tier upgrade levers

Every "upgrade" below is a Terraform variable change in a `.tfvars` file followed by `terraform apply`. No architectural rework.

| Lever | Variable | Launch-lite | Production-tier | Cost delta |
|---|---|---|---|---|
| RDS Multi-AZ | `rds_multi_az` | `false` | `true` | +$30/mo |
| RDS instance class | `rds_instance_class` | `db.t4g.micro` | `db.t4g.small` → `medium` → `m6g.large` | +$15-150/mo |
| RDS storage | `rds_allocated_storage` | 20 GB | 50 → 100 → 250 GB | +$3-30/mo |
| ElastiCache replication | `redis_num_cache_clusters` | 1 | 2 (replication) | +$12-30/mo |
| ElastiCache instance class | `redis_node_type` | `cache.t4g.micro` | `cache.t4g.small` → `medium` | +$10-40/mo |
| Backend replicas | `backend_desired_count` | 1 | 2+ | +$8-30/mo |
| Backend task size | `backend_cpu` / `backend_memory` | 0.25 / 0.5 | 0.5/1 → 1/2 → 2/4 | +$10-100/mo |
| Bot pool active count | `bot_active_count` | 1-2 | 5 | +$25/mo |
| Bot task size | `bot_cpu` / `bot_memory` | 0.25 / 0.5 | 0.5/1 | +$25/mo |
| NAT shape | `nat_type` | `instance` | `gateway` (×2 for HA) | +$60/mo |
| VPC interface endpoints | `enable_interface_endpoints` | `false` | `true` (ECR + Secrets + CW) | +$42-56/mo |
| Database Insights | `rds_database_insights` | `false` | `true` (Standard) → `true` (Advanced) | +$17-50/mo |
| Performance Insights retention | `rds_pi_retention_days` | 7 (free) | 731 (24mo, paid) | +$5-15/mo |
| RDS Proxy | `enable_rds_proxy` | `false` | `true` | +$15/mo + per-vCPU |
| Container Insights | `cluster_container_insights` | `enabled` | `enabled` | (already on; n/a) |
| CloudWatch log retention | `log_retention_days` | 7 | 30 → 90 | +$10-50/mo |
| WAF on ALB | `enable_waf` | `false` | `true` | +$5-30/mo |
| X-Ray traces | `enable_xray` | `false` | `true` (5% sampling) | +$1-10/mo |
| CodeDeploy traffic shift | `codedeploy_config` | `ECSAllAtOnce` | `ECSCanary10Percent5Minutes` | $0 |
| RDS password rotation | (per-secret flag) | Parameter Store | Secrets Manager + rotation Lambda | +$0.40/mo per secret |
| Frontend WAF | (Amplify-side) | none | enabled | +$5+/mo |

**Rule of thumb** — flip levers when CloudWatch alarms or your AWS Budgets soft alert tells you the launch-lite default is no longer acceptable. Don't pre-upgrade.

---

## 6. Terraform module structure

```
infra/
├── main.tf                  # provider, backend (S3 + DynamoDB)
├── variables.tf             # all upgrade-lever variables, defaults set to launch-lite
├── outputs.tf               # ALB DNS name, RDS endpoint, ECS cluster ARN, etc.
├── terraform.tfvars         # gitignored; per-deployment overrides
├── terraform.tfvars.example # committed; placeholder values
│
├── networking/
│   ├── vpc.tf               # VPC, subnets, IGW, route tables
│   ├── nat.tf               # NAT instance (or gateway when nat_type=gateway)
│   ├── endpoints.tf         # S3 gateway endpoint; conditional interface endpoints
│   └── security_groups.tf
│
├── data/
│   ├── rds.tf               # Postgres instance, parameter group, subnet group
│   ├── elasticache.tf       # Redis replication group
│   └── s3.tf                # 4 buckets + lifecycle + bucket policies
│
├── compute/
│   ├── ecs_cluster.tf       # cluster + capacity providers (Fargate only)
│   ├── ecs_backend.tf       # task def, service, ALB target group
│   ├── ecs_bots.tf          # for_each over [bot-1...bot-N] services
│   └── ecr.tf               # backend + bot repos with lifecycle policies
│
├── frontend/
│   └── amplify.tf           # Amplify app, branch, custom domain
│
├── secrets/
│   └── parameter_store.tf   # all /slpa/prod/* parameters (created by Terraform but values set out-of-band where sensitive)
│
├── dns/
│   ├── route53.tf           # 2 hosted zones, records
│   └── acm.tf               # certs + DNS validation
│
├── cicd/
│   ├── codedeploy.tf        # backend blue/green app + deployment group
│   ├── github_oidc.tf       # OIDC provider + deploy role
│   └── codedeploy_alarms.tf # the 3 rollback-driving alarms
│
├── observability/
│   ├── cloudwatch_alarms.tf # 12 alarms
│   ├── cloudwatch_dashboard.tf
│   ├── log_groups.tf        # explicit log groups with 7d retention
│   └── sns.tf               # alerts topic + subscription
│
└── budgets/
    └── budgets.tf           # soft + hard
```

Modules should be **flat with topic dirs**, not deeply nested module hierarchies. SLPA's infra is small enough that one `terraform apply` should build the whole stack.

---

## 7. First-deploy runbook

Sequential steps. Each is an actionable command or a Terraform variable change.

### 7.1 AWS account preparation (one-time, manual)

1. Create AWS account (or use existing).
2. Enable AWS IAM Identity Center (formerly SSO) for human access. Create an admin user; never use the root account for routine work.
3. Create OIDC provider for GitHub: `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`, thumbprint per AWS docs.
4. Manually create the Terraform state backend resources (chicken-and-egg — the backend can't create itself):
   - S3 bucket `slpa-prod-tfstate` with versioning enabled, server-side encryption, public-access-blocked
   - DynamoDB table `slpa-prod-tfstate-lock` with primary key `LockID` (String)
5. Enable AWS Cost Explorer (free; required for Budgets to work).

### 7.2 Repo prep

1. `git checkout -b feat/aws-deployment` from `dev`
2. Copy `terraform.tfvars.example` → `terraform.tfvars`, populate non-sensitive values (region, account ID, domain names)
3. Sensitive values stay out of `tfvars` — see step 7.3

### 7.3 Bootstrap secrets out-of-band

Sensitive values are entered via `aws ssm put-parameter` *before* the first `terraform apply`. Terraform's `aws_ssm_parameter` resources reference these via `data` source so the values can be set/rotated outside Terraform.

```bash
# JWT signing key
JWT_SECRET=$(openssl rand -base64 64)
aws ssm put-parameter \
  --name /slpa/prod/jwt/secret \
  --type SecureString \
  --value "$JWT_SECRET"

# Bot shared secret
BOT_SECRET=$(openssl rand -hex 32)
aws ssm put-parameter \
  --name /slpa/prod/bot/shared-secret \
  --type SecureString \
  --value "$BOT_SECRET"

# Real SLPAEscrow Resident UUID
aws ssm put-parameter \
  --name /slpa/prod/sl/primary-escrow-uuid \
  --type String \
  --value "<real-UUID>"

# Trusted SL owner UUIDs (the owners of the in-world LSL terminals)
aws ssm put-parameter \
  --name /slpa/prod/sl/trusted-owner-keys \
  --type StringList \
  --value "<uuid-1>,<uuid-2>,<uuid-3>"

# Per-bot credentials (repeat for bot-2 through bot-5)
aws ssm put-parameter --name /slpa/prod/bot-1/username --type SecureString --value "SLPABot1 Resident"
aws ssm put-parameter --name /slpa/prod/bot-1/password --type SecureString --value "<bot-1-password>"
aws ssm put-parameter --name /slpa/prod/bot-1/uuid --type String --value "<bot-1-uuid>"
```

RDS password and Redis auth token are generated **by Terraform** (`random_password`) and stored in Parameter Store automatically — no manual step.

### 7.4 First Terraform apply

```bash
cd infra
terraform init    # configures S3 backend
terraform plan    # review every resource creation
terraform apply   # ~15-25 min for the first apply (RDS Multi-AZ creation is the slow step; fast in launch-lite single-AZ)
```

Expected resources at completion: VPC + 4 subnets + IGW + NAT instance + 5 security groups + ALB + RDS instance + ElastiCache replication group + ECS cluster + ECR repos + Amplify app + Route 53 zones + ACM certs + Parameter Store entries + CodeDeploy app + CloudWatch alarms + SNS topic + budgets.

**Note:** ECS services `slpa-backend` and `slpa-bot-1` (and `slpa-bot-2` if active) will be created with `desired_count = 0` initially because no image is in ECR yet. CodeDeploy needs an image to deploy.

### 7.5 Namecheap DNS delegation (one-time)

After Terraform creates the Route 53 hosted zones, copy the 4 nameservers per zone:

```bash
aws route53 get-hosted-zone --id <zone-id> --query 'DelegationSet.NameServers'
```

In Namecheap:
- For `slparcels.com` and `slpa.app`: change "Custom DNS" to the 4 Route 53 nameservers. Wait 5-30 min for NS propagation (`dig NS slparcels.com`).
- For `slparcelauctions.com`: keep on Namecheap DNS. Add a "URL Redirect Record" for `@` and `www`, type **Permanent (301)**, target `https://slparcels.com`.

### 7.6 Database baseline

The first backend deploy will run `V1__initial_schema.sql` against the empty `slpa` database. No manual step required — Spring Boot's Flyway integration runs at startup before the app accepts requests. `baseline-on-migrate: true` handles the case where the DB has any leftover objects (irrelevant for a fresh DB but defensive for re-deploys).

### 7.7 First image push + backend deploy

```bash
# Locally, from repo root
cd backend
./mvnw clean package -DskipTests
docker build -t slpa-backend:initial .
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com
docker tag slpa-backend:initial <account>.dkr.ecr.us-east-1.amazonaws.com/slpa/backend:initial
docker push <account>.dkr.ecr.us-east-1.amazonaws.com/slpa/backend:initial
```

Bump `backend_image_tag` variable in `terraform.tfvars` to `initial`, `terraform apply`. Backend service scales to `desired_count = 1`, pulls image, runs Flyway, healthchecks pass, ALB target becomes healthy. ~3-5 min.

Verify: `curl https://slpa.app/api/v1/health` → 200.

### 7.8 First bot deploy

Same pattern as backend, but for the bot image and one or both of `slpa-bot-1` / `slpa-bot-2`. After apply, bots log into SL within ~10s and start polling `https://slpa.app/api/v1/bot/tasks/claim`. Verify via CloudWatch Logs `/aws/ecs/slpa-bot-1` for `ONLINE as SLPABot1`.

### 7.9 Frontend deploy

Amplify watches `main`. Push the deployment branch through PR → `main` merge. Amplify builds (~5-8 min), deploys to its CloudFront distribution. Custom domain (`slparcels.com` + `www.slparcels.com`) takes ~2-5 min for ACM cert validation + DNS propagation after Amplify finishes.

Verify: `curl -I https://slparcels.com` → 200, content from Amplify.

### 7.10 Smoke test

End-to-end checks against prod:

1. `curl https://slpa.app/api/v1/health` → 200
2. `curl -I https://slparcels.com` → 200
3. `curl -i -X POST https://slpa.app/api/v1/auth/register -H 'Content-Type: application/json' -d '{"email":"smoke-test@example.com","password":"...","displayName":"Smoke"}'` → 201
4. `aws ecs execute-command --cluster slpa-prod --task <bot-1-task-id> --container bot --interactive --command "curl -s http://localhost:8081/health"` → `{"state":"Online"}`
5. CloudWatch dashboard `slpa-prod-overview` shows live metrics
6. Trigger a Postman collection `Auth/` folder run against `https://slpa.app` — every variable-chained request should pass

Smoke test complete. Document any deviations.

### 7.11 Switch GitHub Actions to deploying to prod

Update `.github/workflows/deploy-backend.yml` and `deploy-bot.yml` triggers from manual `workflow_dispatch` to `push: branches: [main]`. From this point, every merge to `main` deploys.

---

## 8. Pre-launch checklist

Consolidated from `DEFERRED_WORK.md` ops items + new items from this design.

| Item | Owner | Status |
|---|---|---|
| `slpa.sl.trusted-owner-keys` populated with real UUIDs | ops | parametrised via `/slpa/prod/sl/trusted-owner-keys` |
| `slpa.bot-task.primary-escrow-uuid` populated with real `SLPAEscrow Resident` UUID | ops | parametrised via `/slpa/prod/sl/primary-escrow-uuid` |
| `slpa.bot.shared-secret` rotated to non-placeholder, ≥16 chars | ops | generated via `openssl rand -hex 32`, stored in `/slpa/prod/bot/shared-secret` |
| `JWT_SECRET` set to non-placeholder | ops | generated via `openssl rand -base64 64`, stored in `/slpa/prod/jwt/secret` |
| `Auction.title NOT NULL` backfill handled by Flyway, not `AuctionTitleDevTouchUp` | dev | covered by §4.11 Flyway baseline |
| Same for `MaturityRatingDevTouchUp` | dev | covered by §4.11 |
| Backend `application-prod.yml` reviewed for any remaining placeholders | dev | one-time spec follow-up before first deploy |
| **`spring.data.redis.ssl.enabled: true` added to `application-prod.yml`** (ElastiCache TLS — backend cannot connect to encrypted Redis without this) | dev | new action item from this spec |
| `CORS_ALLOWED_ORIGIN` set to `https://slparcels.com` (not `localhost:3000`) | dev | parametrised via Terraform |
| Bot `.env.bot-N` files NOT used in prod (creds come from Parameter Store) | dev | covered by §4.3 |
| Postman collection `SLPA Dev` env updated with prod URLs (or new `SLPA Prod` env created) | dev | one-time post-deploy task |
| AWS billing alerts configured | ops | covered by §4.13 Budgets |
| Operator email subscribed to SNS `slpa-prod-alerts` topic | ops | one-time post-apply step |
| Postman collection mirrors every endpoint (per CLAUDE.md rule) | dev | continuous; verify before launch |
| README "Production deployment" section replaced with reference to this spec + `AWS_CALCULATION.md` | dev | one-time doc update |

---

## 9. Out of scope / deferred

- **Multi-region deployment** — single region (`us-east-1`) at launch.
- **Staging environment** — local docker-compose covers dev needs; staging is a documented future addition.
- **EKS migration** — Fargate is the chosen primitive.
- **Email channel for notifications** — `DEFERRED_WORK.md`: removed from roadmap.
- **WAF on ALB** — documented one-line enable; not justified at zero users.
- **AWS X-Ray** — documented enable; out of scope for launch.
- **CloudWatch Synthetics canaries** — out of scope; nothing to alert about at zero users.
- **AWS Backup service** — RDS PITR + S3 versioning suffice at launch.
- **Customer-managed KMS keys** — AWS-managed keys at launch.
- **VPC interface endpoints** (ECR / Secrets Manager / CloudWatch) — documented upgrade lever.
- **CloudFront in front of S3 photo proxy** — backend proxies for now; CloudFront when CPU traces justify.
- **`bots.slpa.app` public DNS** — bot health via ECS Exec only.
- **AWS GuardDuty / Security Hub / Inspector** — not in launch scope; consider for second-half-of-year ops review.
- **CodeDeploy canary traffic shifting** — `ECSAllAtOnce` at launch; canary is a one-config-line upgrade.
- **Reserved Instances or Savings Plans** — only worth committing once traffic shape is predictable.
- **Per-worker bot auth tokens** (`bot_workers` table) — `DEFERRED_WORK.md` indefinite item; single shared bearer secret in Parameter Store remains.
- **HMAC-SHA256 per-request bot/terminal auth** — `DEFERRED_WORK.md` Phase 2 item; bearer tokens at launch.

---

## 10. Open questions

All three questions originally listed here have been resolved by code-grep during spec review:

1. **`SLPA_STORAGE_BUCKET` env var shape** — RESOLVED. `StorageConfigProperties.java:12-17` is a single-bucket record (`bucket` field). §4.7 updated to a single bucket with key prefixes.
2. **`server.forward-headers-strategy`** — RESOLVED. `application.yml:9` already sets `forward-headers-strategy: framework`. ALB integration works as-is.
3. **Spring Boot Redis client TLS config** — RESOLVED (action item, not unknown). `application-prod.yml` does not set `spring.data.redis.ssl.enabled` today. Added to §8 pre-launch checklist.

No remaining open questions at design lock.

---

## 11. Cost estimate

See [`AWS_CALCULATION.md`](../../../AWS_CALCULATION.md) for tiered inputs and totals.

Summary (launch-lite, x86_64 Fargate):

| Tier | Auctions/month | Estimated monthly |
|---|---|---|
| Initial release | 0 | $125-195 |
| Light | 100 | $215-300 |
| Moderate | 500 | $475-685 |
| Heavy | 2,500 | $1,260-2,100 |

Bandwidth and CloudWatch log ingestion are the two line items most likely to surprise; AWS Budgets soft alarm at $200/mo will catch either before they get out of hand.

---

## 12. References

- [`AWS_CALCULATION.md`](../../../AWS_CALCULATION.md) — pricing calculator inputs
- [`README.md`](../../../README.md) — current "Production deployment" placeholder section (to be replaced)
- [`docs/implementation/DEFERRED_WORK.md`](../../implementation/DEFERRED_WORK.md) — pre-launch ops checklist source items
- [`docs/implementation/CONVENTIONS.md`](../../implementation/CONVENTIONS.md) — to be updated for Flyway migration workflow
- [`bot/README.md`](../../../bot/README.md) — bot worker env-var contract (`Bot__*`, `Backend__*` keys)
- [`docker-compose.yml`](../../../docker-compose.yml) — dev parity reference for env-var shape
