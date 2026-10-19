#!/bin/sh
set -eu
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION="${AWS_REGION:-ap-southeast-1}"
aws_local() { aws --endpoint-url http://localhost:4566 "$@"; }
for bucket in "${FOC_USER_BUCKET:-foc-dev-user}" "${FOC_SUPPLIER_BUCKET:-foc-dev-supplier}"; do
  if ! aws_local s3api head-bucket --bucket "$bucket" 2>/dev/null; then
    aws_local s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$AWS_DEFAULT_REGION" >/dev/null
  fi
  aws_local s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
  aws_local s3api put-bucket-encryption --bucket "$bucket" --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
done

# Match the production topic/queue naming and delivery settings. Subscriptions
# must be added alongside actual service event contracts and production routes.
for service in user supplier order credit; do
  aws_local sns create-topic --name "foc-dev-$service-events" >/dev/null
  dlq_url=$(aws_local sqs create-queue --queue-name "foc-dev-$service-dlq" --query QueueUrl --output text)
  aws_local sqs set-queue-attributes --queue-url "$dlq_url" --attributes MessageRetentionPeriod=1209600 >/dev/null
  dlq_arn=$(aws_local sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  queue_url=$(aws_local sqs create-queue --queue-name "foc-dev-$service-events" --query QueueUrl --output text)
  cat > /tmp/foc-queue-attributes.json <<ATTRIBUTES
{
  "VisibilityTimeout": "60",
  "ReceiveMessageWaitTimeSeconds": "20",
  "MessageRetentionPeriod": "345600",
  "SqsManagedSseEnabled": "true",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":5}"
}
ATTRIBUTES
  aws_local sqs set-queue-attributes --queue-url "$queue_url" --attributes file:///tmp/foc-queue-attributes.json >/dev/null
  queue_arn=$(aws_local sqs get-queue-attributes --queue-url "$queue_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  cat > /tmp/foc-redrive-allow.json <<ATTRIBUTES
{"RedriveAllowPolicy":"{\"redrivePermission\":\"byQueue\",\"sourceQueueArns\":[\"$queue_arn\"]}"}
ATTRIBUTES
  aws_local sqs set-queue-attributes --queue-url "$dlq_url" --attributes file:///tmp/foc-redrive-allow.json >/dev/null
done
rm /tmp/foc-queue-attributes.json /tmp/foc-redrive-allow.json
touch /tmp/foc-resources-ready
