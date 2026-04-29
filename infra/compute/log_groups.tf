################################################################################
# CloudWatch log groups (spec §4.12)
#
# Explicit creation with var.log_retention_days so groups don't default to
# the costly "never expire" CloudWatch behaviour.
################################################################################

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/aws/ecs/slpa-backend"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "slpa-${var.environment}-logs-backend"
  }
}
