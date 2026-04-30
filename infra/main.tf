################################################################################
# SLPA — Terraform root module
#
# Deploys the SLPA production stack on AWS. See:
#   docs/superpowers/specs/2026-04-29-aws-deployment-design.md  (architecture)
#   AWS_CALCULATION.md                                           (cost estimates)
#   PREP.md                                                      (one-time setup)
#
# This file owns the provider + backend wiring only. Resources live in topic
# subdirectories (networking/, data/, compute/, etc.) added as later PRs land.
#
# Apply via:
#   cd infra
#   terraform init       # configures the S3 backend (one-time per workstation)
#   terraform plan -out=tfplan
#   terraform apply tfplan
#
# State backend (S3 bucket + DynamoDB lock) is bootstrapped manually per
# PREP.md §9 — Terraform cannot create its own backend.
################################################################################

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    # fck-nat module pulls in cloudinit for the user-data rendering on the
    # NAT instance. Declared explicitly here so `terraform providers lock`
    # picks it up.
    cloudinit = {
      source  = "hashicorp/cloudinit"
      version = "~> 2.3"
    }
  }

  # State backend — must match the bootstrap created in PREP.md §9.
  # Bucket name is NOT parameterised because Terraform's `backend` block
  # doesn't support variables (chicken-and-egg with init).
  #
  # `use_lockfile = true` (Terraform 1.10+) uses S3 conditional writes for
  # state locking instead of a separate DynamoDB table. The table created
  # in PREP.md §9.e is unused going forward — safe to delete with
  # `aws dynamodb delete-table --table-name slpa-prod-tfstate-lock` whenever
  # convenient (no rush, costs are negligible at PAY_PER_REQUEST idle).
  backend "s3" {
    bucket       = "slpa-prod-tfstate"
    key          = "prod/terraform.tfstate"
    region       = "us-east-1"
    use_lockfile = true
    encrypt      = true
  }
}

################################################################################
# AWS provider
#
# Default tags are applied to every taggable resource — covers cost allocation
# (Project, Environment) and operator clarity (ManagedBy = Terraform). Override
# per-resource if a specific resource needs additional tags; default_tags
# merges, doesn't replace.
################################################################################

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "slpa"
      Environment = var.environment
      ManagedBy   = "terraform"
      Repo        = "TheCodeLlama/slparcelauctions"
    }
  }
}

# Second AWS provider aliased to us-east-1 for resources that MUST live
# there regardless of the stack's primary region (CloudFront ACM certs,
# Amplify-managed certs). Today the stack is in us-east-1 already so this
# is functionally identical to the default provider — keeping the alias
# defined now means a future region migration doesn't have to touch ACM.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "slpa"
      Environment = var.environment
      ManagedBy   = "terraform"
      Repo        = "TheCodeLlama/slparcelauctions"
    }
  }
}

################################################################################
# Data sources used at root scope
################################################################################

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

################################################################################
# Module wiring
################################################################################

module "networking" {
  source = "./networking"

  environment        = var.environment
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  nat_type           = var.nat_type
}

module "data" {
  source = "./data"

  environment        = var.environment
  vpc_id             = module.networking.vpc_id
  private_subnet_ids = module.networking.private_subnet_ids

  rds_security_group_id   = module.networking.rds_security_group_id
  redis_security_group_id = module.networking.redis_security_group_id

  rds_multi_az                  = var.rds_multi_az
  rds_instance_class            = var.rds_instance_class
  rds_allocated_storage         = var.rds_allocated_storage
  rds_database_insights_enabled = var.rds_database_insights_enabled
  rds_pi_retention_days         = var.rds_pi_retention_days

  redis_num_cache_clusters = var.redis_num_cache_clusters
  redis_node_type          = var.redis_node_type
}

module "dns" {
  source = "./dns"

  environment      = var.environment
  domain_slparcels = var.domain_slparcels
  domain_slpa_app  = var.domain_slpa_app
}

# module "frontend" — removed 2026-04-29.
#
# Amplify Hosting for the Next.js frontend is managed via the Amplify Console
# rather than Terraform because the WEB_COMPUTE platform's IAM role wiring
# repeatedly defeated `aws_amplify_app` in our hands (9 build attempts, none
# succeeded — Amplify's build environment kept rejecting any custom service
# role with the misleading "Unable to assume specified IAM Role" error).
#
# What lives in the console (slpa-frontend app):
#   - GitHub source via CodeStar Connection (049dbcb6-...)
#   - main branch with auto-build on push
#   - WEB_COMPUTE platform (Next.js SSR)
#   - Custom domain slparcels.com + www
#   - Build spec: frontend/amplify.yml (committed in repo)
#   - env: NEXT_PUBLIC_API_URL = https://slpa.app
#
# What stays in Terraform:
#   - Route 53 zone for slparcels.com (in dns module — required for the
#     CodeStar Connection-based custom domain validation that Amplify drives)
#   - GitHub PAT in tfvars (kept; used by GHA deploy role too)
#
# Reproducing on a fresh AWS account: see the Amplify Console steps in the
# AWS deployment design doc + the post-PR-49 README addendum.

module "compute" {
  source = "./compute"

  environment        = var.environment
  vpc_id             = module.networking.vpc_id
  public_subnet_ids  = module.networking.public_subnet_ids
  private_subnet_ids = module.networking.private_subnet_ids

  alb_security_group_id     = module.networking.alb_security_group_id
  backend_security_group_id = module.networking.backend_security_group_id
  bots_security_group_id    = module.networking.bots_security_group_id

  rds_writer_endpoint      = module.data.rds_writer_endpoint
  rds_database_name        = module.data.rds_database_name
  rds_password_ssm_arn     = module.data.rds_password_ssm_arn
  rds_username_ssm_arn     = module.data.rds_username_ssm_arn
  redis_primary_endpoint   = module.data.redis_primary_endpoint
  redis_auth_token_ssm_arn = module.data.redis_auth_token_ssm_arn
  storage_bucket_name      = module.data.storage_bucket_name
  storage_bucket_arn       = module.data.storage_bucket_arn

  cert_arn_slpa_app = module.dns.cert_arn_slpa_app
  zone_id_slpa_app  = module.dns.zone_id_slpa_app
  domain_slpa_app   = var.domain_slpa_app

  backend_desired_count = var.backend_desired_count
  backend_cpu           = var.backend_cpu
  backend_memory        = var.backend_memory
  backend_image_tag     = var.backend_image_tag

  bot_active_count = var.bot_active_count
  bot_cpu          = var.bot_cpu
  bot_memory       = var.bot_memory

  log_retention_days = var.log_retention_days
}

module "cicd" {
  source = "./cicd"

  environment              = var.environment
  github_repo              = var.github_repo
  github_branch_for_deploy = var.github_branch_for_deploy

  ecr_backend_repo_arn = module.compute.ecr_backend_repo_arn
  ecr_bot_repo_arn     = module.compute.ecr_bot_repo_arn
  ecs_cluster_arn      = module.compute.ecs_cluster_arn

  backend_task_role_arn           = module.compute.backend_task_role_arn
  backend_task_execution_role_arn = module.compute.backend_task_execution_role_arn
  bot_task_role_arn               = module.compute.bot_task_role_arn
  bot_task_execution_role_arn     = module.compute.bot_task_execution_role_arn
}

module "observability" {
  source = "./observability"

  environment = var.environment
  alarm_email = var.alarm_email

  ecs_cluster_name                = module.compute.ecs_cluster_name
  backend_service_name            = module.compute.backend_service_name
  bot_service_names               = module.compute.bot_service_names
  alb_arn_suffix                  = module.compute.alb_arn_suffix
  backend_target_group_arn_suffix = module.compute.backend_target_group_arn_suffix
  rds_instance_id                 = module.data.rds_instance_id
  rds_max_connections             = module.data.rds_max_connections
  redis_replication_group_id      = module.data.redis_replication_group_id

  budget_soft_threshold_usd = var.budget_soft_threshold_usd
  budget_hard_threshold_usd = var.budget_hard_threshold_usd
}
