################################################################################
# S3 storage bucket (spec §4.7)
#
# Single bucket with key prefixes — matches StorageConfigProperties in the
# backend (single 'bucket' field; existing code writes under prefixes
# 'avatars/', 'listing-photos/', 'dispute-evidence/').
#
# Versioning is ON (required for dispute-evidence prefix recovery; storage
# cost is small at SLPA scale). Two prefix-scoped lifecycle rules:
#   1. dispute-evidence/  -> Glacier Deep Archive at 365 days, expire at 7yrs
#   2. (all prefixes) non-current versions -> delete after 90 days
#
# The Terraform state bucket (slpa-prod-tfstate) is intentionally NOT managed
# here — it was bootstrapped manually per PREP.md §9 because Terraform can't
# create its own backend.
################################################################################

resource "aws_s3_bucket" "storage" {
  bucket        = "slpa-${var.environment}-storage"
  force_destroy = false # protect against accidental terraform destroy

  tags = {
    Name = "slpa-${var.environment}-storage"
  }
}

resource "aws_s3_bucket_versioning" "storage" {
  bucket = aws_s3_bucket.storage.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "storage" {
  bucket = aws_s3_bucket.storage.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "storage" {
  bucket = aws_s3_bucket.storage.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "storage_tls_only" {
  bucket = aws_s3_bucket.storage.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.storage.arn,
          "${aws_s3_bucket.storage.arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })

  # Ensure public access block lands first — bucket policy can fail if AWS
  # detects the policy could grant public access while public-access-block
  # isn't yet in place.
  depends_on = [aws_s3_bucket_public_access_block.storage]
}

resource "aws_s3_bucket_lifecycle_configuration" "storage" {
  bucket = aws_s3_bucket.storage.id

  # Rule 1: dispute-evidence prefix -> Glacier at 1 year, delete at 7 years.
  rule {
    id     = "dispute-evidence-glacier-then-expire"
    status = "Enabled"

    filter {
      prefix = "dispute-evidence/"
    }

    transition {
      days          = 365
      storage_class = "DEEP_ARCHIVE"
    }

    expiration {
      days = 2555 # 7 years
    }
  }

  # Rule 2: non-current versions across all prefixes -> delete after 90 days.
  rule {
    id     = "expire-non-current-versions"
    status = "Enabled"

    filter {} # applies to all objects

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
