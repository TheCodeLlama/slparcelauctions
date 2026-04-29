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
