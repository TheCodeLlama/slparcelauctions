################################################################################
# CloudWatch alarms (spec §4.12)
#
# 11 alarms covering backend (4), RDS (3), Redis (2), bots (1), budgets (handled
# in budgets.tf as separate AWS Budgets resources, not CloudWatch alarms).
#
# All publish to the same SNS topic. Thresholds match the spec table; tune via
# `aws cloudwatch put-metric-alarm --alarm-name <name>` overrides if real prod
# traffic shifts the noise floor.
#
# CodeDeploy-relevant alarms (5xx-rate, p95-latency, unhealthy-hosts) are tagged
# `CodeDeployRollback = true` so a future Step-0 wiring can grep them.
################################################################################

# ----- Backend (ALB-derived) ------------------------------------------------ #

# 5xx rate as a percentage of total requests over a 1-min window. Math expression:
# (5xx / requests) * 100. Treats divide-by-zero as 0% (no traffic = healthy).
resource "aws_cloudwatch_metric_alarm" "backend_5xx_rate" {
  alarm_name          = "slpa-${var.environment}-backend-5xx-rate"
  alarm_description   = "Backend 5xx rate exceeded 5% over 1 min. Likely deploy regression or downstream failure."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "rate"
    expression  = "IF(reqs > 0, (errors / reqs) * 100, 0)"
    label       = "5xx rate (%)"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
        TargetGroup  = var.backend_target_group_arn_suffix
      }
    }
  }

  metric_query {
    id = "reqs"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
        TargetGroup  = var.backend_target_group_arn_suffix
      }
    }
  }

  tags = {
    CodeDeployRollback = "true"
  }
}

resource "aws_cloudwatch_metric_alarm" "backend_p95_latency" {
  alarm_name          = "slpa-${var.environment}-backend-p95-latency"
  alarm_description   = "Backend p95 response time exceeded 2s over 5 min. Likely DB pool exhaustion or N+1 query regression."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.backend_target_group_arn_suffix
  }

  tags = {
    CodeDeployRollback = "true"
  }
}

resource "aws_cloudwatch_metric_alarm" "backend_unhealthy_hosts" {
  alarm_name          = "slpa-${var.environment}-backend-unhealthy-hosts"
  alarm_description   = "Backend has at least one unhealthy ALB target. With desired_count=1 this means total outage."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 60
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.backend_target_group_arn_suffix
  }

  tags = {
    CodeDeployRollback = "true"
  }
}

# ----- Backend ECS task failures (alert-only, no rollback) ----------------- #

# `ServiceTaskCount` minus `DesiredTaskCount` would be ideal but ECS doesn't
# expose that as a single metric. RunningTaskCount < DesiredTaskCount = at
# least one task failure or in-progress restart.
resource "aws_cloudwatch_metric_alarm" "backend_task_failures" {
  alarm_name          = "slpa-${var.environment}-backend-ecs-task-failures"
  alarm_description   = "Backend has fewer running tasks than desired. At least one task crashed or is restarting."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "diff"
    expression  = "desired - running"
    label       = "Tasks below desired"
    return_data = true
  }

  metric_query {
    id = "desired"
    metric {
      metric_name = "DesiredTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Average"
      dimensions = {
        ClusterName = var.ecs_cluster_name
        ServiceName = var.backend_service_name
      }
    }
  }

  metric_query {
    id = "running"
    metric {
      metric_name = "RunningTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Average"
      dimensions = {
        ClusterName = var.ecs_cluster_name
        ServiceName = var.backend_service_name
      }
    }
  }
}

# ----- RDS ------------------------------------------------------------------ #

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "slpa-${var.environment}-rds-cpu"
  alarm_description   = "RDS CPU > 80% over 5 min. Investigate slow queries via Performance Insights."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 80
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_id
  }
}

# Free storage low. Threshold = 20% of allocated storage. Allocated storage is
# baked into the RDS instance config and exposed via FreeStorageSpace
# (bytes-free) — we compare against an absolute floor of 4 GB which is 20% of
# the 20 GB launch-lite allocation. If allocated storage grows via
# rds_allocated_storage, bump this.
resource "aws_cloudwatch_metric_alarm" "rds_free_storage_low" {
  alarm_name          = "slpa-${var.environment}-rds-free-storage-low"
  alarm_description   = "RDS free storage below 4 GB (20% of 20 GB launch allocation). Storage autoscaling should kick in but worth a heads-up."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 4 * 1024 * 1024 * 1024 # 4 GB in bytes
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_id
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_connections_high" {
  alarm_name          = "slpa-${var.environment}-rds-connections-high"
  alarm_description   = "RDS connection count above 80% of max_connections. Investigate connection pool sizing."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  statistic           = "Average"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  period              = 300
  threshold           = ceil(var.rds_max_connections * 0.8)
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_id
  }
}

# ----- Redis ---------------------------------------------------------------- #

resource "aws_cloudwatch_metric_alarm" "redis_cpu" {
  alarm_name          = "slpa-${var.environment}-redis-cpu"
  alarm_description   = "Redis EngineCPUUtilization > 80% over 5 min. Investigate hot keys or connection storms."
  namespace           = "AWS/ElastiCache"
  metric_name         = "EngineCPUUtilization"
  statistic           = "Average"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 80
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    ReplicationGroupId = var.redis_replication_group_id
  }
}

resource "aws_cloudwatch_metric_alarm" "redis_memory_pressure" {
  alarm_name          = "slpa-${var.environment}-redis-memory-pressure"
  alarm_description   = "Redis DatabaseMemoryUsagePercentage > 90% (less than 10% free). Risk of evictions."
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsagePercentage"
  statistic           = "Average"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 90
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    ReplicationGroupId = var.redis_replication_group_id
  }
}

# ----- Bot pool ------------------------------------------------------------- #

# One alarm per active bot service: running task count != desired (which is
# always 1 for bots). If a bot's task count drops to 0, the bot is either
# restarting or stuck — operator should investigate. The alarm de-registers
# itself when bot_active_count shrinks (for_each tied to var.bot_service_names).
resource "aws_cloudwatch_metric_alarm" "bot_task_running" {
  for_each = toset(var.bot_service_names)

  alarm_name          = "slpa-${var.environment}-${each.value}-task-running"
  alarm_description   = "Bot ${each.value} has fewer than 1 running task. Bot is offline or restarting."
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Average"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 3
  period              = 60
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = each.value
  }
}
