################################################################################
# Route 53 hosted zones (spec §4.9)
#
# Two zones created here:
#   - slparcels.com  (frontend; Amplify target - records added in PR-6d)
#   - slpa.app       (backend; ALB ALIAS - records added in PR-6d)
#
# slparcelauctions.com intentionally NOT here - that domain stays on
# Namecheap DNS and uses Namecheap's URL Redirect feature (spec Q10b).
#
# After this PR applies, the operator MUST update nameservers at Namecheap
# for both domains to delegate DNS authority to Route 53. The NS records
# to use are exposed as outputs.
################################################################################

resource "aws_route53_zone" "slparcels_com" {
  name = var.domain_slparcels

  comment = "SLPA frontend apex - Amplify-fronted, NS-delegated from Namecheap."

  tags = {
    Name = "slpa-${var.environment}-zone-slparcels-com"
  }
}

resource "aws_route53_zone" "slpa_app" {
  name = var.domain_slpa_app

  comment = "SLPA backend apex - ALB ALIAS, NS-delegated from Namecheap."

  tags = {
    Name = "slpa-${var.environment}-zone-slpa-app"
  }
}
