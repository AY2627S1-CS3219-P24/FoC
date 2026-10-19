#!/usr/bin/env bash
set -euo pipefail

services=(user supplier order credit gateway)

fail() {
  echo "$1" >&2
  exit 1
}

register_with_image() {
  local source_arn=$1
  local name=$2
  local image=$3
  local work=$4
  local registered
  aws ecs describe-task-definition --task-definition "$source_arn" --query taskDefinition > "$work/task-definition.json"
  jq --arg name "$name" --arg image "$image" '
    if ([.containerDefinitions[] | select(.name == $name)] | length) != 1 then
      error("expected exactly one container named \($name)")
    else
      .containerDefinitions |= map(if .name == $name then .image = $image else . end)
      | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy, .deregisteredAt)
    end
  ' "$work/task-definition.json" > "$work/register.json"
  registered=$(jq -er --arg name "$name" '.containerDefinitions[] | select(.name == $name) | .image' "$work/register.json")
  if [ "$registered" != "$image" ]; then
    fail "Refusing to register $name with image $registered"
  fi
  aws ecs register-task-definition --cli-input-json "file://$work/register.json" \
    --query taskDefinition.taskDefinitionArn --output text
}

release() {
  local deployment_file=$1
  local registry=$2
  local release_sha=$3
  local cluster service image source_arn new_arn
  cluster=$(jq -er '.cluster' "$deployment_file")
  for service in "${services[@]}"; do
    image="$registry/$cluster/$service:$release_sha"
    source_arn=$(jq -er --arg service "$service" '.task_definitions[$service]' "$deployment_file")
    new_arn=$(register_with_image "$source_arn" "$service" "$image" "$RUNNER_TEMP")
    aws ecs update-service --cluster "$cluster" --service "$service" --task-definition "$new_arn" >/dev/null
  done
  aws ecs wait services-stable --cluster "$cluster" --services "${services[@]}"
}

command=${1:-}
case "$command" in
  release)
    [ "$#" -eq 4 ] || fail 'usage: deploy-ecs.sh release <deployment-json> <registry> <release-sha>'
    release "$2" "$3" "$4"
    ;;
  *)
    fail 'usage: deploy-ecs.sh release <deployment-json> <registry> <release-sha>'
    ;;
esac
