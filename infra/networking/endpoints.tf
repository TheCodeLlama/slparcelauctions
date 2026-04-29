################################################################################
# VPC endpoints
#
# S3 gateway endpoint is free + always-on. Routes Fargate→S3 traffic without
# traversing NAT, saving NAT egress cost on photo/avatar/dispute-evidence
# read+write paths (the highest-volume internal traffic).
#
# Interface endpoints (ECR, Secrets Manager, CloudWatch) are paid (~$7/mo
# each per AZ) and gated behind a future variable when NAT egress costs
# justify them. Not in this PR.
################################################################################

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = {
    Name = "slpa-${var.environment}-vpce-s3"
  }
}

data "aws_region" "current" {}
