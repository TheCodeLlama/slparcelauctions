variable "environment" {
  description = "Environment name (used in resource naming + tags)."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block (/16 recommended)."
  type        = string
}

variable "availability_zones" {
  description = "AZs for subnet placement (length must be exactly 2 — one public + one private subnet per AZ)."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Networking module is wired for exactly 2 AZs (RDS subnet group requires >=2 even at launch-lite single-AZ)."
  }
}

variable "nat_type" {
  description = "NAT shape — 'instance' (fck-nat single AZ, ~$5/mo) or 'gateway' (×2 NAT GWs, ~$64/mo)."
  type        = string

  validation {
    condition     = contains(["instance", "gateway"], var.nat_type)
    error_message = "nat_type must be 'instance' or 'gateway'."
  }
}
