################################################################################
# Bot pool IAM (spec §4.3)
#
# One pair of roles (task + execution) shared across all bot ECS services.
# Per-bot credentials are scoped at the Parameter Store path level: each
# bot service injects only its own /slpa/${env}/bot-N/* params into the
# task env, so even though all bots share the IAM role, they only see
# their own credentials at runtime.
#
# Bots don't need S3 access (they don't write user-facing assets); they
# only need:
#   - SSM read for /slpa/${env}/bot/* (shared backend secret) and
#     /slpa/${env}/bot-N/* (per-bot creds)
#   - ECS Exec (so we can shell into a misbehaving bot)
#   - CloudWatch Logs writes
################################################################################

# ----- Task role ------------------------------------------------------------ #

resource "aws_iam_role" "bot_task" {
  name               = "slpa-${var.environment}-bot-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name = "slpa-${var.environment}-bot-task-role"
  }
}

data "aws_iam_policy_document" "bot_task" {
  # Parameter Store reads (shared bot secret + per-bot creds).
  statement {
    sid       = "ParameterStoreRead"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:*:*:parameter/slpa/${var.environment}/*"]
  }
  statement {
    sid       = "ParameterStoreKMS"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:*:*:alias/aws/ssm"]
  }

  # ECS Exec (interactive shell into a running task).
  statement {
    sid = "ECSExec"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }

  # CloudWatch Logs writes — wildcard across the per-bot log group ARNs
  # because the IAM role doesn't know which specific bot it'll be assigned
  # to. The log groups follow a known naming pattern.
  statement {
    sid     = "CloudWatchLogs"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      "arn:aws:logs:*:*:log-group:/aws/ecs/slpa-bot-*:*",
    ]
  }
}

resource "aws_iam_role_policy" "bot_task" {
  name   = "slpa-${var.environment}-bot-task-policy"
  role   = aws_iam_role.bot_task.id
  policy = data.aws_iam_policy_document.bot_task.json
}

# ----- Execution role ------------------------------------------------------- #

resource "aws_iam_role" "bot_task_execution" {
  name               = "slpa-${var.environment}-bot-task-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name = "slpa-${var.environment}-bot-task-execution-role"
  }
}

resource "aws_iam_role_policy_attachment" "bot_task_execution_managed" {
  role       = aws_iam_role.bot_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Plus SSM read for secrets[] injection on the per-bot task definitions.
data "aws_iam_policy_document" "bot_task_execution_ssm" {
  statement {
    sid       = "ParameterStoreRead"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:*:*:parameter/slpa/${var.environment}/*"]
  }
  statement {
    sid       = "ParameterStoreKMS"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:*:*:alias/aws/ssm"]
  }
}

resource "aws_iam_role_policy" "bot_task_execution_ssm" {
  name   = "slpa-${var.environment}-bot-task-execution-ssm"
  role   = aws_iam_role.bot_task_execution.id
  policy = data.aws_iam_policy_document.bot_task_execution_ssm.json
}
