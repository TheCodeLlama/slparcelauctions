output "vpc_id" {
  value       = aws_vpc.main.id
  description = "VPC ID for downstream modules (data, compute)."
}

output "vpc_cidr_block" {
  value       = aws_vpc.main.cidr_block
  description = "VPC CIDR block."
}

output "public_subnet_ids" {
  value       = aws_subnet.public[*].id
  description = "Public subnet IDs (ALB, NAT)."
}

output "private_subnet_ids" {
  value       = aws_subnet.private[*].id
  description = "Private subnet IDs (Fargate tasks, RDS, Redis)."
}

output "private_route_table_id" {
  value       = aws_route_table.private.id
  description = "Single private route table (launch-lite). Per-AZ tables when nat_type=gateway."
}

# ----- Security group IDs (consumed by data + compute modules) -------------- #

output "alb_security_group_id" {
  value       = aws_security_group.alb.id
  description = "ALB SG — for ALB resource itself in compute module."
}

output "backend_security_group_id" {
  value       = aws_security_group.backend.id
  description = "Backend Fargate SG — assigned to backend ECS service tasks."
}

output "bots_security_group_id" {
  value       = aws_security_group.bots.id
  description = "Bots Fargate SG — assigned to each bot ECS service's tasks."
}

output "rds_security_group_id" {
  value       = aws_security_group.rds.id
  description = "RDS SG — assigned to the RDS instance in data module."
}

output "redis_security_group_id" {
  value       = aws_security_group.redis.id
  description = "Redis SG — assigned to the ElastiCache replication group in data module."
}
