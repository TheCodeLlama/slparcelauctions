variable "environment" {
  description = "Environment name applied to role + policy names. Single-env launch (prod)."
  type        = string
}

variable "github_repo" {
  description = "GitHub repo (owner/name) the OIDC role is scoped to. Only workflows in this repo can assume the role."
  type        = string
}

variable "github_branch_for_deploy" {
  description = "The single branch from which deploys are allowed (per spec, only main)."
  type        = string
  default     = "main"
}

variable "ecr_backend_repo_arn" {
  description = "ARN of the slpa/backend ECR repo. Used to scope ECR push permissions."
  type        = string
}

variable "ecr_bot_repo_arn" {
  description = "ARN of the slpa/bot ECR repo."
  type        = string
}

variable "ecs_cluster_arn" {
  description = "ARN of the ECS cluster the deploy role can update services on."
  type        = string
}

variable "backend_task_role_arn" {
  description = "Task role assumed by the backend container. iam:PassRole is granted only on this + the execution role."
  type        = string
}

variable "backend_task_execution_role_arn" {
  description = "Backend execution role (ECR pull, log writes, secrets[] injection)."
  type        = string
}

variable "bot_task_role_arn" {
  description = "Bot task role."
  type        = string
}

variable "bot_task_execution_role_arn" {
  description = "Bot execution role."
  type        = string
}
