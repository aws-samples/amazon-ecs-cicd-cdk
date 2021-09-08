## amazon-ecs-cicd-cdk
This project provides sample code on how to deploy a containerized Java microservice with a Blue/Green Deployment Strategy using AWS CodePipeline and to create the underlying Amazon ECS infrastructure using AWS Cloud Development Kit (CDK) in Java.


This project contains the following CDK stacks:
- MyEcsClusterInfrastructureCdkStack: Creates the ECS Cluster and underlying infrastructure such as VPC, subnets, security groups, and application load balancer.
- MyCICDBlueGreenStack: Creates the pipeline resources such as AWS CodeBuild and AWS CodeDeploy.

To know more about creating a Blue/Green pipeline for Java microservices on Amazon ECS using AWS CDK and AWS CodePipeline please visit the following link:


## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.

