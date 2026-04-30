output "gha_deploy_role_arn" {
  description = "ARN of the IAM role GitHub Actions assumes via OIDC. Reference this from .github/workflows/*.yml in the role-to-assume input of aws-actions/configure-aws-credentials."
  value       = aws_iam_role.gha_deploy.arn
}

output "gha_deploy_role_name" {
  value = aws_iam_role.gha_deploy.name
}
