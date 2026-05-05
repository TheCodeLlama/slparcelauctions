# SLParcels AWS Deployment — Pre-flight Setup

Everything you need to do **before** I can start writing Terraform or touching AWS. Follow top-to-bottom. Each step says exactly what to click, type, or run, and what to expect when it works.

When you finish, ping me and I'll start on Step 1 of the implementation flow (Flyway backend changes — see `docs/superpowers/specs/2026-04-29-aws-deployment-design.md` §7).

---

## What this document covers

| Step | What                                              | Approx. time | Cost                                                           |
|------|---------------------------------------------------|--------------|----------------------------------------------------------------|
| 1    | AWS account ready (or create one)                 | 5-15 min     | $0 (account creation is free)                                  |
| 2    | Enable AWS IAM Identity Center                    | 5 min        | $0                                                             |
| 3    | Note your SSO Start URL + region                  | 1 min        | $0                                                             |
| 4    | Create an admin user (the one you'll use)         | 5 min        | $0                                                             |
| 5    | Install AWS CLI v2 on Windows                     | 5 min        | $0                                                             |
| 6    | Configure the CLI via SSO                         | 5 min        | $0                                                             |
| 7    | Verify access                                     | 1 min        | $0                                                             |
| 8    | Enable AWS Cost Explorer                          | 1 min        | $0 (console enable is free; Cost Explorer queries are pennies) |
| 9    | Bootstrap Terraform state backend (S3 + DynamoDB) | 5 min        | < $1/mo                                                        |
| 10   | Set up GitHub Actions OIDC provider               | 5 min        | $0                                                             |
| 11   | Confirm checklist — ready to go                   | 1 min        | n/a                                                            |

**Total time:** ~40-50 min if AWS account already exists, ~60 min if you're creating one.
**Total recurring cost from this prep alone:** under $1/mo (the Terraform state S3 bucket + DynamoDB lock table).

---

## Step 1 — AWS account ready

**If you already have an AWS account you'll use for SLParcels:** skip to Step 2. Make sure a payment method is on file (the launch-lite SLParcels bill will be ~$130-180/mo).

**If you need to create one:**

1. Go to https://aws.amazon.com/free/
2. Click "Create an AWS Account" (top right).
3. Email + password + AWS account name. Use a real email you check; AWS sends bills + alerts here.
4. Account type: **Personal** (or Business, doesn't materially matter for SLParcels).
5. Add a credit card. AWS pre-authorizes $1 to verify; refunded immediately.
6. Phone verification (SMS or call).
7. Pick the **Basic Support plan** (free).
8. Wait ~5 min for activation. You'll get a confirmation email.

When done, note your **12-digit AWS Account ID**. Find it in the top-right dropdown after signing in (`Account` menu → number under your account name). Looks like `123456789012`.

**Write this down — I'll need it later:**

```
AWS Account ID: ____________
```

---

## Step 2 — Enable AWS IAM Identity Center

This is AWS's modern SSO service (formerly "AWS SSO"). You'll use it to log in via browser + get short-lived CLI credentials.

**Why IAM Identity Center instead of IAM users with access keys?**
- No long-lived secrets to leak
- Browser-based login flow (works with MFA cleanly)
- CLI credentials auto-refresh, expire after ~12h
- Modern AWS-recommended pattern

1. Sign in to the AWS Console as the **root user** (the email you used in Step 1). For this one-time setup; we'll switch off root immediately after.
2. Top-left search bar: search **"IAM Identity Center"** and click it.
3. If you see "Enable" — click it. AWS asks where to enable:
   - **Region:** choose **`US East (N. Virginia) — us-east-1`** to match the SLParcels stack region.
   - Click **Enable**. Takes ~30 seconds.
4. If Identity Center is already enabled in another region, you'll need to disable + re-enable in `us-east-1`, OR accept the cross-region setup. Cleanest is to enable directly in `us-east-1` from the start.

When done, you should see the Identity Center dashboard with a left nav including **Settings**, **Users**, **Groups**, **Permission sets**.

---

## Step 3 — Note your SSO Start URL + region

This is the URL the CLI will open in your browser when you `aws sso login`.

1. Still in the IAM Identity Center console.
2. Click **Settings** in the left nav.
3. Look at the right side under **Identity source**. You'll see two values worth copying:

   - **AWS access portal URL** — looks like `https://d-1234567890.awsapps.com/start` (a `d-` prefix followed by 10 digits). This is what AWS calls the "SSO Start URL" in CLI configuration.
   - **Region** — should say `us-east-1`. This is your "SSO region."

4. (Optional but recommended) Customize the URL alias. Click **Customize** next to the access portal URL. Pick something memorable like `slpa` so the URL becomes `https://slpa.awsapps.com/start`. Saves you remembering the random `d-` prefix.

**Write these down:**

```
SSO Start URL: ____________________________
SSO Region:    us-east-1
```

---

## Step 4 — Create an admin user (the one you'll use)

You'll use this user via SSO from the CLI. I'll run AWS commands through your local CLI session, so this is also "the user Claude operates as" — there is no separate Claude account in AWS.

1. In IAM Identity Center, left nav: **Users**.
2. Click **Add user** (top right).
3. Fill in:
   - **Username:** `heath` (or your preferred handle — keep it short)
   - **Email address:** your real email (used for invitation + password reset)
   - **First name / Last name:** your name
   - Leave other fields default
4. Click **Next**.
5. **Add user to groups** screen — skip (no groups yet). Click **Next**.
6. Review and click **Add user**.
7. AWS sends you a setup invitation email. Open it, click **Accept invitation**, set a password, set up MFA (use an authenticator app — Authy, 1Password, Google Authenticator, etc.). MFA is required for IAM Identity Center.

Now grant this user admin access to the AWS account:

8. Back in IAM Identity Center, left nav: **AWS accounts**.
9. Check the box next to your AWS account (the one with your 12-digit ID).
10. Click **Assign users or groups** (button at top right of the account list).
11. Pick the **Users** tab, check `heath`, click **Next**.
12. **Permission sets** screen — click **Create permission set**.
13. Pick **Predefined permission set** → **AdministratorAccess** → **Next**.
14. Permission set name: `AdministratorAccess` (default is fine). Session duration: 12 hours (default). Click **Next** → **Create**.
15. Back on the assignment screen, the new permission set should be checked. Click **Next** → **Submit**.
16. Wait ~30 sec for the assignment to propagate.

Verify the assignment worked:

17. Left nav: **AWS accounts** → click your account → you should see `heath` listed under "Assigned users and groups" with `AdministratorAccess`.

You can now sign out of the root user. From here on, you log in via the SSO Start URL from Step 3.

---

## Step 5 — Install AWS CLI v2 on Windows

Note: Windows has a native MSI installer. **Do not use the older v1 CLI** (it doesn't support SSO).

1. Download: https://awscli.amazonaws.com/AWSCLIV2.msi
2. Run the installer with default options.
3. Open a **new** PowerShell window (existing windows won't see the CLI in PATH).
4. Verify:
   ```powershell
   aws --version
   ```
   Expected output: `aws-cli/2.x.x Python/3.x.x Windows/...`

If `aws` is not recognized, the PATH didn't update. Sign out and back in to Windows, or restart the terminal once more.

---

## Step 6 — Configure the CLI via SSO

This sets up a named profile so commands like `aws s3 ls --profile slpa-prod` use the right credentials.

In PowerShell:

```powershell
aws configure sso
```

The CLI prompts you in this order — answer with what you noted in Steps 3 + 4:

| Prompt                                          | Your answer                                                 |
|-------------------------------------------------|-------------------------------------------------------------|
| `SSO session name (Recommended):`               | `slpa`                                                      |
| `SSO start URL [None]:`                         | the URL from Step 3 (e.g. `https://slpa.awsapps.com/start`) |
| `SSO region [None]:`                            | `us-east-1`                                                 |
| `SSO registration scopes [sso:account:access]:` | press Enter to accept default                               |

The CLI then prints a code and opens your browser. Sign in via your `heath` user (Step 4) and approve the access request. The browser shows "Request approved." Return to the terminal.

Next prompts:

| Prompt                                                 | Your answer                                                                |
|--------------------------------------------------------|----------------------------------------------------------------------------|
| (account selection)                                    | If you have one account, it auto-selects. Otherwise pick the SLParcels account. |
| (role selection)                                       | Pick `AdministratorAccess`.                                                |
| `CLI default client Region [None]:`                    | `us-east-1`                                                                |
| `CLI default output format [None]:`                    | `json`                                                                     |
| `CLI profile name [AdministratorAccess-123456789012]:` | `slpa-prod`                                                                |

You'll end up with a profile named `slpa-prod`. To re-login when your session expires (every ~12h):

```powershell
aws sso login --profile slpa-prod
```

---

## Step 7 — Verify access

```powershell
aws sts get-caller-identity --profile slpa-prod
```

Expected output:

```json
{
    "UserId": "AIDAEXAMPLEEXAMPLEEX:heath",
    "Account": "123456789012",
    "Arn": "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_AdministratorAccess_..../heath"
}
```

The `Account` field should match what you wrote down in Step 1. If you see `Unable to locate credentials`, re-run `aws sso login --profile slpa-prod`.

(Optional) Make `slpa-prod` your default profile so you don't need `--profile` every time:

```powershell
$env:AWS_PROFILE = "slpa-prod"
```

To make permanent across PowerShell sessions:

```powershell
[System.Environment]::SetEnvironmentVariable('AWS_PROFILE', 'slpa-prod', 'User')
```

---

## Step 8 — Enable AWS Cost Explorer

Required for AWS Budgets (the $200 / $400 alarms in the spec) to work. One click, free to enable.

1. Console → search **"Cost Explorer"** → click it.
2. If you see "Enable Cost Explorer" — click the button. Done.
3. If it's already showing graphs, you're good.

Cost Explorer takes 24h to populate the first time. Budgets work without waiting for that data.

---

## Step 9 — Bootstrap Terraform state backend

Terraform stores its state file in S3. The bucket + lock table have to exist **before** Terraform can use them, so we create them out-of-band one time. After this, Terraform manages itself.

Run these in PowerShell with your `slpa-prod` profile active:

### 9a. Create the S3 state bucket

```powershell
aws s3api create-bucket `
  --bucket slpa-prod-tfstate `
  --region us-east-1
```

If you get `BucketAlreadyExists`, the global S3 namespace has a collision. Pick a unique name like `slpa-prod-tfstate-<your-suffix>` (e.g. your AWS account ID's last 6 digits) and remember it for later.

### 9b. Enable versioning (so a corrupted state can be rolled back)

```powershell
aws s3api put-bucket-versioning `
  --bucket slpa-prod-tfstate `
  --versioning-configuration Status=Enabled
```

### 9c. Enable encryption

```powershell
aws s3api put-bucket-encryption `
  --bucket slpa-prod-tfstate `
  --server-side-encryption-configuration '{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"AES256\"}}]}'
```

(The escaped quotes are because PowerShell wants `\"` inside the single-quoted string for nested JSON. If this complains, save the JSON to a file `encryption.json` and use `--server-side-encryption-configuration file://encryption.json`.)

### 9d. Block public access (defense in depth)

```powershell
aws s3api put-public-access-block `
  --bucket slpa-prod-tfstate `
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

### 9e. Create the DynamoDB lock table

```powershell
aws dynamodb create-table `
  --table-name slpa-prod-tfstate-lock `
  --attribute-definitions AttributeName=LockID,AttributeType=S `
  --key-schema AttributeName=LockID,KeyType=HASH `
  --billing-mode PAY_PER_REQUEST `
  --region us-east-1
```

Wait ~30 sec for the table to be active:

```powershell
aws dynamodb wait table-exists --table-name slpa-prod-tfstate-lock
```

### 9f. Verify

```powershell
aws s3api list-buckets --query "Buckets[?Name=='slpa-prod-tfstate']"
aws dynamodb describe-table --table-name slpa-prod-tfstate-lock --query "Table.TableStatus"
```

First should return one bucket entry; second should return `"ACTIVE"`.

**Cost: ~$0.20/mo for the bucket + DynamoDB.** Pay-per-request DynamoDB is essentially free at SLParcels' lock-acquisition rate.

---

## Step 10 — Set up the GitHub Actions OIDC provider

This lets GitHub Actions assume an IAM role to deploy without storing long-lived AWS keys in repo secrets. One-time setup; subsequent IAM roles for specific workflows are managed via Terraform.

### 10a. Add the GitHub OIDC provider to your AWS account

```powershell
aws iam create-open-id-connect-provider `
  --url https://token.actions.githubusercontent.com `
  --client-id-list sts.amazonaws.com `
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

(The thumbprint is GitHub's; AWS docs explain why it's required and that the value is well-known.)

If you've already added this provider for some other repo, you'll get `EntityAlreadyExists` — that's fine, just continue.

### 10b. Verify

```powershell
aws iam list-open-id-connect-providers
```

Should return one entry with an ARN like `arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com`.

**Note:** I'll create the actual deployment role (the one GitHub Actions assumes) via Terraform later. This step just adds the OIDC provider that the role trusts.

---

## Step 11 — Confirm you're ready

Quick checklist. When every box is checked, ping me:

- [x] AWS account exists with a payment method on file
- [x] AWS account ID written down: `486208158127`
- [x] IAM Identity Center enabled in `us-east-1`
- [x] SSO Start URL written down: `https://identitycenter.amazonaws.com/ssoins-7223d34be31db9c5`
- [x] User `heath` created in Identity Center with `AdministratorAccess` permission set
- [x] You can log in via SSO Start URL in a browser
- [x] AWS CLI v2 installed locally; `aws --version` returns `2.x.x`
- [x] CLI profile `slpa-prod` configured via `aws configure sso`
- [x] `aws sts get-caller-identity --profile slpa-prod` returns your account ID
- [x] Cost Explorer enabled (one-click)
- [x] S3 bucket `slpa-prod-tfstate` exists, versioned, encrypted, public-access-blocked
- [x] DynamoDB table `slpa-prod-tfstate-lock` exists and is ACTIVE
- [x] GitHub OIDC provider added (`token.actions.githubusercontent.com`)

When you've done all of these, tell me **"prep done"** + paste the output of `aws sts get-caller-identity --profile slpa-prod` so I can confirm the connection. Then I'll start on Step 1 of the implementation flow (Flyway backend changes).

---

## Things still on your plate (later, not now)

These come up during the deploy flow itself, not pre-flight. Listing here so you're not surprised:

- **Namecheap DNS changes** — when we get to Step 11 of the deploy flow, you'll change nameservers for `slparcels.com` + `slpa.app` and add a URL Redirect record for `slparcelauctions.com`.
- **Sensitive secret values** — when we set up Parameter Store, you'll run `aws ssm put-parameter` for: real bot SL passwords (5x), real `SLPAEscrow Resident` UUID, trusted SL owner UUIDs, JWT signing key, bot shared secret. I'll provide exact commands.
- **SNS subscription confirmation email** — you'll get an email from AWS asking you to confirm the alerts subscription. Click the link.
- **Terraform `apply` approvals** — I'll produce `terraform plan` output for each step; you review and run `apply` (or approve me running it).
- **First image push to ECR** — you'll run `docker build + push` for the first backend image. After that, GitHub Actions does it on every merge to `main`.

---

## What I am and am not doing

To be explicit about how this will work:

**I will:**
- Write all Terraform (`infra/` directory)
- Write GitHub Actions workflows (`.github/workflows/*.yml`)
- Write/modify backend code (Flyway dependency, `application-prod.yml` Redis TLS, remove `AuctionTitleDevTouchUp` etc.)
- Generate the V1 Flyway baseline migration
- Update README + CONVENTIONS docs
- Mirror new endpoints to the Postman collection
- Run `terraform fmt`, `terraform validate`, `terraform plan` (using your local CLI session)
- Provide every CLI command you'll need to run, copy-paste-ready

**I will not without explicit permission:**
- Run `terraform apply` (destructive — touches real cloud resources)
- Run `aws ssm put-parameter` for sensitive values (real secrets)
- Push images to ECR (uses real account credentials and bandwidth)
- Modify Namecheap or any registrar
- Modify GitHub repo settings or branch protection
- Force-push, rebase, or rewrite any history

**I literally cannot:**
- Click anything in the AWS console (browser GUI)
- Read your email (SNS subscription confirmations)
- Click links in emails
- Access your Namecheap account
- Modify the `.env.bot-N` files (per your project memory)
