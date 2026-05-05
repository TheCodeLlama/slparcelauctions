################################################################################
# SLParcels — Observability module (spec §4.12 + §4.13)
#
# Owns:
#   - SNS topic + email subscription (slpa-${env}-alerts)
#   - 11 of the 12 spec'd CloudWatch alarms (NAT instance CPU deferred —
#     fck-nat module output schema not verified here; tracked in DEFERRED_WORK)
#   - CloudWatch dashboard (slpa-${env}-overview)
#   - AWS Budgets (soft + hard)
#
# All alarms publish to the SNS topic; the operator email subscription must be
# *confirmed* via the AWS-sent confirmation link before alerts arrive. The
# subscription resource creates the pending subscription; confirmation is a
# user action.
################################################################################

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
