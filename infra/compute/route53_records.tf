################################################################################
# Route 53 records pointing at the ALB (spec §4.9)
#
# slpa.app apex -> ALB ALIAS. Apex ALIAS works on .app zones because Route 53
# supports apex ALIAS records natively (sidesteps the DNS-spec restriction
# against CNAMEs at the apex).
#
# bots.slpa.app intentionally not created here — bot health endpoints stay
# private; ECS Exec covers ad-hoc checks (spec Q10a = B).
################################################################################

resource "aws_route53_record" "slpa_app_apex" {
  zone_id = var.zone_id_slpa_app
  name    = var.domain_slpa_app
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
