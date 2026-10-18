terraform {
  required_version = "~> 1.16.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.65.0" }
  }
  backend "s3" {}
}
provider "aws" { region = var.region }
