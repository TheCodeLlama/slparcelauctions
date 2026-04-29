################################################################################
# Security groups (spec §4.1)
#
# Five SGs — alb, backend, bots, rds, redis. The NAT SG is created by the
# fck-nat module (when nat_type = "instance") so we don't redefine it here.
#
# Ingress rules use security_group_id references rather than CIDR blocks —
# AWS-native pattern, easier to reason about than IP ranges.
################################################################################

# ----- ALB: public-facing HTTPS ingress ------------------------------------ #

resource "aws_security_group" "alb" {
  name        = "slpa-${var.environment}-alb"
  description = "Public ingress to the SLPA ALB (80/443 from world). Egress to backend tasks only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https_from_world" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS from anywhere"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http_from_world" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "HTTP from anywhere (redirect to 443 at the ALB listener)"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_backend" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.backend.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "ALB to backend tasks on port 8080"
}

# ----- Backend: receives only from ALB -------------------------------------- #

resource "aws_security_group" "backend" {
  name        = "slpa-${var.environment}-backend"
  description = "Backend Fargate tasks. Ingress from ALB only; egress to RDS, Redis, internet (via NAT)."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-backend"
  }
}

resource "aws_vpc_security_group_ingress_rule" "backend_from_alb" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "From ALB on application port"
}

resource "aws_vpc_security_group_egress_rule" "backend_to_rds" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.rds.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Backend to RDS Postgres"
}

resource "aws_vpc_security_group_egress_rule" "backend_to_redis" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.redis.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "Backend to Redis"
}

resource "aws_vpc_security_group_egress_rule" "backend_to_internet_https" {
  security_group_id = aws_security_group.backend.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "Backend to SL World API + AWS APIs (Parameter Store, S3) via NAT"
}

resource "aws_vpc_security_group_egress_rule" "backend_to_internet_http" {
  security_group_id = aws_security_group.backend.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "Backend egress on port 80 for SL World API HTTP redirect handling during retries"
}

# ----- Bots: no inbound; egress to backend (ALB-fronted) + internet --------- #

resource "aws_security_group" "bots" {
  name        = "slpa-${var.environment}-bots"
  description = "Bot Fargate tasks. No inbound. Egress to ALB (backend calls) + internet (SL grid via NAT)."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-bots"
  }
}

resource "aws_vpc_security_group_egress_rule" "bots_to_alb_https" {
  security_group_id            = aws_security_group.bots.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "Bots to backend via ALB (claim/verify/monitor callbacks)"
}

resource "aws_vpc_security_group_egress_rule" "bots_to_internet_all" {
  security_group_id = aws_security_group.bots.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 0
  to_port           = 65535
  ip_protocol       = "tcp"
  description       = "Bots to SL grid (LibreMetaverse uses arbitrary high TCP ports for sim connections) + AWS APIs"
}

resource "aws_vpc_security_group_egress_rule" "bots_to_internet_udp" {
  security_group_id = aws_security_group.bots.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 0
  to_port           = 65535
  ip_protocol       = "udp"
  description       = "Bots to SL grid UDP (LibreMetaverse uses UDP for sim message channel)"
}

# ----- RDS: only from backend + bots ---------------------------------------- #

resource "aws_security_group" "rds" {
  name        = "slpa-${var.environment}-rds"
  description = "RDS Postgres. Inbound 5432 from backend + bots only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-rds"
  }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_backend" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.backend.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "From backend tasks"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_bots" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.bots.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "From bots (RDS direct read; bot direct DB access is not used today but reserved for future BOT-tier monitor optimisations)"
}

# ----- Redis: only from backend --------------------------------------------- #

resource "aws_security_group" "redis" {
  name        = "slpa-${var.environment}-redis"
  description = "ElastiCache Redis. Inbound 6379 from backend only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-redis"
  }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_backend" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = aws_security_group.backend.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "From backend tasks"
}
