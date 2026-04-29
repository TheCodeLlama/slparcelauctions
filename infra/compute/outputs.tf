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

output "ecr_bot_repo_url" {
  description = "ECR URL for the bot image."
  value       = aws_ecr_repository.bot.repository_url
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
