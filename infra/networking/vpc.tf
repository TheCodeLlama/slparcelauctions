################################################################################
# VPC + subnets + IGW + route tables
#
# Layout (spec §4.1):
#   10.0.0.0/16 VPC
#     Public subnets:  10.0.0.0/24 (AZ a)  + 10.0.1.0/24 (AZ b)
#     Private subnets: 10.0.10.0/24 (AZ a) + 10.0.11.0/24 (AZ b)
#
# Both AZs get private subnets even at launch-lite (single-AZ data tier)
# because RDS subnet groups require ≥2 AZs for any future Multi-AZ flip
# without re-provisioning.
################################################################################

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "slpa-${var.environment}-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "slpa-${var.environment}-igw"
  }
}

# ----- Public subnets ------------------------------------------------------- #

resource "aws_subnet" "public" {
  count = length(var.availability_zones)

  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index) # 10.0.0.0/24, 10.0.1.0/24
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "slpa-${var.environment}-public-${var.availability_zones[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "slpa-${var.environment}-rt-public"
  }
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ----- Private subnets ------------------------------------------------------ #

resource "aws_subnet" "private" {
  count = length(var.availability_zones)

  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10) # 10.0.10.0/24, 10.0.11.0/24
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "slpa-${var.environment}-private-${var.availability_zones[count.index]}"
    Tier = "private"
  }
}

# Single private route table at launch-lite — both private subnets route
# through the same NAT instance in AZ a. Upgrade to per-AZ NAT Gateways
# means flipping nat_type = "gateway", which adds a second route table +
# AZ-affined NAT GW per subnet.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  # NAT route is added via aws_route in nat.tf to keep this resource clean
  # and let nat_type swap (instance vs. gateway) without rewriting the RT.

  tags = {
    Name = "slpa-${var.environment}-rt-private"
  }
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
