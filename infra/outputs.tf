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
  value       = data.aws_region.current.name
}
