################################################################################
# Backend IAM (spec §4.2)
#
# Two roles per ECS service convention:
#   - task role:        identity of the running container (app permissions)
#   - execution role:   identity ECS uses to launch the task (ECR pull, log
#                       group writes, Parameter Store secret fetch for the
#                       `secrets[]` block on the task definition)
################################################################################

# ----- Task role ------------------------------------------------------------ #

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend_task" {
  name               = "slpa-${var.environment}-backend-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name = "slpa-${var.environment}-backend-task-role"
  }
}

data "aws_iam_policy_document" "backend_task" {
  # S3 storage bucket
  statement {
    sid     = "S3StorageRW"
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [
      "${var.storage_bucket_arn}/*",
    ]
  }
  statement {
    sid       = "S3StorageList"
    actions   = ["s3:ListBucket"]
    resources = [var.storage_bucket_arn]
  }

  # Parameter Store reads at runtime (the app reads non-injected params via SDK
  # if it ever wants to; secrets[] injection on the task def covers env vars).
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

  # ECS Exec — interactive shell into a running task via SSM Session Manager.
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

  # CloudWatch Logs writes
  statement {
    sid = "CloudWatchLogs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.backend.arn}:*"]
  }
}

resource "aws_iam_role_policy" "backend_task" {
  name   = "slpa-${var.environment}-backend-task-policy"
  role   = aws_iam_role.backend_task.id
  policy = data.aws_iam_policy_document.backend_task.json
}

# ----- Execution role ------------------------------------------------------- #

resource "aws_iam_role" "backend_task_execution" {
  name               = "slpa-${var.environment}-backend-task-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name = "slpa-${var.environment}-backend-task-execution-role"
  }
}

# Standard managed policy for ECR pull + log group + log writes.
resource "aws_iam_role_policy_attachment" "backend_task_execution_managed" {
  role       = aws_iam_role.backend_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Plus SSM read for secrets[] injection on the task definition.
data "aws_iam_policy_document" "backend_task_execution_ssm" {
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

resource "aws_iam_role_policy" "backend_task_execution_ssm" {
  name   = "slpa-${var.environment}-backend-task-execution-ssm"
  role   = aws_iam_role.backend_task_execution.id
  policy = data.aws_iam_policy_document.backend_task_execution_ssm.json
}
