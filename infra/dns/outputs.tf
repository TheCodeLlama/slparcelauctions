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
