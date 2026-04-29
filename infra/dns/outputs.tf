output "zone_id_slparcels_com" {
  description = "Route 53 hosted zone ID for slparcels.com. Used in PR-6d for ALIAS records to Amplify + ACM validation records."
  value       = aws_route53_zone.slparcels_com.zone_id
}

output "zone_id_slpa_app" {
  description = "Route 53 hosted zone ID for slpa.app. Used in PR-6d for ALIAS to ALB + ACM validation records."
  value       = aws_route53_zone.slpa_app.zone_id
}

# ----- Nameservers (CRITICAL - paste these into Namecheap) ----------------- #

output "nameservers_slparcels_com" {
  description = "AWS-assigned nameservers for slparcels.com. Replace Namecheap's NS records with these 4 entries to delegate DNS authority."
  value       = aws_route53_zone.slparcels_com.name_servers
}

output "nameservers_slpa_app" {
  description = "AWS-assigned nameservers for slpa.app. Replace Namecheap's NS records with these 4 entries to delegate DNS authority."
  value       = aws_route53_zone.slpa_app.name_servers
}

# ----- ACM certificate ARNs (consumed by ALB + Amplify) -------------------- #

output "cert_arn_slpa_app" {
  description = "ACM cert ARN for *.slpa.app + apex slpa.app. Attach to the ALB HTTPS listener."
  value       = aws_acm_certificate_validation.slpa_app.certificate_arn
}

output "cert_arn_slparcels_com" {
  description = "ACM cert ARN for slparcels.com + www.slparcels.com. Attach to Amplify custom domain."
  value       = aws_acm_certificate_validation.slparcels_com.certificate_arn
}
