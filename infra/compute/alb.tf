################################################################################
# Application Load Balancer (spec §4.2)
#
# Public-facing HTTPS termination for the backend. Two listeners:
#   - 80  -> redirect to 443
#   - 443 -> forward to backend target group (HTTP 8080 internal)
#
# Bot tasks reach the backend via this ALB too (spec §4.3 — Backend__BaseUrl
# is the ALB internal DNS routed via slpa.app).
################################################################################

resource "aws_lb" "main" {
  name               = "slpa-${var.environment}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = true

  # idle_timeout default 60s is fine for SLParcels' request shape.
  drop_invalid_header_fields = true

  tags = {
    Name = "slpa-${var.environment}-alb"
  }
}

resource "aws_lb_target_group" "backend" {
  name                 = "slpa-${var.environment}-backend-tg"
  port                 = 8080
  protocol             = "HTTP"
  vpc_id               = var.vpc_id
  target_type          = "ip" # required for awsvpc network mode (Fargate)
  deregistration_delay = 30   # seconds; in-flight requests drain before a task is removed

  health_check {
    enabled             = true
    path                = "/api/v1/health"
    protocol            = "HTTP"
    port                = "traffic-port"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  tags = {
    Name = "slpa-${var.environment}-backend-tg"
  }
}

# HTTPS listener — primary entry. ALB's HSTS-style behaviour handled at the
# app level via Spring Security headers.
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.cert_arn_slpa_app

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  tags = {
    Name = "slpa-${var.environment}-listener-https"
  }
}

# HTTP listener — redirect everything to HTTPS.
resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }

  tags = {
    Name = "slpa-${var.environment}-listener-http-redirect"
  }
}
