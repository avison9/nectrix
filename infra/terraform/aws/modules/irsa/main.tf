locals {
  oidc_provider_host = replace(var.oidc_provider_url, "https://", "")

  # Condensed permission sets — enough to actually run each controller, not a
  # line-for-line copy of the full upstream IAM policy JSON (~450 lines for the
  # ALB controller alone). Tighten against the real upstream policy before a
  # real `terraform apply` (see infra/terraform/aws/README.md).
  cluster_autoscaler_actions = [
    "autoscaling:DescribeAutoScalingGroups",
    "autoscaling:DescribeAutoScalingInstances",
    "autoscaling:DescribeLaunchConfigurations",
    "autoscaling:DescribeTags",
    "autoscaling:SetDesiredCapacity",
    "autoscaling:TerminateInstanceInAutoScalingGroup",
    "ec2:DescribeLaunchTemplateVersions",
    "eks:DescribeNodegroup",
  ]

  alb_controller_actions = [
    "ec2:DescribeSubnets",
    "ec2:DescribeSecurityGroups",
    "ec2:DescribeVpcs",
    "elasticloadbalancing:DescribeLoadBalancers",
    "elasticloadbalancing:DescribeTargetGroups",
    "elasticloadbalancing:CreateLoadBalancer",
    "elasticloadbalancing:CreateTargetGroup",
    "elasticloadbalancing:RegisterTargets",
    "elasticloadbalancing:ModifyLoadBalancerAttributes",
    "wafv2:AssociateWebACL",
    "wafv2:GetWebACLForResource",
  ]

  roles = {
    cluster_autoscaler = {
      sa_namespace = "kube-system"
      sa_name      = "cluster-autoscaler"
      policy_arn   = aws_iam_policy.cluster_autoscaler.arn
    }
    aws_load_balancer_controller = {
      sa_namespace = "kube-system"
      sa_name      = "aws-load-balancer-controller"
      policy_arn   = aws_iam_policy.alb_controller.arn
    }
    app_storage_access = {
      sa_namespace = "core-app"
      sa_name      = "core-app"
      policy_arn   = var.s3_access_policy_arn
    }
  }
}

resource "aws_iam_policy" "cluster_autoscaler" {
  name = "${var.name_prefix}-cluster-autoscaler"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = local.cluster_autoscaler_actions
      Resource = "*"
    }]
  })
}

resource "aws_iam_policy" "alb_controller" {
  name = "${var.name_prefix}-alb-controller"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = local.alb_controller_actions
      Resource = "*"
    }]
  })
}

resource "aws_iam_role" "this" {
  for_each = local.roles

  name = "${var.name_prefix}-irsa-${each.key}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = var.oidc_provider_arn }
      Condition = {
        StringEquals = {
          "${local.oidc_provider_host}:sub" = "system:serviceaccount:${each.value.sa_namespace}:${each.value.sa_name}"
          "${local.oidc_provider_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "this" {
  for_each = local.roles

  role       = aws_iam_role.this[each.key].name
  policy_arn = each.value.policy_arn
}
