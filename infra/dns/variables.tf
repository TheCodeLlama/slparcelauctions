variable "environment" {
  type = string
}

variable "domain_slparcels" {
  description = "Frontend apex (Amplify target). Spec §4.9."
  type        = string
}

variable "domain_slpa_app" {
  description = "Backend apex (ALB ALIAS target). Spec §4.9."
  type        = string
}
