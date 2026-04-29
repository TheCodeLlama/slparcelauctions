################################################################################
# Bot pool ECS services (spec §4.3)
#
# One ECS service per active bot (slpa-bot-1, slpa-bot-2, ...). Each service
# has desired_count = 1 — named workers can't double-run with the same SL
# credentials. var.bot_active_count controls how many of the 5-bot pool are
# instantiated at this deploy (launch-lite default = 2).
#
# Each bot's task definition pulls its own credentials via secrets[] from
# /slpa/${env}/bot-N/{username,password,uuid}. The shared bot/backend secret
# at /slpa/${env}/bot/shared-secret is injected into every bot's
# Backend__SharedSecret env var.
#
# Bots have no public exposure (no ALB target group, no Route 53 record per
# spec Q10a = B). Health checks via container healthCheck only; ad-hoc
# inspection via ECS Exec.
################################################################################

locals {
  # Build a set of stringified bot indices [1, 2, ...] up to var.bot_active_count.
  active_bots = toset([for i in range(1, var.bot_active_count + 1) : tostring(i)])
}

# ----- Per-bot CloudWatch log groups --------------------------------------- #

resource "aws_cloudwatch_log_group" "bot" {
  for_each = local.active_bots

  name              = "/aws/ecs/slpa-bot-${each.value}"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "slpa-${var.environment}-logs-bot-${each.value}"
  }
}

# ----- Per-bot task definitions -------------------------------------------- #

resource "aws_ecs_task_definition" "bot" {
  for_each = local.active_bots

  family                   = "slpa-${var.environment}-bot-${each.value}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.bot_cpu
  memory                   = var.bot_memory

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  task_role_arn      = aws_iam_role.bot_task.arn
  execution_role_arn = aws_iam_role.bot_task_execution.arn

  container_definitions = jsonencode([
    {
      name      = "bot"
      image     = "${aws_ecr_repository.bot.repository_url}:${var.bot_image_tag}"
      essential = true

      # Bot exposes /health on 8081 for the container healthcheck. Not
      # registered to any ALB target group — health is internal-only per
      # spec Q10a = B.
      portMappings = [
        {
          containerPort = 8081
          protocol      = "tcp"
        }
      ]

      environment = [
        # Note: ASP.NET uses '__' (double underscore) as the section separator
        # for env-var binding. So Bot__Username binds to Bot:Username in the
        # appsettings hierarchy.
        { name = "Bot__StartLocation", value = "last" },
        { name = "Backend__BaseUrl", value = "https://${var.domain_slpa_app}" },
        { name = "RateLimit__TeleportsPerMinute", value = "6" },
        { name = "Logging__LogLevel__Default", value = "Warning" },
        { name = "Logging__LogLevel__Slpa", value = "Information" },
      ]

      secrets = [
        # Per-bot credentials — only this bot sees its own creds.
        { name = "Bot__Username", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/bot-${each.value}/username" },
        { name = "Bot__Password", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/bot-${each.value}/password" },
        { name = "Bot__BotUuid", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/bot-${each.value}/uuid" },
        # Shared backend bearer secret — same value across all bots.
        { name = "Backend__SharedSecret", valueFrom = "arn:aws:ssm:us-east-1:${data.aws_caller_identity.current.account_id}:parameter/slpa/${var.environment}/bot/shared-secret" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.bot[each.value].name
          awslogs-region        = "us-east-1"
          awslogs-stream-prefix = "ecs"
        }
      }

      healthCheck = {
        # Bot's ASP.NET /health endpoint (NOT /api/v1/health like the backend
        # — the bot is its own ASP.NET host on port 8081, not a Spring app).
        command  = ["CMD-SHELL", "curl -f http://localhost:8081/health || exit 1"]
        interval = 30
        timeout  = 5
        retries  = 3
        # SL grid login takes ~10-30s of network round-trips on cold start.
        # 120s gives headroom for slow grid days.
        startPeriod = 120
      }
    }
  ])

  tags = {
    Name = "slpa-${var.environment}-bot-${each.value}-task"
  }
}

# ----- Per-bot ECS services ------------------------------------------------- #

resource "aws_ecs_service" "bot" {
  for_each = local.active_bots

  name            = "slpa-${var.environment}-bot-${each.value}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.bot[each.value].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  enable_execute_command = true
  propagate_tags         = "SERVICE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.bots_security_group_id]
    assign_public_ip = false
  }

  # Bot is a stateful single-task service (named SL credentials). Rolling
  # deploy with min=0 / max=100 means the old task drains before the new
  # task starts — no SL grid double-login. Brief downtime per deploy is
  # acceptable; the bot is best-effort observer, not on the auction-close
  # critical path.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  tags = {
    Name = "slpa-${var.environment}-bot-${each.value}-service"
  }
}
