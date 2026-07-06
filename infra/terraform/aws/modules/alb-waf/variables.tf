variable "name_prefix" {
  type = string
}

variable "alb_arn" {
  description = "ARN of the ALB to associate the WebACL with — the AWS Load Balancer Controller creates this from Kubernetes Ingress objects, so it doesn't exist until after a real apply + Ingress creation. Leave null to create the WebACL only (association done as a follow-up apply once the ALB exists)."
  type        = string
  default     = null
}
