################################################################################
# Backend ECS service + task definition (spec §4.2)
#
# Task definition uses awsvpc network mode (required for Fargate). All env
# vars except secrets are passed via the `environment[]` block; sensitive
# values come from Parameter Store via `secrets[]` injection (the execution
# role has SSM read for /slpa/${env}/*).
#
# desired_count starts at 0 — the operator must bootstrap user-action secrets
# (JWT_SECRET, SLPA_BOT_SHARED_SECRET, SLPA_PRIMARY_ESCROW_UUID,
# SLPA_SL_TRUSTED_OWNER_KEYS) via `aws ssm put-parameter` per spec §7.3
# BEFORE bumping desired_count. Backend's SlStartupValidator + BotStartupValidator
# fail-fast on placeholder values and the task would crash-loop.
#
# After secrets are set + first image is pushed, bump var.backend_desired_count
# = 1 and apply.
################################################################################

locals {
  # Parse RDS endpoint "host:port" into separate values for SPRING_DATASOURCE_URL.
  rds_host = split(":", var.rds_writer_endpoint)[0]
  rds_port = split(":", var.rds_writer_endpoint)[1]

  spring_datasource_url = "jdbc:postgresql://${local.rds_host}:${local.rds_port}/${var.rds_database_name}"
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "slpa-${var.environment}-backend"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  task_role_arn      = aws_iam_role.backend_task.arn
  execution_role_arn = aws_iam_role.backend_task_execution.arn

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = "${aws_ecr_repository.backend.repository_url}:${var.backend_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_DATASOURCE_URL", value = local.spring_datasource_url },
        { name = "SPRING_DATA_REDIS_HOST", value = var.redis_primary_endpoint },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "CORS_ALLOWED_ORIGIN", value = var.cors_allowed_origin },
        { name = "SLPA_STORAGE_BUCKET", value = var.storage_bucket_name },
        { name = "AWS_REGION", value = "us-east-1" },
        { name = "SLPA_WEB_BASE_URL", value = var.web_base_url },
        { name = "SERVER_FORWARD_HEADERS_STRATEGY", value = "framework" },
        { name = "LOGGING_LEVEL_ROOT", value = "WARN" },
        { name = "LOGGING_LEVEL_COM_SLPARCELAUCTIONS", value = "INFO" },
        { name = "LOGGING_LEVEL_ORG_FLYWAYDB", value = "INFO" },
      ]

      secrets = [
        # Always-required (Terraform-managed)
        { name = "SPRING_DATASOURCE_USERNAME", valueFrom = var.rds_username_ssm_arn },
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = var.rds_password_ssm_arn },
        { name = "SPRING_DATA_REDIS_PASSWORD", valueFrom = var.redis_auth_token_ssm_arn },
        # User-bootstrap (spec §7.3) — these must exist in Parameter Store
        # before desired_count > 0 or the task fails to start.
        { name = "JWT_SECRET", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/jwt/secret" },
        { name = "SLPA_BOT_SHARED_SECRET", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/bot/shared-secret" },
        { name = "SLPA_PRIMARY_ESCROW_UUID", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/sl/primary-escrow-uuid" },
        { name = "SLPA_SL_TRUSTED_OWNER_KEYS", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/sl/trusted-owner-keys" },
        { name = "SLPA_ESCROW_TERMINAL_SHARED_SECRET", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/escrow/terminal-shared-secret" },
        { name = "SL_IM_DISPATCHER_SECRET", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/notifications/sl-im/dispatcher-secret" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.backend.name
          awslogs-region        = "us-east-1"
          awslogs-stream-prefix = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8080/api/v1/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        # Spring Boot 4 + Java 26 + the SLPA bean graph takes ~150-180s to
        # finish context init on a 0.25 vCPU Fargate task. startPeriod=180
        # gives the JVM headroom to bind 8080 before failed checks count
        # toward the unhealthy threshold. With 0.5 vCPU the real boot is
        # ~60-90s but the same 180 ceiling is fine — startPeriod is a
        # MAXIMUM grace window, not a fixed delay.
        startPeriod = 180
      }
    }
  ])

  tags = {
    Name = "slpa-${var.environment}-backend-task"
  }
}

data "aws_caller_identity" "current" {}

resource "aws_ecs_service" "backend" {
  name            = "slpa-${var.environment}-backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.backend_desired_count
  launch_type     = "FARGATE"

  enable_execute_command = true # ECS Exec
  propagate_tags         = "SERVICE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.backend_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # NOTE: ignore_changes on task_definition + desired_count is intentionally
  # NOT set today. Terraform owns both fields. When GHA wires CodeDeploy
  # blue/green deploys (Step 17 of the deploy flow), this resource flips
  # deployment_controller to CODE_DEPLOY and adds the ignore_changes — at
  # that point Terraform cedes ownership of the running task def revision
  # to the deployment tool. Until then, Terraform-driven task def updates
  # work the way you'd expect: bump CPU/memory/env in code -> plan -> apply
  # rolls a new revision and ECS deploys it.

  depends_on = [aws_lb_listener.https]

  tags = {
    Name = "slpa-${var.environment}-backend-service"
  }
}
