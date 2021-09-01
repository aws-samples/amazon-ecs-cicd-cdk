package com.aws.pattern.cicd;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.aws.pattern.infrastructure.EcsInfrastructureStack;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.CustomResource;
import software.amazon.awscdk.core.CustomResourceProps;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageProps;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.PolicyStatementProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;

public class CicdBlueGreenStack extends Stack {
	
	public static String ECS_DEPLOYMENT_GROUP_NAME  = "SampleAppECSBlueGreenDeployGroup";
	public static String ECS_DEPLOYMENT_CONFIG_NAME = "CodeDeployDefault.ECSAllAtOnce";
	public static Number ECS_TASKSET_TERMINATION_WAIT_TIME = 10;

	public CicdBlueGreenStack(final App scope, final String id, final StackProps props, EcsInfrastructureStack ecsInfraStack) {
		super(scope, id, props);
		
		// *******************************************//
		// ************CodeCommit repo****************//
		// *******************************************//

		// Checkout repo
		String repoName = (String) scope.getNode().tryGetContext("repo_name");
		IRepository code = Repository.fromRepositoryName(this, "MyRepo", repoName);
		

		// *******************************************//
		// ************CodeBuild**********************//
		// *******************************************//
		
		
		// CodeBuild service role
		RoleProps rolePropsCodeBuildServiceRole = RoleProps.builder()
				.assumedBy(new ServicePrincipal("codebuild.amazonaws.com"))
				.build();
		Role codeBuildServiceRole = new Role(this, "codeBuildServiceRole", rolePropsCodeBuildServiceRole);
		codeBuildServiceRole.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"));
		
		
		PolicyStatementProps codeBuildPolicyStatementProps = PolicyStatementProps.builder()
				.effect(Effect.ALLOW)
				.resources(Arrays.asList("*"))
				.actions(Arrays.asList("ecr:GetAuthorizationToken",
						"ecr:BatchCheckLayerAvailability",
						"ecr:InitiateLayerUpload",
						"ecr:UploadLayerPart",
						"ecr:CompleteLayerUpload",
						"ecr:PutImage"))
				.build();
		PolicyStatement codeBuildInlinePolicyStatement = new PolicyStatement(codeBuildPolicyStatementProps);
		codeBuildServiceRole.addToPolicy(codeBuildInlinePolicyStatement);
		
		PipelineProject dockerApiImageBuild = PipelineProject.Builder.create(this, "CDKSampleBuild")
				.buildSpec(BuildSpec.fromObject(new HashMap<String, Object>() {
					{
						put("version", "0.2");
						put("phases", new HashMap<String, Object>() {
							{
								put("install", new HashMap<String, Object>() {
									{
										put("commands",
												Arrays.asList("apt update",
														"yes Y | apt install amazon-ecr-credential-helper"));
									}
								});
								put("build", new HashMap<String, Object>() {
									{
										put("commands", Arrays.asList("mvn compile jib:build"));
									}
								});
							}
						});
						put("artifacts", new HashMap<String, Object>() {
							{
								put("files", Arrays.asList("imagedefinitions.json", "imageDetail.json", "appspec.yaml",
										"taskdef.json"));
							}
						});
					}
				})).environment(BuildEnvironment.builder().buildImage(LinuxBuildImage.STANDARD_5_0).build())
				.role(codeBuildServiceRole)
				.projectName("CodeBuildProject").build();

		Artifact sourceOutput = new Artifact();
		Artifact imageArtifact = new Artifact();

		// *******************************************//
		// ************CodeDeploy*********************//
		// *******************************************//
		
		// CodeDeploy Application
		EcsApplication ecsApplication = new EcsApplication(this, "mySampleApp");
		
		// CodeDeploy service role
		RoleProps rolePropsCodeDeployServiceRole = RoleProps.builder()
				.assumedBy(new ServicePrincipal("codedeploy.amazonaws.com"))
				.build();
		Role codeDeployServiceRole = new Role(this, "codeDeployServiceRole", rolePropsCodeDeployServiceRole);
		codeDeployServiceRole.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"));
		
		// IAM role for custom lambda function
		PolicyStatementProps policyStatementProps = PolicyStatementProps.builder()
				.effect(Effect.ALLOW)
				.resources(Arrays.asList("*"))
				.actions(Arrays.asList("iam:PassRole",
						"sts:AssumeRole",
						"codedeploy:List*",
						"codedeploy:Get*",
						"codedeploy:UpdateDeploymentGroup",
						"codedeploy:CreateDeploymentGroup",
						"codedeploy:DeleteDeploymentGroup"))
				.build();
		PolicyStatement inlinePolicyStatementForLambda = new PolicyStatement(policyStatementProps);
		
		RoleProps rolePropsLambdaServiceRole = RoleProps.builder()
				.assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
				.build();
		Role customLambdaServiceRole = new Role(this, "codeDeployCustomLambda", rolePropsLambdaServiceRole);
		customLambdaServiceRole.addToPolicy(inlinePolicyStatementForLambda);
		customLambdaServiceRole.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"));
		
		
		
		// Lambda function to create a custom resource
		FunctionProps lambdaProps = FunctionProps.builder()
				.runtime(Runtime.PYTHON_3_8)
				.handler("create_deployment_group.handler")
				.role(customLambdaServiceRole)
				.description("Custom resource to create a CodeDeploy deployment group")
				.memorySize(128)
				.timeout(Duration.seconds(60))
				.code(Code.fromAsset("src/main/resources/cdk_custom_resources"))
				.build();
		Function deploymentGroupLambda = new Function(this, "deploymentGroupLambda", lambdaProps);
		
		Map<String, Object> mapProperties = new HashMap<>();
		mapProperties.put("ApplicationName", ecsApplication.getApplicationName());
		mapProperties.put("DeploymentGroupName", ECS_DEPLOYMENT_GROUP_NAME);
		mapProperties.put("DeploymentConfigName", ECS_DEPLOYMENT_CONFIG_NAME);
		mapProperties.put("ServiceRoleArn", codeDeployServiceRole.getRoleArn());
		mapProperties.put("BlueTargetGroup", ecsInfraStack.blueTargetGroup.getTargetGroupName());
		mapProperties.put("GreenTargetGroup", ecsInfraStack.greenTargetGroup.getTargetGroupName());
		mapProperties.put("ProdListenerArn", ecsInfraStack.blueWebListener.getListenerArn());
		mapProperties.put("TestListenerArn", ecsInfraStack.greenWebListener.getListenerArn());
		mapProperties.put("EcsClusterName", EcsInfrastructureStack.ECS_CLUSTER_NAME);
		mapProperties.put("EcsServiceName", EcsInfrastructureStack.ECS_SERVICE_NAME);
		mapProperties.put("TerminationWaitTime", ECS_TASKSET_TERMINATION_WAIT_TIME);
		CustomResourceProps customResourceProp = new CustomResourceProps.Builder()
				.serviceToken(deploymentGroupLambda.getFunctionArn())
				.properties(mapProperties)
				.build();
		CustomResource customResource = new CustomResource(this, "customEcsDeploymentGroup", customResourceProp);
		

		// Resource created outside CDK
		EcsDeploymentGroupAttributes ecsDeploymentGroupAttributes = EcsDeploymentGroupAttributes.builder()
				.application(ecsApplication)
				.deploymentGroupName(ECS_DEPLOYMENT_GROUP_NAME)
				.deploymentConfig(EcsDeploymentConfig.fromEcsDeploymentConfigName(this, "ecsDeploymentConfig", ECS_DEPLOYMENT_CONFIG_NAME))
				.build();

		// Resource created outside CDK
		IEcsDeploymentGroup EcsDeploymentGroup = software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup
				.fromEcsDeploymentGroupAttributes(this, "ecsDeploymentConfig",
						ecsDeploymentGroupAttributes);

		CodeDeployEcsContainerImageInput codeDeployEcsContainerImageInput = CodeDeployEcsContainerImageInput.builder()
				.input(sourceOutput).taskDefinitionPlaceholder("IMAGE_NAME").build();

		
		
		
		
		// *******************************************//
		// ************CodePipeline*******************//
		// *******************************************//
		
		
		// CodePipeline service role
		RoleProps rolePropsCodePipelineServiceRole = RoleProps.builder()
				.assumedBy(new ServicePrincipal("codepipeline.amazonaws.com"))
				.build();
		Role codePipelineServiceRole = new Role(this, "codePipelineServiceRole", rolePropsCodePipelineServiceRole);
		
		PolicyStatementProps codePipelinePolicyStatementProps = PolicyStatementProps.builder()
				.effect(Effect.ALLOW)
				.resources(Arrays.asList("*"))
				.actions(Arrays.asList("iam:PassRole",
						"sts:AssumeRole",
						"codecommit:Get*",
						"codecommit:List*",
						"codecommit:GitPull",
						"codecommit:UploadArchive",
						"codecommit:CancelUploadArchive",
						"codebuild:BatchGetBuilds",
						"codebuild:StartBuild",
						"codedeploy:CreateDeployment",
						"codedeploy:Get*",
						"codedeploy:RegisterApplicationRevision"))
				.build();
		PolicyStatement codePipelineInlinePolicyStatement = new PolicyStatement(codePipelinePolicyStatementProps);
		codePipelineServiceRole.addToPolicy(codePipelineInlinePolicyStatement);
		
		
		

		Pipeline.Builder.create(this, "MySamplePipeline")
				.stages(Arrays.asList(
						StageProps.builder().stageName("Source")
								.actions(Arrays.asList(CodeCommitSourceAction.Builder.create().actionName("Source")
										.branch("main").repository(code).output(sourceOutput).build()))
								.build(),
						StageProps.builder().stageName("Build")
								.actions(Arrays.asList(CodeBuildAction.Builder.create().actionName("Build")
										.project(dockerApiImageBuild).input(sourceOutput)
										.outputs(Arrays.asList(imageArtifact)).build()))
								.build(),
						StageProps.builder().stageName("Deploy")
								.actions(Arrays.asList(CodeDeployEcsDeployAction.Builder.create()
										.actionName("DeployBlueGreenToECS").deploymentGroup(EcsDeploymentGroup)
										.taskDefinitionTemplateInput(sourceOutput).appSpecTemplateInput(sourceOutput)
										.containerImageInputs(Arrays.asList(codeDeployEcsContainerImageInput)).build()))
								.build()))
				.role(codePipelineServiceRole)
				.build();

	}

}
