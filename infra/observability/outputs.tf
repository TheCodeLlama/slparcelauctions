output "alerts_topic_arn" {
  description = "SNS topic ARN where all alarms publish. Subscribe additional endpoints (Slack, PagerDuty) here later."
  value       = aws_sns_topic.alerts.arn
}

output "dashboard_name" {
  value = aws_cloudwatch_dashboard.overview.dashboard_name
}
