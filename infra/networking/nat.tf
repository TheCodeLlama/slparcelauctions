################################################################################
# NAT egress
#
# nat_type = "instance"  → fck-nat on t4g.nano in single AZ (~$5/mo).
# nat_type = "gateway"   → managed NAT Gateways (~$32/mo each, ×2 for HA).
#
# Launch-lite default is "instance" — see spec §4.1 / §5 (upgrade lever).
#
# fck-nat (https://fck-nat.dev) is a community-maintained NAT instance AMI
# that auto-discovers its route table, attaches the default route, and
# self-heals via ASG. We use the official Terraform module for it because
# it abstracts the IAM role, security group, launch template, and ASG
# scaffolding into ~10 lines of caller config.
################################################################################

# ----- nat_type = "instance" (fck-nat) -------------------------------------- #

module "fck_nat" {
  count = var.nat_type == "instance" ? 1 : 0

  source  = "RaJiska/fck-nat/aws"
  version = "~> 1.3"

  name          = "slpa-${var.environment}-nat"
  vpc_id        = aws_vpc.main.id
  subnet_id     = aws_subnet.public[0].id # NAT lives in AZ a public subnet
  instance_type = "t4g.nano"              # ARM, ~$3/mo (NAT instance is the one place ARM is safe — no JNI/native deps in the NAT routing path)

  # Have the module add a default route from a single private RT through the
  # NAT instance. update_route_tables = true wires that automatically.
  update_route_tables = true
  route_tables_ids = {
    private = aws_route_table.private.id
  }

  tags = {
    Name = "slpa-${var.environment}-nat-instance"
  }
}

# ----- nat_type = "gateway" (managed NAT GW × 2 for HA) --------------------- #

resource "aws_eip" "nat" {
  count = var.nat_type == "gateway" ? length(var.availability_zones) : 0

  domain = "vpc"

  tags = {
    Name = "slpa-${var.environment}-nat-eip-${var.availability_zones[count.index]}"
  }
}

resource "aws_nat_gateway" "main" {
  count = var.nat_type == "gateway" ? length(var.availability_zones) : 0

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = {
    Name = "slpa-${var.environment}-nat-gw-${var.availability_zones[count.index]}"
  }

  depends_on = [aws_internet_gateway.main]
}

# When using managed NAT GWs, the single private route table approach above
# breaks the AZ-affinity benefit (a private subnet in AZ b would still route
# through the NAT GW in AZ a). For "gateway" mode we add per-AZ private
# route tables and reassociate. This is a no-op when nat_type = "instance".
#
# At launch-lite this branch is dormant; activated via .tfvars flip.
resource "aws_route_table" "private_per_az" {
  count = var.nat_type == "gateway" ? length(var.availability_zones) : 0

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id
  }

  tags = {
    Name = "slpa-${var.environment}-rt-private-${var.availability_zones[count.index]}"
  }
}

# Note: when flipping to nat_type = "gateway", the operator must also
# manually disassociate the original aws_route_table.private from the
# private subnets and associate the per-AZ route tables instead. Terraform
# handles this in a follow-up plan because aws_route_table_association is
# already declared in vpc.tf bound to aws_route_table.private — the upgrade
# task should rewrite that association block to switch on nat_type.
