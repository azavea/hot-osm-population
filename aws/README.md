# aws-batch-example

This is an example [AWS Batch cloudformation stack](https://aws.amazon.com/batch/). Batch is a way to run containerised jobs in a managed way. This stack uses Spotfleet.
## Setup
1. `npm install`
2. Create a stack with `npx cfn-config create staging cloudformation/template.json -c bucket -t bucket`

## Resources
1. Needs an ECR and image pushed to the ECR. You can point to the image using the `ImageUrl` parameter in the cloudformation.
2. The task is in the task/ directory. You can build and push into ECR. Uses a basic osmlint example.
3. Parameters to the task are passed through the lambda, in case you want to override the defaults.
3. A lambda function with a schedule. This function should be archived and put on S3 using util/upload.sh. Example: `./util/upload.sh lambda bucket/key`

