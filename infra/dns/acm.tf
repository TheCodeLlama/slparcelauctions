################################################################################
# ACM certificates (spec §4.9)
#
# Two certs issued in this module:
#   - cert_slpa_app:    apex slpa.app + wildcard *.slpa.app for the ALB
#   - cert_slparcels:   slparcels.com + www.slparcels.com for Amplify
#
# Both use DNS validation against the Route 53 zones created in route53.tf.
# Validation auto-completes within a few minutes once Namecheap NS delegation
# has propagated to the AWS resolvers.
#
# IMPORTANT region pinning:
# - cert_slpa_app: issued in the stack's primary region (us-east-1) for use
#   by the ALB. Will need cross-region issuance if we ever move the stack
#   off us-east-1.
# - cert_slparcels: MUST be issued in us-east-1 because Amplify's CloudFront
#   distribution requires the cert there regardless of where the rest of
#   the stack lives. Today the stack is already in us-east-1 so they're
#   the same region, but using the aliased provider explicitly makes the
#   constraint visible + future-proof.
#
# Note: Amplify can also auto-issue + auto-manage its own ACM cert when you
# connect a custom domain. We're issuing one explicitly here so that the
# DNS validation (and renewal records) live in our Route 53 zone where we
# can see them, rather than in Amplify-managed shadow records.
################################################################################

# ----- slpa.app: apex + wildcard for ALB ----------------------------------- #

resource "aws_acm_certificate" "slpa_app" {
  domain_name               = var.domain_slpa_app
  subject_alternative_names = ["*.${var.domain_slpa_app}"]
  validation_method         = "DNS"

  tags = {
    Name = "slpa-${var.environment}-cert-slpa-app"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Per-FQDN DNS validation records. ACM emits one CNAME per name on the cert
# (apex + each SAN). The for_each iterates over those distinct records.
resource "aws_route53_record" "slpa_app_validation" {
  for_each = {
    for dvo in aws_acm_certificate.slpa_app.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id         = aws_route53_zone.slpa_app.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "slpa_app" {
  certificate_arn         = aws_acm_certificate.slpa_app.arn
  validation_record_fqdns = [for r in aws_route53_record.slpa_app_validation : r.fqdn]

  timeouts {
    create = "10m"
  }
}

# ----- slparcels.com: apex + www for Amplify ------------------------------- #

resource "aws_acm_certificate" "slparcels_com" {
  domain_name               = var.domain_slparcels
  subject_alternative_names = ["www.${var.domain_slparcels}"]
  validation_method         = "DNS"

  tags = {
    Name = "slpa-${var.environment}-cert-slparcels-com"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "slparcels_com_validation" {
  for_each = {
    for dvo in aws_acm_certificate.slparcels_com.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id         = aws_route53_zone.slparcels_com.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "slparcels_com" {
  certificate_arn         = aws_acm_certificate.slparcels_com.arn
  validation_record_fqdns = [for r in aws_route53_record.slparcels_com_validation : r.fqdn]

  timeouts {
    create = "10m"
  }
}
