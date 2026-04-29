################################################################################
# SLPA — Terraform variables
#
# Every variable is annotated with whether it's a launch-lite default or an
# upgrade lever. Spec reference: §5 (Launch-lite vs. production-tier upgrade
# levers) of docs/superpowers/specs/2026-04-29-aws-deployment-design.md.
#
# Sensitive values are NOT defaulted here — they're set in terraform.tfvars
# (gitignored) or sourced from Parameter Store (set out-of-band via
# `aws ssm put-parameter` per PREP.md / spec §7.3).
################################################################################

# ----- Identity + global ---------------------------------------------------- #

variable "aws_region" {
  description = "Primary AWS region for the SLPA stack. Spec §4.1 + Q12."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name applied to default tags + resource names. Single-env launch (spec Q3 = prod-only)."
  type        = string
  default     = "prod"
}

variable "alarm_email" {
  description = "Operator email subscribed to the SNS alerts topic. Required."
  type        = string
}

# ----- Domains -------------------------------------------------------------- #

variable "domain_slparcels" {
  description = "Frontend apex (Amplify). Spec §4.9."
  type        = string
  default     = "slparcels.com"
}

variable "domain_slpa_app" {
  description = "Backend apex (ALB ALIAS target). Spec §4.9."
  type        = string
  default     = "slpa.app"
}

# ----- RDS Postgres (spec §4.5) -------------------------------------------- #

variable "rds_multi_az" {
  description = "Multi-AZ RDS deployment. Launch-lite default = false; flip to true once production traffic justifies the +$30/mo for AZ failover. Upgrade lever."
  type        = bool
  default     = false
}

variable "rds_instance_class" {
  description = "RDS instance class. Launch-lite default = db.t4g.micro. Upgrade path: db.t4g.small → db.t4g.medium → db.m6g.large."
  type        = string
  default     = "db.t4g.micro"
}

variable "rds_allocated_storage" {
  description = "RDS storage (GB). Launch-lite default = 20. Autoscaling enabled to a 100 GB ceiling regardless of this initial value."
  type        = number
  default     = 20
}

variable "rds_database_insights_enabled" {
  description = "Enable RDS Database Insights (CloudWatch). Launch-lite default = false. Upgrade lever for moderate+ tier."
  type        = bool
  default     = false
}

variable "rds_pi_retention_days" {
  description = "Performance Insights retention. 7 = free tier. Upgrade lever to 731 (24mo) at moderate+ tier."
  type        = number
  default     = 7
}

variable "rds_enable_proxy" {
  description = "Enable RDS Proxy. Launch-lite default = false. Upgrade lever for heavy-tier connection-spike mitigation."
  type        = bool
  default     = false
}

# ----- ElastiCache Redis (spec §4.6) --------------------------------------- #

variable "redis_num_cache_clusters" {
  description = "Number of Redis cache nodes. Launch-lite default = 1 (single node). Upgrade lever to 2 (primary + replica with Multi-AZ failover)."
  type        = number
  default     = 1
}

variable "redis_node_type" {
  description = "ElastiCache node type. Launch-lite default = cache.t4g.micro. Upgrade lever: cache.t4g.small → cache.t4g.medium."
  type        = string
  default     = "cache.t4g.micro"
}

# ----- Compute — backend (spec §4.2) --------------------------------------- #

variable "backend_desired_count" {
  description = "Number of backend Fargate tasks running concurrently. Default 0 in this PR — bump to 1+ AFTER bootstrapping user-action secrets in Parameter Store (spec §7.3) AND pushing first backend image to ECR. Launch-lite production target = 1; upgrade lever to 2+ for scheduler-redundancy + AZ spread."
  type        = number
  default     = 0
}

variable "backend_image_tag" {
  description = "ECR image tag for the backend ECS task. Default 'initial' — first deploy pushes the image manually with this tag, then bump backend_desired_count to start tasks. CI/CD later rotates this to git SHA on every merge to main."
  type        = string
  default     = "initial"
}

variable "backend_cpu" {
  description = "Backend Fargate task CPU units (256 = 0.25 vCPU, 512 = 0.5 vCPU, 1024 = 1 vCPU, etc.). Launch-lite default = 256."
  type        = number
  default     = 256
}

variable "backend_memory" {
  description = "Backend Fargate task memory (MiB). Must satisfy Fargate's vCPU/memory ratio. Launch-lite default = 512 (matches 256 CPU)."
  type        = number
  default     = 512
}

# ----- Compute — bot pool (spec §4.3) -------------------------------------- #

variable "bot_active_count" {
  description = "Number of bot ECS services to provision (1-5). Launch-lite default = 2 (bot-1 + bot-2). Increase as Method-C verification + ownership-monitoring volume justifies."
  type        = number
  default     = 2

  validation {
    condition     = var.bot_active_count >= 1 && var.bot_active_count <= 5
    error_message = "Bot pool is fixed at 5 named workers (SLPABot1-5). Set between 1 and 5."
  }
}

variable "bot_cpu" {
  description = "Per-bot Fargate task CPU units. Launch-lite default = 256 (0.25 vCPU). Upgrade lever to 512 at heavy tier."
  type        = number
  default     = 256
}

variable "bot_memory" {
  description = "Per-bot Fargate task memory (MiB). Launch-lite default = 512."
  type        = number
  default     = 512
}

# ----- Networking (spec §4.1) ---------------------------------------------- #

variable "vpc_cidr" {
  description = "VPC CIDR block. /16 gives plenty of room for future subnets."
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "AZs for subnet placement. Two required even at launch-lite (RDS subnet group constraint)."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "nat_type" {
  description = "NAT shape. Launch-lite default = 'instance' (fck-nat on t4g.nano, ~$5/mo). Upgrade lever to 'gateway' (×2 for HA, ~$64/mo)."
  type        = string
  default     = "instance"

  validation {
    condition     = contains(["instance", "gateway"], var.nat_type)
    error_message = "nat_type must be 'instance' or 'gateway'."
  }
}

variable "enable_interface_endpoints" {
  description = "Enable VPC interface endpoints for ECR/Secrets/CloudWatch. Launch-lite default = false (S3 gateway endpoint is always on, free). Upgrade lever (~$42-56/mo) once NAT egress costs justify."
  type        = bool
  default     = false
}

# ----- Observability (spec §4.12) ------------------------------------------ #

variable "log_retention_days" {
  description = "CloudWatch Logs retention for ECS task log groups. Launch-lite default = 7. Upgrade lever to 30 / 90 days as compliance or debugging window grows."
  type        = number
  default     = 7
}

variable "enable_xray" {
  description = "Enable AWS X-Ray distributed tracing on the backend. Launch-lite default = false. Upgrade lever (~$1-10/mo)."
  type        = bool
  default     = false
}

# ----- Security (spec §4.13 / §9 deferred) --------------------------------- #

variable "enable_waf" {
  description = "Enable AWS WAF on the ALB. Launch-lite default = false. Upgrade lever ($5+/mo + per-request) once traffic justifies."
  type        = bool
  default     = false
}

# ----- Cost guardrails (spec §4.13) ---------------------------------------- #

variable "budget_soft_threshold_usd" {
  description = "AWS Budgets soft alert (email). Launch-lite default = $200/mo (1.5x estimate)."
  type        = number
  default     = 200
}

variable "budget_hard_threshold_usd" {
  description = "AWS Budgets hard alert. Launch-lite default = $400/mo (2.6x estimate; signal of something very wrong)."
  type        = number
  default     = 400
}

# ----- CI/CD (spec §4.10) -------------------------------------------------- #

variable "github_repo" {
  description = "GitHub repo (owner/name) for OIDC trust. Used to scope the deploy role to this specific repo + branch."
  type        = string
  default     = "TheCodeLlama/slparcelauctions"
}

variable "github_branch_for_deploy" {
  description = "Git branch GitHub Actions can assume the deploy role from. Per the spec, only main."
  type        = string
  default     = "main"
}
