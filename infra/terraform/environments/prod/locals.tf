locals {
  region = "ap-southeast-1"

  services = {
    user = {
      runtime    = "java"
      database   = "foc_user"
      username   = "foc_user"
      port       = 8080
      cpu        = 256
      memory     = 768
      jwt        = true
      mail       = true
      bucket_env = "FOC_USER_BUCKET"
    }

    supplier = {
      runtime    = "java"
      database   = "foc_supplier"
      username   = "foc_supplier"
      port       = 8081
      cpu        = 256
      memory     = 640
      bucket_env = "FOC_SUPPLIER_BUCKET"
    }

    order = {
      ai_enabled = true
      runtime    = "java"
      database   = "foc_order"
      username   = "foc_order"
      port       = 8082
      cpu        = 512
      memory     = 640
    }

    credit = {
      runtime  = "java"
      database = "foc_credit"
      username = "foc_credit"
      port     = 8083
      cpu      = 256
      memory   = 512
    }
    realtime = {
      port    = 8084
      cpu     = 128
      memory  = 256
      runtime = "node"
    }
  }
  event_publishers = toset(["user", "supplier", "order", "credit"])
  event_consumers  = toset(["user", "supplier", "order", "credit", "realtime"])
}
