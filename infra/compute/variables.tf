variable "environment" {
  type = string
}

# ----- Inputs from sibling modules ----------------------------------------- #

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  description = "Public subnets for the ALB."
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Private subnets for ECS tasks."
  type        = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "backend_security_group_id" {
  type = string
}

variable "rds_writer_endpoint" {
  description = "Format 'host:port' from data module — split for SPRING_DATASOURCE_URL construction."
  type        = string
}

variable "rds_database_name" {
  type = string
}

variable "rds_password_ssm_arn" {
  type = string
}

variable "rds_username_ssm_arn" {
  type = string
}

variable "redis_primary_endpoint" {
  type = string
}

variable "redis_auth_token_ssm_arn" {
  type = string
}

variable "storage_bucket_name" {
  type = string
}

variable "storage_bucket_arn" {
  type = string
}

variable "cert_arn_slpa_app" {
  type = string
}

variable "zone_id_slpa_app" {
  type = string
}

variable "domain_slpa_app" {
  type = string
}

# ----- Backend sizing + image tag (passed through from root) --------------- #

variable "backend_desired_count" {
  type = number
}

variable "backend_cpu" {
  type = number
}

variable "backend_memory" {
  type = number
}

variable "backend_image_tag" {
  description = "Image tag for the backend ECS task definition. Defaults to 'initial' for first deploy; CI/CD bumps to git SHA on each deploy."
  type        = string
  default     = "initial"
}

variable "log_retention_days" {
  type = number
}

# ----- CORS + frontend wiring ---------------------------------------------- #

variable "cors_allowed_origin" {
  description = "Public origin allowed by Spring Security CORS. Should match the Amplify custom domain (e.g. https://slparcels.com)."
  type        = string
  default     = "https://slparcels.com"
}

variable "web_base_url" {
  description = "Public web origin used by backend to construct outbound links in notifications."
  type        = string
  default     = "https://slparcels.com"
}
