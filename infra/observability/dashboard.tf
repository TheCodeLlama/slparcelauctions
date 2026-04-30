################################################################################
# CloudWatch dashboard (spec §4.12)
#
# Single overview dashboard. Six widgets covering the surfaces an operator
# wants to see in one glance after a deploy or page:
#   1. Backend request rate + 5xx count + latency p50/p95/p99
#   2. RDS CPU + connections + free storage
#   3. ElastiCache CPU + memory pressure
#   4. ECS task counts (backend + bots) — shows desired vs. running
#
# JSON shape is the standard CloudWatch dashboard body schema; the
# `aws_cloudwatch_dashboard` resource takes it as a string.
################################################################################

resource "aws_cloudwatch_dashboard" "overview" {
  dashboard_name = "slpa-${var.environment}-overview"

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Backend — request rate + 5xx + latency"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.backend_target_group_arn_suffix, { "label" : "Requests" }],
            [".", "HTTPCode_Target_5XX_Count", ".", ".", ".", ".", { "label" : "5xx", "color" : "#d62728" }],
            [".", "TargetResponseTime", ".", ".", ".", ".", { "stat" : "p50", "label" : "p50 latency", "yAxis" : "right" }],
            ["...", { "stat" : "p95", "label" : "p95 latency", "yAxis" : "right", "color" : "#ff7f0e" }],
            ["...", { "stat" : "p99", "label" : "p99 latency", "yAxis" : "right", "color" : "#9467bd" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "RDS — CPU + connections + free storage"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 300
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.rds_instance_id, { "stat" : "Average", "label" : "CPU %" }],
            [".", "DatabaseConnections", ".", ".", { "stat" : "Average", "label" : "Connections", "yAxis" : "right" }],
            [".", "FreeStorageSpace", ".", ".", { "stat" : "Minimum", "label" : "Free GB", "yAxis" : "right" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "Redis — CPU + memory"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 300
          metrics = [
            ["AWS/ElastiCache", "EngineCPUUtilization", "ReplicationGroupId", var.redis_replication_group_id, { "stat" : "Average", "label" : "Engine CPU %" }],
            [".", "DatabaseMemoryUsagePercentage", ".", ".", { "stat" : "Average", "label" : "Memory used %", "yAxis" : "right" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "ECS — backend + bot task counts"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          stat   = "Average"
          metrics = concat(
            [
              ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", var.backend_service_name, { "label" : "backend" }],
            ],
            [for name in var.bot_service_names : ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", name, { "label" : name }]]
          )
        }
      },
    ]
  })
}
