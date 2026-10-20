#!/bin/sh
set -eu
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION="${AWS_REGION:-ap-southeast-2}"
aws_local() { aws --endpoint-url http://localhost:4566 "$@"; }
for bucket in "${FOC_USER_BUCKET:-foc-dev-user}" "${FOC_SUPPLIER_BUCKET:-foc-dev-supplier}"; do
  if ! aws_local s3api head-bucket --bucket "$bucket" 2>/dev/null; then
    aws_local s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$AWS_DEFAULT_REGION" >/dev/null
  fi
  aws_local s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
  aws_local s3api put-bucket-encryption --bucket "$bucket" --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
done
touch /tmp/foc-storage-ready
