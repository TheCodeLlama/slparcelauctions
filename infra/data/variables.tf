variable "environment" {
  description = "Environment name (used in resource naming + tags)."
  type        = string
}

variable "vpc_id" {
  description = "VPC where the data tier lives. From networking module."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for the RDS + ElastiCache subnet groups. From networking module. Must be at least 2 even at single-AZ launch-lite (RDS subnet group constraint)."
  type        = list(string)
}

variable "rds_security_group_id" {
  description = "RDS SG from networking module — controls who can connect on 5432."
  type        = string
}

variable "redis_security_group_id" {
  description = "Redis SG from networking module — controls who can connect on 6379."
  type        = string
}

# ----- RDS sizing (passed through from root) -------------------------------- #

variable "rds_multi_az" {
  type = bool
}

variable "rds_instance_class" {
  type = string
}

variable "rds_allocated_storage" {
  type = number
}

variable "rds_database_insights_enabled" {
  type = bool
}

variable "rds_pi_retention_days" {
  type = number
}

# ----- Redis sizing --------------------------------------------------------- #

variable "redis_num_cache_clusters" {
  type = number
}

variable "redis_node_type" {
  type = string
}
