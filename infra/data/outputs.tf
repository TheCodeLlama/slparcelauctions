output "rds_writer_endpoint" {
  description = "RDS Postgres writer endpoint (host:port). Used by the backend ECS task definition to construct SPRING_DATASOURCE_URL."
  value       = aws_db_instance.main.endpoint
}

output "rds_database_name" {
  description = "Postgres database name. Currently 'slpa'."
  value       = aws_db_instance.main.db_name
}

output "rds_password_ssm_arn" {
  description = "Parameter Store ARN of the RDS master password (SecureString). Used by ECS task execution role to inject into SPRING_DATASOURCE_PASSWORD."
  value       = aws_ssm_parameter.rds_master_password.arn
}

output "rds_username_ssm_arn" {
  description = "Parameter Store ARN of the RDS master username."
  value       = aws_ssm_parameter.rds_master_username.arn
}

output "redis_primary_endpoint" {
  description = "ElastiCache Redis primary endpoint. Used by the backend ECS task definition to construct SPRING_DATA_REDIS_HOST."
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "redis_auth_token_ssm_arn" {
  description = "Parameter Store ARN of the Redis AUTH token (SecureString). Used by ECS task execution role to inject into SPRING_DATA_REDIS_PASSWORD."
  value       = aws_ssm_parameter.redis_auth_token.arn
}

output "storage_bucket_name" {
  description = "S3 bucket for user-uploaded photos, avatars, dispute evidence. Single bucket with key prefixes per backend StorageConfigProperties."
  value       = aws_s3_bucket.storage.id
}

output "storage_bucket_arn" {
  description = "S3 bucket ARN — used by the backend task IAM role for s3:GetObject/PutObject/DeleteObject permissions."
  value       = aws_s3_bucket.storage.arn
}
