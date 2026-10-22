terraform {
  backend "s3" {
    key          = "foc/terraform.tfstate"
    region       = "ap-southeast-1"
    encrypt      = true
    use_lockfile = true
  }
}
