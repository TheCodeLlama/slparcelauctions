################################################################################
# ECS cluster (spec §4.2)
#
# Single cluster hosts both backend + bot services. Container Insights ON
# (spec Q11 = B baseline; ~$3/mo flat).
################################################################################

resource "aws_ecs_cluster" "main" {
  name = "slpa-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Name = "slpa-${var.environment}-cluster"
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name = aws_ecs_cluster.main.name

  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 1
  }
}
