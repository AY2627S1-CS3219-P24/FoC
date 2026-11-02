# Keep these moves until every environment has migrated from the ECS service module.

moved {
  from = module.app["user"].aws_ecs_task_definition.this[0]
  to   = aws_ecs_task_definition.app["user"]
}

moved {
  from = module.app["supplier"].aws_ecs_task_definition.this[0]
  to   = aws_ecs_task_definition.app["supplier"]
}

moved {
  from = module.app["order"].aws_ecs_task_definition.this[0]
  to   = aws_ecs_task_definition.app["order"]
}

moved {
  from = module.app["credit"].aws_ecs_task_definition.this[0]
  to   = aws_ecs_task_definition.app["credit"]
}

moved {
  from = module.app["realtime"].aws_ecs_task_definition.this[0]
  to   = aws_ecs_task_definition.app["realtime"]
}
