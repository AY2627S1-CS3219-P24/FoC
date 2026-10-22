name_prefix = "foc-prod"

image_bucket_names = {
  user     = "foc-prod-user"
  supplier = "foc-prod-supplier"
}

domain_name        = "cs3219-p24.mooo.com"
acme_email         = "kristupas.arifovas@gmail.com"
mail_from          = "kristupas.arifovas@gmail.com"
start_services     = false
core_instance_type = "t3a.medium"
db_instance_class  = "db.t4g.micro"
event_subscriptions = {
  user_created = {
    publisher   = "user"
    consumer    = "credit"
    event_types = ["UserCreatedEvent"]
  }
  errand_settlement = {
    publisher   = "order"
    consumer    = "credit"
    event_types = ["ErrandCancelledEvent", "ErrandExpiredEvent", "ErrandCompletedEvent"]
  }
  credit_settlement_result = {
    publisher   = "credit"
    consumer    = "order"
    event_types = ["CreditSettlementResultEvent"]
  }
}
