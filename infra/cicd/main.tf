################################################################################
# SLPA — CI/CD module (spec §4.10)
#
# Owns the IAM role GitHub Actions assumes via OIDC to deploy backend + bots.
# The OIDC provider itself is created out-of-band (PREP.md §10) — this module
# just creates the role that trusts it.
#
# Trust scope: only the configured repo's main branch can assume the role.
# Permission scope: ECR push for slpa/backend + slpa/bot, ECS describe +
# register-task-definition + update-service for the slpa-${env} cluster, and
# iam:PassRole on the existing task + execution roles.
#
# What this module deliberately does NOT include:
# - CodeDeploy app/deployment-group (deferred per spec — ECSAllAtOnce rolling
#   is fine at launch).
# - Workflow YAML files. Those live in `.github/workflows/` (git-managed).
################################################################################

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
