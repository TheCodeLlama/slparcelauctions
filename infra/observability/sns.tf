################################################################################
# SNS topic + email subscription
#
# All alarms publish here. The email subscription is created in 'pending' state
# — the operator MUST click the confirmation link in the email AWS sends or
# alerts will silently drop. Terraform's `aws_sns_topic_subscription` resource
# does not block on confirmation; it returns immediately with the pending ARN.
################################################################################

resource "aws_sns_topic" "alerts" {
  name = "slpa-${var.environment}-alerts"

  tags = {
    Name = "slpa-${var.environment}-alerts"
  }
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}
