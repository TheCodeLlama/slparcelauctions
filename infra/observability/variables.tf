variable "environment" {
  description = "Environment name applied to alarm + topic names."
  type        = string
}

variable "alarm_email" {
  description = "Operator email subscribed to the SNS alerts topic."
  type        = string
}

# Resource references for alarm dimensions
variable "ecs_cluster_name" {
  description = "ECS cluster name (CloudWatch dimension)."
  type        = string
}

variable "backend_service_name" {
  description = "Backend ECS service name."
  type        = string
}

variable "bot_service_names" {
  description = "List of active bot ECS service names."
  type        = list(string)
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix for ALB-level metrics (RequestCount, TargetResponseTime, HTTPCode_Target_*)."
  type        = string
}

variable "backend_target_group_arn_suffix" {
  description = "Backend ALB target-group ARN suffix for UnHealthyHostCount / HealthyHostCount."
  type        = string
}

variable "rds_instance_id" {
  description = "RDS instance identifier (DBInstanceIdentifier)."
  type        = string
}

variable "rds_max_connections" {
  description = "Approximate Postgres max_connections; used to compute the connections-high threshold (80% of this)."
  type        = number
}

variable "redis_replication_group_id" {
  description = "ElastiCache replication group ID."
  type        = string
}

# Cost guardrails (spec §4.13)
variable "budget_soft_threshold_usd" {
  description = "Soft budget alert in USD."
  type        = number
}

variable "budget_hard_threshold_usd" {
  description = "Hard budget alert in USD."
  type        = number
}
