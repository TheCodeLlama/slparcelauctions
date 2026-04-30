################################################################################
# AWS Budgets (spec §4.13)
#
# Two cost-monitoring budgets. Both publish to the same SNS topic the alarms
# use (operator gets one email, regardless of which budget tripped).
#
# These are AWS Budgets resources (not CloudWatch alarms) because Budgets has
# its own evaluation engine optimised for cost data with daily granularity.
################################################################################

resource "aws_budgets_budget" "soft" {
  name              = "slpa-${var.environment}-soft-budget"
  budget_type       = "COST"
  limit_amount      = tostring(var.budget_soft_threshold_usd)
  limit_unit        = "USD"
  time_unit         = "MONTHLY"
  time_period_start = "2026-01-01_00:00"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }
}

resource "aws_budgets_budget" "hard" {
  name              = "slpa-${var.environment}-hard-budget"
  budget_type       = "COST"
  limit_amount      = tostring(var.budget_hard_threshold_usd)
  limit_unit        = "USD"
  time_unit         = "MONTHLY"
  time_period_start = "2026-01-01_00:00"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alarm_email]
  }
}
