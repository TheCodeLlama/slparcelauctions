################################################################################
# GitHub Actions deploy role (OIDC-trusted)
################################################################################

# OIDC provider was created out-of-band (PREP.md §10). Reference, don't create.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "gha_assume_role" {
  statement {
    sid     = "GitHubOIDC"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    # Standard GitHub OIDC audience claim.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Repo + branch scoping. Only workflows running on the configured branch
    # of the configured repo can assume this role.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/${var.github_branch_for_deploy}"]
    }
  }
}

resource "aws_iam_role" "gha_deploy" {
  name               = "slpa-${var.environment}-gha-deploy"
  description        = "Assumed by GitHub Actions on push to ${var.github_branch_for_deploy} to deploy backend + bots."
  assume_role_policy = data.aws_iam_policy_document.gha_assume_role.json

  tags = {
    Name = "slpa-${var.environment}-gha-deploy"
  }
}

# ----- Permissions ---------------------------------------------------------- #

data "aws_iam_policy_document" "gha_deploy" {
  # ECR auth is account-wide (the GetAuthorizationToken API doesn't accept a
  # resource ARN); push/pull are scoped to our two repos.
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "EcrPushPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListImages",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [
      var.ecr_backend_repo_arn,
      var.ecr_bot_repo_arn,
    ]
  }

  # Register a new task definition revision pointing at the just-pushed image.
  # RegisterTaskDefinition + DescribeTaskDefinition do not support resource-
  # level permissions, so they're scoped to "*" — this is the documented AWS
  # constraint, not a permission gap.
  statement {
    sid = "EcsTaskDefinition"
    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"]
  }

  # Update the live service to use the new task def revision. Scoped to the
  # cluster's services. UpdateService accepts service ARNs but the cluster ARN
  # is sufficient: AWS treats cluster ARN as the parent resource.
  statement {
    sid = "EcsServiceUpdate"
    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService",
    ]
    resources = [
      "arn:aws:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:service/slpa-${var.environment}/*",
    ]
  }

  # ECS RegisterTaskDefinition needs to attach the existing task + execution
  # roles to the new revision. iam:PassRole is the gate.
  statement {
    sid     = "PassEcsRoles"
    actions = ["iam:PassRole"]
    resources = [
      var.backend_task_role_arn,
      var.backend_task_execution_role_arn,
      var.bot_task_role_arn,
      var.bot_task_execution_role_arn,
    ]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # CodeDeploy hooks call ListServices on the cluster to discover targets.
  # Cheap to grant; needed for `aws ecs describe-services --cluster <arn>` from
  # workflows when iterating bot services.
  statement {
    sid       = "EcsListServices"
    actions   = ["ecs:ListServices"]
    resources = [var.ecs_cluster_arn]
  }
}

resource "aws_iam_role_policy" "gha_deploy" {
  name   = "slpa-${var.environment}-gha-deploy-policy"
  role   = aws_iam_role.gha_deploy.id
  policy = data.aws_iam_policy_document.gha_deploy.json
}
