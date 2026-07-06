# One repository per deployable — EKS worker nodes can already pull from any
# repo in this account without a Kubernetes imagePullSecret (the node IAM
# role in ../eks already has AmazonEC2ContainerRegistryReadOnly attached).
resource "aws_ecr_repository" "this" {
  for_each = toset(var.repository_names)

  name                 = "nectrix/${each.value}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name = "${var.name_prefix}-ecr-${each.value}"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after ${var.untagged_expiry_days} days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_expiry_days
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the most recent ${var.max_tagged_images} images (tags are raw commit SHAs, no fixed prefix to filter on)"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.max_tagged_images
        }
        action = { type = "expire" }
      }
    ]
  })
}
