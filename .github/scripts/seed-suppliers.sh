#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/../.." && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

terraform -chdir="$repository_root/infra/terraform/environments/prod" output -json deployment > "$work_dir/deployment.json"

cluster=$(jq -er '.cluster' "$work_dir/deployment.json")
AWS_REGION=$(jq -er '.region' "$work_dir/deployment.json")
export AWS_REGION

supplier_revision=$(aws ecs describe-services --cluster "$cluster" --services supplier \
  --query 'services[0].taskDefinition' --output text)
image=$(aws ecs describe-task-definition --task-definition "$supplier_revision" \
  --query "taskDefinition.containerDefinitions[?name=='supplier'].image | [0]" --output text)

jq --arg image "$image" '
  .supplier_seed_task_definition | .containerDefinitions[0].image = $image
' "$work_dir/deployment.json" > "$work_dir/task-definition.json"

task_definition=$(aws ecs register-task-definition --cli-input-json "file://$work_dir/task-definition.json" \
  --query 'taskDefinition.taskDefinitionArn' --output text)

aws ecs run-task --cluster "$cluster" --task-definition "$task_definition" > "$work_dir/task.json"

jq '{tasks: [.tasks[].taskArn], failures}' "$work_dir/task.json"
task=$(jq -er '.tasks[0].taskArn' "$work_dir/task.json")
log_group=$(jq -r '.containerDefinitions[0].logConfiguration.options["awslogs-group"]' "$work_dir/task-definition.json")
echo "Supplier seed logs: $log_group (stream supplier-seed/supplier-seed/${task##*/})"

aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$task"
aws ecs describe-tasks --cluster "$cluster" --tasks "$task" > "$work_dir/result.json"
jq '.tasks[] | {taskArn, stoppedReason, containers: [.containers[] | {name, exitCode, reason}]}' "$work_dir/result.json"
jq -e '.tasks[0].containers[0].exitCode == 0' "$work_dir/result.json" >/dev/null

echo 'Supplier seed completed.'
