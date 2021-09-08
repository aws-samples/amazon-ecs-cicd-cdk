package com.aws.pattern.infrastructure;

import java.util.Arrays;

import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.CfnOutputProps;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuUtilizationScalingProps;
import software.amazon.awscdk.services.ecs.DeploymentController;
import software.amazon.awscdk.services.ecs.DeploymentControllerType;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.ScalableTaskCount;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocolVersion;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;

public class EcsInfrastructureStack extends Stack {
	
	public static String ECS_CLUSTER_NAME = "BlueGreenPipelineJavaAppCluster";
	public static String ECS_SERVICE_NAME = "BlueGreenPipelineJavaAppService";
	
	public ApplicationListener greenWebListener;
	public ApplicationListener blueWebListener;
	
	public ApplicationTargetGroup greenTargetGroup;
	public ApplicationTargetGroup blueTargetGroup;

	public EcsInfrastructureStack(final Construct scope, final String id, final StackProps props) {
		super(scope, id, props);

		// *******************************************//
		// ************** VPC, Subnets****************//
		// *******************************************//

		Vpc vpc = Vpc.Builder.create(this, "MyVpcId").maxAzs(2).build();

		// *******************************************//
		// *************Security groups***************//
		// *******************************************//

		ISecurityGroup albSecurityGroup = SecurityGroup.Builder.create(this, "albSecurityGroupId")
				.securityGroupName("albSecurityGroup").allowAllOutbound(true).vpc(vpc).build();
		albSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(8080)); 
		albSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(8100));

		ISecurityGroup serviceSecurityGroup = SecurityGroup.Builder.create(this, "serviceSecurityGroupId")
				.securityGroupName("serviceSecurityGroup").allowAllOutbound(true).vpc(vpc).build();
		serviceSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(8080));

		// *******************************************//
		// ******ALB, Listeners, Target groups********//
		// **************Web APP**********************//

		ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "myALBId")
				.deletionProtection(false)
				.http2Enabled(true)
				.internetFacing(true)
				.loadBalancerName("myALB")
				.vpc(vpc)
				.vpcSubnets(SubnetSelection.builder().subnets(vpc.getPublicSubnets()).build())
				.securityGroup(albSecurityGroup).build();

		HealthCheck defaultHealthCheck = HealthCheck.builder().build();

		greenTargetGroup = ApplicationTargetGroup.Builder.create(this, "greenTGId")
				.targetGroupName("greenTG")
				.healthCheck(defaultHealthCheck)
				.port(8080)
				.protocol(ApplicationProtocol.HTTP)
				.protocolVersion(ApplicationProtocolVersion.HTTP1)
				.targetType(TargetType.IP)
				.vpc(vpc)
				.build();

		BaseApplicationListenerProps greenWebApplicationListenerProps = BaseApplicationListenerProps.builder()
				.port(8100)
				.protocol(ApplicationProtocol.HTTP)
				.open(true)
				.defaultAction(ListenerAction.forward(Arrays.asList(greenTargetGroup)))
				.build();
		greenWebListener = alb.addListener("greenListener", greenWebApplicationListenerProps);

		greenTargetGroup.registerListener(greenWebListener);

		// ********************************************************************//
		// *******ECS Cluster, task definition ECS Services with ALB***********//
		// ********************************************************************//

		Cluster cluster = Cluster.Builder.create(this, "MyClusterId").clusterName(ECS_CLUSTER_NAME).vpc(vpc)
				.build();


		// Create a load-balanced Fargate service and make it public
		ApplicationLoadBalancedFargateService fargateService = ApplicationLoadBalancedFargateService.Builder
				.create(this, "MyServiceId")
				.serviceName(ECS_SERVICE_NAME)
				.cluster(cluster)
				.loadBalancer(alb)
				.listenerPort(8080)
				.protocol(ApplicationProtocol.HTTP)
				.publicLoadBalancer(true)
				.taskImageOptions(
		                 ApplicationLoadBalancedTaskImageOptions.builder()
		                 	     .image(ContainerImage.fromRegistry("amazon/amazon-ecs-sample"))
		                         .build())
				.taskSubnets(SubnetSelection.builder()
						.subnets(vpc.getPrivateSubnets()).build())
						.assignPublicIp(false)
						.securityGroups(Arrays.asList(serviceSecurityGroup))
						.cpu(512)
						.desiredCount(2)
						.memoryLimitMiB(2048)
						.deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
			    .build();

		blueTargetGroup = fargateService.getTargetGroup();
		blueWebListener = fargateService.getListener();

		// AutoScaling Policy
		EnableScalingProps enableWebScalingProps = EnableScalingProps.builder().minCapacity(2).maxCapacity(5).build();

		// CPU usage configuration
		CpuUtilizationScalingProps cpuWebUtilizationScalingProps = CpuUtilizationScalingProps.builder()
				.targetUtilizationPercent(40).scaleInCooldown(Duration.seconds(60))
				.scaleOutCooldown(Duration.seconds(60)).build();
		ScalableTaskCount scalableWebTaskCount = fargateService.getService().autoScaleTaskCount(enableWebScalingProps);
		scalableWebTaskCount.scaleOnCpuUtilization("CPUWebScalingPolicy", cpuWebUtilizationScalingProps);
		
		// ECS Task execution role
		RoleProps rolePropsExecRole = RoleProps.builder()
				.assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
				.build();
		Role ecsTaskExecRole = new Role(this, "ecsTaskRoleSampleApp", rolePropsExecRole);
		ecsTaskExecRole.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy"));
		
		
		LogGroup logGroup = LogGroup.Builder.create(this, "lLogGroup")
				.logGroupName("awslogs-SampleApplication")
				.build();
		AwsLogDriverProps logDriverProps = AwsLogDriverProps.builder()
				.logGroup(logGroup)
				.streamPrefix("SampleApplication")
				.build();
		LogDriver.awsLogs(logDriverProps);
		
		
		// *******************************************//
	    // *******************ECR repo****************//
	    // *******************************************//
		
		software.amazon.awscdk.services.ecr.IRepository ecrRepo = software.amazon.awscdk.services.ecr.Repository.Builder.create(this, "EcrRepo")
				.repositoryName("sampleapp")
				.imageTagMutability(TagMutability.MUTABLE)
				.imageScanOnPush(false)
				.build();
		
		
		
		// *******************************************//
	    // *******************CDK outputs*************//
	    // *******************************************//
		
		CfnOutputProps ecrRepoOutputProps = CfnOutputProps.builder()
			.value(ecrRepo.getRepositoryUri())
			.description("ECR repository URI")
			.exportName("ECRRepository")
			.build();
		CfnOutput ecrRepoOutput = new CfnOutput(this, "ecrRepoOutput", ecrRepoOutputProps);
		
		
		CfnOutputProps ecsTaskExecRoleOutputProps = CfnOutputProps.builder()
				.value(ecsTaskExecRole.getRoleArn())
				.description("ECS task execution role ARN")
				.exportName("ECSTaskRoleExec")
				.build();
		CfnOutput ecsTaskExecRoleOutput = new CfnOutput(this, "ecsTaskRoleExecOutput", ecsTaskExecRoleOutputProps);

	}

}
