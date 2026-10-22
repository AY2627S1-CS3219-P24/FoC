locals {
  region = "ap-southeast-1"

  service_settings = {
    user = {
      port       = 8080
      cpu        = 256
      memory     = 768
      jwt        = true
      mail       = true
      bucket_env = "FOC_USER_BUCKET"
    }

    supplier = {
      port       = 8081
      cpu        = 256
      memory     = 640
      jwt        = false
      mail       = false
      bucket_env = "FOC_SUPPLIER_BUCKET"
    }

    order = {
      port       = 8082
      cpu        = 512
      memory     = 640
      jwt        = false
      mail       = false
      bucket_env = null
    }

    credit = {
      port       = 8083
      cpu        = 256
      memory     = 512
      jwt        = false
      mail       = false
      bucket_env = null
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
