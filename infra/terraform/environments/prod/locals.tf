locals {
  service_settings = {
    user = {
      port       = 8080
      cpu        = 256
      memory     = 768
      jwt        = true
      mail       = true
      bucket_env = "FOC_USER_BUCKET"
      scalable   = false
    }
    supplier = {
      port       = 8081
      cpu        = 256
      memory     = 640
      jwt        = false
      mail       = false
      bucket_env = "FOC_SUPPLIER_BUCKET"
      scalable   = false
    }
    order = {
      port       = 8082
      cpu        = 512
      memory     = 640
      jwt        = false
      mail       = false
      bucket_env = null
      scalable   = true
    }
    credit = {
      port       = 8083
      cpu        = 256
      memory     = 512
      jwt        = false
      mail       = false
      bucket_env = null
      scalable   = false
    }
  }
  services = {
    for name, service in local.service_settings : name => merge(service, {
      database = "foc_${name}"
      username = "foc_${name}"
    })
  }
  messaging_services = toset(["user", "supplier", "order", "credit"])
}

check "image_bucket_names_match_services" {
  assert {
    condition     = toset(keys(var.image_bucket_names)) == toset([for name, service in local.services : name if service.bucket_env != null])
    error_message = "image_bucket_names must name exactly the services that set bucket_env."
  }
}
