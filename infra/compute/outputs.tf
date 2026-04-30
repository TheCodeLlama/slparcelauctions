output "ecs_cluster_arn" {
  value = aws_ecs_cluster.main.arn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecr_backend_repo_url" {
  description = "ECR URL for the backend image. Format: <account>.dkr.ecr.<region>.amazonaws.com/slpa/backend"
  value       = aws_ecr_repository.backend.repository_url
}

output "ecr_backend_repo_arn" {
  description = "ECR ARN for the backend image. Used by the GHA deploy role to scope ECR push permissions."
  value       = aws_ecr_repository.backend.arn
}

output "ecr_bot_repo_url" {
  description = "ECR URL for the bot image."
  value       = aws_ecr_repository.bot.repository_url
}

output "ecr_bot_repo_arn" {
  description = "ECR ARN for the bot image."
  value       = aws_ecr_repository.bot.arn
}

output "alb_dns_name" {
  description = "ALB DNS name. The Route 53 ALIAS for slpa.app points here."
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID (used for ALIAS records elsewhere if needed)."
  value       = aws_lb.main.zone_id
}

output "backend_task_role_arn" {
  value = aws_iam_role.backend_task.arn
}

output "backend_task_execution_role_arn" {
  value = aws_iam_role.backend_task_execution.arn
}

output "bot_task_role_arn" {
  value = aws_iam_role.bot_task.arn
}

output "bot_task_execution_role_arn" {
  value = aws_iam_role.bot_task_execution.arn
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch metric dimensions (RequestCount, TargetResponseTime, etc.)."
  value       = aws_lb.main.arn_suffix
}

output "backend_target_group_arn_suffix" {
  description = "Backend ALB target-group ARN suffix for CloudWatch metric dimensions (UnHealthyHostCount, HTTPCode_Target_5XX_Count)."
  value       = aws_lb_target_group.backend.arn_suffix
}

output "backend_service_name" {
  value = aws_ecs_service.backend.name
}

output "bot_service_names" {
  description = "List of active bot ECS service names. Used by GHA bot-deploy workflow to fan out updates."
  value       = [for s in aws_ecs_service.bot : s.name]
}
