name_prefix = "foc-prod"

image_bucket_names = {
  user     = "foc-prod-user"
  supplier = "foc-prod-supplier"
}

domain_name         = "foc.example.edu"
acme_email          = "team@example.edu"
mail_from           = "noreply@mail.example.edu"
image_tag           = "bootstrap"
start_services      = false
core_instance_type  = "t3a.medium"
db_instance_class   = "db.t4g.micro"
event_subscriptions = {}
