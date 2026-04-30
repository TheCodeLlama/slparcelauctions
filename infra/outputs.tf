################################################################################
# SLPA — Terraform outputs
#
# Outputs are added as resources land in subsequent PRs. Expected outputs from
# the design (added incrementally):
#
#   networking:    vpc_id, public_subnet_ids, private_subnet_ids, alb_dns_name
#   data:          rds_writer_endpoint, redis_primary_endpoint, s3_storage_bucket
#   compute:       ecs_cluster_arn, ecr_backend_repo_url, ecr_bot_repo_url
#   secrets:       (none — Parameter Store paths are conventional)
#   dns:           route53_zone_ns_slparcels, route53_zone_ns_slpa_app
#   cicd:          github_actions_role_arn, codedeploy_app_name
#
# This file stays empty at the skeleton stage — Terraform requires no outputs.
################################################################################

output "aws_account_id" {
  description = "AWS account this stack is deployed into. Sanity check that init is wired to the right account."
  value       = data.aws_caller_identity.current.account_id
}

output "aws_region" {
  description = "Primary region for the stack."
  value       = data.aws_region.current.region
}

# ----- Networking outputs (re-exported from the module) --------------------- #

output "vpc_id" {
  value = module.networking.vpc_id
}

output "public_subnet_ids" {
  value = module.networking.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.networking.private_subnet_ids
}

# ----- Data tier outputs (re-exported from the module) ---------------------- #

output "rds_writer_endpoint" {
  value = module.data.rds_writer_endpoint
}

output "redis_primary_endpoint" {
  value = module.data.redis_primary_endpoint
}

output "storage_bucket_name" {
  value = module.data.storage_bucket_name
}

# ----- DNS outputs (the nameservers are what you paste into Namecheap) ------ #

output "nameservers_slparcels_com" {
  description = "Paste these 4 NS records into Namecheap for slparcels.com (Domain List → Manage → Domain → Nameservers → Custom DNS)."
  value       = module.dns.nameservers_slparcels_com
}

output "nameservers_slpa_app" {
  description = "Paste these 4 NS records into Namecheap for slpa.app (same path)."
  value       = module.dns.nameservers_slpa_app
}

output "cert_arn_slpa_app" {
  value = module.dns.cert_arn_slpa_app
}

output "cert_arn_slparcels_com" {
  value = module.dns.cert_arn_slparcels_com
}

# ----- Compute outputs ------------------------------------------------------ #

output "ecs_cluster_name" {
  value = module.compute.ecs_cluster_name
}

output "ecr_backend_repo_url" {
  value = module.compute.ecr_backend_repo_url
}

output "ecr_bot_repo_url" {
  value = module.compute.ecr_bot_repo_url
}

output "alb_dns_name" {
  value = module.compute.alb_dns_name
}

# Amplify outputs removed — frontend is managed via Amplify Console rather
# than Terraform (see comment in main.tf for context).

# ----- CI/CD outputs -------------------------------------------------------- #

output "gha_deploy_role_arn" {
  description = "Paste this into .github/workflows/*.yml as the role-to-assume input. Already wired in deploy-backend.yml + deploy-bot.yml."
  value       = module.cicd.gha_deploy_role_arn
}

# ----- Observability outputs ------------------------------------------------ #

output "alerts_topic_arn" {
  description = "SNS topic for all alarm notifications. Confirm the email subscription in your inbox."
  value       = module.observability.alerts_topic_arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name. Open via https://console.aws.amazon.com/cloudwatch/home?region=us-east-1#dashboards:name=<this>."
  value       = module.observability.dashboard_name
}
